package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

class IccoaInputServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun unusedInputConnectionCanCloseThenReconnectWithEncryptedEvents() {
        val events = LinkedBlockingQueue<Int>()
        val logs = LinkedBlockingQueue<String>()
        val cipher = IccoaControl.Cipher(ByteArray(16) { it.toByte() })
        val server = IccoaInputServer(InetSocketAddress(loopback, 0), loopback, { socket ->
            IccoaInputReader.read(socket.getInputStream(), cipher::decrypt,
                IccoaInputJson { events.add(it.getInt("type")) })
        }, { logs.add(it) })
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit { server.run() }
        try {
            Socket(loopback, server.localPort).use { it.shutdownOutput() }
            assertEquals("UIBC 4321 connected", logs.poll(3, TimeUnit.SECONDS))
            assertEquals("UIBC 4321 clean-eof; waiting-for-reconnect", logs.poll(3, TimeUnit.SECONDS))
            repeat(2) { index ->
                Socket(loopback, server.localPort).use { car ->
                    car.getOutputStream().write(cipher.encrypt("{\"type\":${index + 1}}".toByteArray()))
                    car.shutdownOutput()
                    assertEquals(index + 1, events.poll(3, TimeUnit.SECONDS))
                    assertEquals("UIBC 4321 connected", logs.poll(3, TimeUnit.SECONDS))
                    assertEquals("UIBC 4321 clean-eof; waiting-for-reconnect", logs.poll(3, TimeUnit.SECONDS))
                }
            }
            assertFalse(worker.isDone)
        } finally {
            server.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun cleanAndTruncatedInputAreDistinguishedAtEveryPacketBoundary() {
        val cipher = IccoaControl.Cipher(ByteArray(16))
        val packet = cipher.encrypt("{\"type\":1}".toByteArray())
        fun read(bytes: ByteArray): List<Int> {
            val events = mutableListOf<Int>()
            IccoaInputReader.read(ByteArrayInputStream(bytes), cipher::decrypt,
                IccoaInputJson { events += it.getInt("type") })
            return events
        }
        assertTrue(read(byteArrayOf()).isEmpty())
        assertEquals(listOf(1, 1), read(packet + packet))
        for (size in 1 until packet.size) {
            assertThrows("Truncated encrypted packet: $size bytes", EOFException::class.java) {
                read(packet.copyOf(size))
            }
            assertThrows("Truncated next encrypted packet: $size bytes", EOFException::class.java) {
                read(packet + packet.copyOf(size))
            }
        }
    }

    @Test fun plaintextObjectsCanSpanReadsButUnfinishedJsonIsAnError() {
        val events = mutableListOf<Int>()
        val bytes = "{\"type\":1}{\"type\":2}\n".toByteArray()
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        IccoaInputReader.read(fragmented, { error("Plaintext must not decrypt") },
            IccoaInputJson { events += it.getInt("type") })
        assertEquals(listOf(1, 2), events)
        assertThrows(EOFException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream("{\"type\":".toByteArray()), { it }, IccoaInputJson {})
        }
    }

    @Test fun invalidCiphertextAndOversizedPacketsAreStillRejected() {
        val cipher = IccoaControl.Cipher(ByteArray(16))
        val corrupt = cipher.encrypt("{\"type\":1}".toByteArray()).apply {
            this[lastIndex] = (last().toInt() xor 1).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(corrupt), cipher::decrypt, IccoaInputJson {})
        }
        val invalid = ByteBuffer.allocate(20).putInt(12).put(ByteArray(12)).putInt(65537).array()
        assertThrows(IllegalArgumentException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(invalid), cipher::decrypt, IccoaInputJson {})
        }
    }

    @Test fun completeEncryptedEnvelopeWithUnfinishedJsonIsNotCleanEof() {
        val cipher = IccoaControl.Cipher(ByteArray(16))
        assertThrows(EOFException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(cipher.encrypt("{\"type\":".toByteArray())),
                cipher::decrypt, IccoaInputJson {})
        }
    }

    @Test fun cleanEofReleasesActiveGestureExactlyOnce() {
        IccoaInputGesture().finish()
        var releases = 0
        val gesture = IccoaInputGesture { releases++ }
        gesture.accept(0)
        for (action in listOf(2, 5 or (1 shl 8), 6 or (1 shl 8), 3)) {
            gesture.accept(action)
        }
        gesture.finish()
        gesture.finish()
        assertEquals(1, releases)
        gesture.accept(0)
        gesture.accept(1)
        gesture.finish()
        assertEquals(1, releases)
        gesture.accept(0)
        gesture.finish()
        assertEquals(2, releases)
    }

    @Test fun truncatedConnectionIsFatalAndItsSocketIsClosed() {
        val server = IccoaInputServer(InetSocketAddress(loopback, 0), loopback, { socket ->
            IccoaInputReader.read(socket.getInputStream(), { it }, IccoaInputJson {})
        }, {})
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit { server.run() }
        try {
            Socket(loopback, server.localPort).use { car ->
                car.soTimeout = 3000
                car.getOutputStream().write(byteArrayOf(0, 0))
                car.shutdownOutput()
                val error = assertThrows(ExecutionException::class.java) { worker.get(3, TimeUnit.SECONDS) }
                assertTrue(error.cause is EOFException)
                assertEquals(-1, car.getInputStream().read())
            }
        } finally {
            server.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun stoppingServerUnblocksAcceptAndInputRead() {
        for (connect in listOf(false, true)) {
            val connected = LinkedBlockingQueue<Socket>()
            val server = IccoaInputServer(InetSocketAddress(loopback, 0), loopback, { socket ->
                connected.add(socket)
                IccoaInputReader.read(socket.getInputStream(), { it }, IccoaInputJson {})
            }, {})
            val executor = Executors.newSingleThreadExecutor()
            val worker = executor.submit { server.run() }
            var car: Socket? = null
            try {
                if (connect) {
                    car = Socket(loopback, server.localPort).apply { soTimeout = 3000 }
                    assertNotNull(connected.poll(3, TimeUnit.SECONDS))
                }
                server.close()
                runCatching { worker.get(3, TimeUnit.SECONDS) }.exceptionOrNull()?.let {
                    assertTrue(it is ExecutionException)
                }
                assertTrue(worker.isDone)
                if (car != null) assertEquals(-1, car.getInputStream().read())
            } finally {
                server.close(); car?.close(); executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }
}
