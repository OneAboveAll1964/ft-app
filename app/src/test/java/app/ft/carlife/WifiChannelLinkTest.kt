package app.ft.carlife

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class WifiChannelLinkTest {
    @Test fun aTimeoutDoesNotInterruptACommandBeingProcessedOrRetireItsCompletedSession() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val bound = LinkedBlockingQueue<Int>()
        val connected = LinkedBlockingQueue<Long>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = LinkedBlockingQueue<String>()
        val admission = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(mapOf(CarLifeProtocol.CH_CMD to port),
            onBound = { bound.put(it) }, admissionDispatcher = admission)
        var client: Socket? = null
        try {
            link.start(scope, { _, _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                finished.countDown()
            }, {}, { connected.put(link.commandGeneration) }, { closed.put(it) })
            assertNotNull(bound.poll(5, TimeUnit.SECONDS))
            client = Socket("127.0.0.1", port)
            val generation = requireNotNull(connected.poll(5, TimeUnit.SECONDS))
            client.getOutputStream().write(CarLifeFraming.cmd(CarLifeProtocol.CMD_HU_PROTOCOL_VERSION))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Admission remains free while the IO handler is processing the reply.
            assertFalse(withContext(admission) { link.retryCommand(generation) })
            assertTrue(link.connected)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            val checked = AtomicBoolean(false)
            withTimeout(5_000) {
                while (!checked.get()) {
                    assertFalse(withContext(admission) {
                        link.retryCommand(generation) { checked.set(true); false }
                    })
                    delay(10)
                }
            }
            assertTrue(link.connected)
            assertNull(closed.poll())
        } finally {
            release.countDown()
            link.stop()
            client?.close()
            scope.cancel()
            admission.close()
        }
    }

    @Test fun aTimedOutCommandCanReconnectOnTheSameListenerAndAnOldTimeoutCannotCloseItsReplacement() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val bound = LinkedBlockingQueue<Int>()
        val connected = LinkedBlockingQueue<Long>()
        val closed = LinkedBlockingQueue<String>()
        val admission = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(mapOf(CarLifeProtocol.CH_CMD to port),
            onBound = { bound.put(it) }, admissionDispatcher = admission)
        val clients = mutableListOf<Socket>()
        try {
            link.start(scope, { _, _, _ -> }, {}, { connected.put(link.commandGeneration) }, { closed.put(it) })
            assertNotNull(bound.poll(5, TimeUnit.SECONDS))
            clients += Socket("127.0.0.1", port)
            val first = requireNotNull(connected.poll(5, TimeUnit.SECONDS))
            assertTrue(withContext(admission) { link.retryCommand(first) })
            assertEquals("CarLife startup negotiation timed out", closed.poll(5, TimeUnit.SECONDS))
            assertFalse(link.connected)
            val replacement = Socket("127.0.0.1", port).also { it.soTimeout = 5_000; clients += it }
            val second = requireNotNull(connected.poll(5, TimeUnit.SECONDS))
            assertTrue(second > first)
            assertFalse(withContext(admission) { link.retryCommand(first) })
            assertTrue(link.connected)
            assertFalse(link.sendForCommand(first, CarLifeProtocol.CH_CMD, byteArrayOf(99)))
            assertTrue(link.sendForCommand(second, CarLifeProtocol.CH_CMD, byteArrayOf(42)))
            assertEquals(42, replacement.getInputStream().read())
            // Also drains the old reader's disconnect callback on the admission dispatcher.
            withContext(admission) { assertTrue(link.connected) }
            assertNull(closed.poll())
            assertNull("the listener was reused without binding again", bound.poll())
        } finally {
            link.stop()
            clients.forEach { runCatching { it.close() } }
            scope.cancel()
            admission.close()
        }
    }

    @Test fun commandAdmissionCannotBeSplitByAQueuedGroupChange() {
        val port = ServerSocket(0).use { it.localPort }
        val bound = LinkedBlockingQueue<Int>()
        val stamps = LinkedBlockingQueue<Int>()
        val changed = LinkedBlockingQueue<Int>()
        val group = AtomicInteger(1)
        val admission = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(mapOf(CarLifeProtocol.CH_CMD to port),
            refuse = {
                // Simulate a group callback becoming ready during socket admission.
                scope.launch(admission) { changed.put(group.incrementAndGet()) }
                null
            },
            onBound = { bound.put(it) },
            onCommandConnected = { stamps.put(group.get()) },
            admissionDispatcher = admission)
        var client: Socket? = null
        try {
            link.start(scope, { _, _, _ -> }, {}, {}, {})
            assertNotNull(bound.poll(5, TimeUnit.SECONDS))
            client = Socket("127.0.0.1", port)
            assertEquals(1, stamps.poll(5, TimeUnit.SECONDS))
            assertEquals(2, changed.poll(5, TimeUnit.SECONDS))
        } finally {
            link.stop()
            client?.close()
            scope.cancel()
            admission.close()
        }
    }

    @Test fun retiringCommandFinishesBeforeItsReplacementCanBeAnnounced() {
        val port = ServerSocket(0).use { it.localPort }
        val bound = LinkedBlockingQueue<Int>()
        val connected = LinkedBlockingQueue<Boolean>()
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val admission = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(mapOf(CarLifeProtocol.CH_CMD to port),
            onBound = { bound.put(it) }, admissionDispatcher = admission)
        var first: Socket? = null
        var second: Socket? = null
        try {
            link.start(scope, { _, _, _ -> }, {}, { connected.put(true) }, {
                closing.countDown()
                check(releaseClose.await(5, TimeUnit.SECONDS))
            })
            assertNotNull(bound.poll(5, TimeUnit.SECONDS))
            first = Socket("127.0.0.1", port)
            assertEquals(true, connected.poll(5, TimeUnit.SECONDS))
            first.close()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            second = Socket("127.0.0.1", port)
            assertNull(connected.poll(200, TimeUnit.MILLISECONDS))
            releaseClose.countDown()
            assertEquals(true, connected.poll(5, TimeUnit.SECONDS))
            assertTrue(link.connected)
        } finally {
            releaseClose.countDown()
            link.stop()
            first?.close()
            second?.close()
            scope.cancel()
            admission.close()
        }
    }

    @Test fun replacingMediaKeepsCommandAndUsesTheNewStream() {
        val cmd = CarLifeProtocol.CH_CMD
        val media = CarLifeProtocol.CH_MEDIA
        val reservations = listOf(ServerSocket(0), ServerSocket(0))
        val ports = mapOf(cmd to reservations[0].localPort, media to reservations[1].localPort)
        reservations.forEach { it.close() }
        val bound = LinkedBlockingQueue<Int>()
        val events = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<String>()
        val connections = AtomicInteger()
        val commandGroups = LinkedBlockingQueue<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(ports, onBound = { bound.put(it) },
            onCommandConnected = { commandGroups.put(connections.get()) })
        val clients = mutableListOf<Socket>()
        fun connect(channel: Int): Socket = Socket("127.0.0.1", ports.getValue(channel)).also {
            it.soTimeout = 5000
            clients += it
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var found = false
            while (!found && System.nanoTime() < deadline) {
                found = events.poll(100, TimeUnit.MILLISECONDS)?.contains("${CarLifeProtocol.channelName(channel)} connected from") == true
            }
            assertTrue("channel accepted", found)
        }
        try {
            link.start(scope, { _, _, _ -> }, { events.put(it) }, { connections.incrementAndGet() }, { closed.put(it) })
            repeat(2) { assertNotNull(bound.poll(5, TimeUnit.SECONDS)) }
            val command = connect(cmd)
            assertEquals(1, commandGroups.poll(5, TimeUnit.SECONDS))
            val oldMedia = connect(media)
            val newMedia = connect(media)
            assertTrue(link.connected)
            assertTrue(link.send(cmd, byteArrayOf(11)))
            assertEquals(11, command.getInputStream().read())
            assertTrue(link.send(media, byteArrayOf(22)))
            assertEquals(22, newMedia.getInputStream().read())
            // SO_LINGER(0) can report reset rather than EOF for the retired socket.
            assertEquals(-1, try { oldMedia.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            assertTrue(link.send(media, byteArrayOf(33)))
            assertEquals(33, newMedia.getInputStream().read())
            assertEquals(1, connections.get())
            assertNull(closed.poll())
            assertNull(commandGroups.poll())
            connect(cmd)
            assertEquals(2, commandGroups.poll(5, TimeUnit.SECONDS))
            assertEquals("head unit connected again", closed.poll(5, TimeUnit.SECONDS))
        } finally {
            link.stop()
            clients.forEach { runCatching { it.close() } }
            scope.cancel()
        }
    }
}
