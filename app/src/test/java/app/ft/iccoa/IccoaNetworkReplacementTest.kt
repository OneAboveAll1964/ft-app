package app.ft.iccoa

import android.content.Context
import android.net.Network
import android.os.Handler
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException
import java.net.Socket
import java.net.SocketAddress
import java.util.ArrayDeque

/** Exercises the actual session queue, not just the callback gate. */
class IccoaNetworkReplacementTest {
    @Test fun oldAuthenticationErrorMustNotCloseReplacementNetwork() = authenticationError(replaceNetwork = true)

    @Test fun currentAuthenticationErrorStillClosesSession() = authenticationError(replaceNetwork = false)

    private fun authenticationError(replaceNetwork: Boolean) {
        val pending = ArrayDeque<Runnable>()
        val closed = mutableListOf<String>()
        mockConstruction(Handler::class.java) { handler, _ ->
            doAnswer { call ->
                pending.addLast(call.getArgument(0, Runnable::class.java))
                true
            }.`when`(handler).post(any(Runnable::class.java))
        }.use {
            val session = IccoaSession(
                context = mock(Context::class.java), onStatus = {}, onPinRequired = {},
                onReady = { _, _ -> }, onMessage = { _, _, _ -> },
                onClosed = { message, _ -> closed += message }, vehicleProfileId = "review")
            val attemptType = Class.forName("app.ft.iccoa.IccoaSession\$Attempt")
            val constructor = attemptType.getDeclaredConstructor(IccoaClientProfile::class.java)
                .apply { isAccessible = true }
            val attempt = constructor.newInstance(IccoaClientProfile("abcdef", "02:00:00:00:00:01"))
            IccoaSession::class.java.getDeclaredField("current").apply { isAccessible = true }
                .set(session, attempt)
            @Suppress("UNCHECKED_CAST")
            val gate = attemptType.getDeclaredField("networkGate").apply { isAccessible = true }
                .get(attempt) as IccoaNetworkCallbackGate<Network>
            val oldNetwork = mock(Network::class.java)
            val replacementNetwork = mock(Network::class.java)
            gate.onAvailable(oldNetwork)
            try {
                mockConstruction(Socket::class.java) { socket, _ ->
                    doThrow(IOException("simulated connect failure"))
                        .`when`(socket).connect(any(SocketAddress::class.java), anyInt())
                }.use {
                    val authenticate = IccoaSession::class.java.getDeclaredMethod(
                        "authenticate", attemptType, Network::class.java,
                        String::class.java, String::class.java, String::class.java, ByteArray::class.java)
                        .apply { isAccessible = true }
                    authenticate.invoke(session, attempt, oldNetwork,
                        "192.0.2.2", "192.0.2.1", "123456", null)
                    verify(it.constructed().single(), atLeastOnce()).close()
                }
                assertFalse("Authentication failure should be queued on main", pending.isEmpty())
                // onAvailable(B) is delivered before the already queued fail(A) executes.
                if (replaceNetwork) gate.onAvailable(replacementNetwork)
                while (pending.isNotEmpty()) pending.removeFirst().run()
                if (replaceNetwork) {
                    assertTrue("An error from the old Network closed the replacement session: $closed", closed.isEmpty())
                    assertTrue(gate.isCurrent(replacementNetwork))
                } else assertEquals(1, closed.size)
            } finally {
                session.stop()
            }
        }
    }

    @Test fun queuedActivationDoesNotPublishOldNetworkListeners() = withSession { session, attempt, pending, ready, closed ->
        val old = mock(Network::class.java)
        val replacement = mock(Network::class.java)
        select(session, attempt, old)
        field(attempt, "pin").set(attempt, "123456")
        val activation = java.util.concurrent.FutureTask {
            val method = IccoaSession::class.java.getDeclaredMethod("activateNetwork", attempt.javaClass,
                Network::class.java, IccoaControl.Cipher::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
            method.invoke(session, attempt, old, mock(IccoaControl.Cipher::class.java), "192.0.2.2", "192.0.2.1") as Boolean
        }
        pending.addLast(activation)
        select(session, attempt, replacement)
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertFalse(activation.get())
        assertEquals(0, ready.size)
        assertTrue(closed.isEmpty())
        assertNull(session.cipher)
        assertEquals("123456", field(attempt, "pin").get(attempt))
    }

    @Test fun replacementAfterKeyCommitClosesOwnedResourcesWithoutReusingPin() = withSession { session, attempt, pending, ready, closed ->
        val old = mock(Network::class.java)
        select(session, attempt, old)
        field(attempt, "authenticatedNetwork").set(attempt, old)
        field(attempt, "cipher").set(attempt, mock(IccoaControl.Cipher::class.java))
        var released = false
        @Suppress("UNCHECKED_CAST")
        val resources = field(attempt, "resources").get(attempt) as MutableList<java.io.Closeable>
        resources += java.io.Closeable { released = true }
        assertFalse(select(session, attempt, mock(Network::class.java)))
        assertTrue(released)
        assertNull(session.cipher)
        assertNull(field(attempt, "pin").get(attempt))
        assertEquals(listOf(IccoaCloseReason.TRANSPORT_FAILURE), closed)
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertTrue(ready.isEmpty())
        assertEquals(1, closed.size)
    }

    @Test fun repeatedAvailableOnCommittedNetworkKeepsSession() = withSession { session, attempt, _, _, closed ->
        val network = mock(Network::class.java)
        select(session, attempt, network)
        field(attempt, "authenticatedNetwork").set(attempt, network)
        field(attempt, "authStarted").setBoolean(attempt, true)
        assertTrue(select(session, attempt, network))
        assertTrue(field(attempt, "authStarted").getBoolean(attempt))
        assertTrue(closed.isEmpty())
    }

    @Test fun currentActivationPublishesListenersAndDiscardsPin() = withSession { session, attempt, _, ready, closed ->
        val network = mock(Network::class.java)
        val key = mock(IccoaControl.Cipher::class.java)
        select(session, attempt, network)
        field(attempt, "pin").set(attempt, "123456")
        mockConstruction(IccoaLaneServer::class.java).use { lanes ->
            val method = IccoaSession::class.java.getDeclaredMethod("activateNetwork", attempt.javaClass,
                Network::class.java, IccoaControl.Cipher::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
            assertEquals(true, method.invoke(session, attempt, network, key, "192.0.2.2", "192.0.2.1"))
            assertEquals(5, lanes.constructed().size)
            assertEquals(listOf(network), ready)
            assertSame(key, session.cipher)
            assertNull(field(attempt, "pin").get(attempt))
            assertTrue(closed.isEmpty())
        }
    }

    private fun select(session: IccoaSession, attempt: Any, network: Network): Boolean =
        IccoaSession::class.java.getDeclaredMethod("selectNetwork", attempt.javaClass, Network::class.java)
            .apply { isAccessible = true }.invoke(session, attempt, network) as Boolean

    private fun field(attempt: Any, name: String) = attempt.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun withSession(block: (IccoaSession, Any, ArrayDeque<Runnable>, MutableList<Network>, MutableList<IccoaCloseReason>) -> Unit) {
        val pending = ArrayDeque<Runnable>()
        val ready = mutableListOf<Network>()
        val closed = mutableListOf<IccoaCloseReason>()
        mockConstruction(Handler::class.java) { handler, _ ->
            doAnswer { call -> pending.addLast(call.getArgument(0, Runnable::class.java)); true }
                .`when`(handler).post(any(Runnable::class.java))
        }.use {
            val session = IccoaSession(mock(Context::class.java), {}, {},
                { network, _ -> ready += network }, { _, _, _ -> }, { _, cause -> closed += cause },
                vehicleProfileId = "test")
            val type = Class.forName("app.ft.iccoa.IccoaSession\$Attempt")
            val attempt = type.getDeclaredConstructor(IccoaClientProfile::class.java)
                .apply { isAccessible = true }.newInstance(IccoaClientProfile("abcdef", "02:00:00:00:00:01"))
            IccoaSession::class.java.getDeclaredField("current").apply { isAccessible = true }.set(session, attempt)
            try { block(session, attempt, pending, ready, closed) } finally { session.stop() }
        }
    }
}
