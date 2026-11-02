package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class IccoaLaneServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private class Harness : AutoCloseable {
        val executor = Executors.newCachedThreadPool()
        val lanes = mutableListOf<IccoaLaneServer>()
        val clients = mutableListOf<Socket>()
        override fun close() {
            lanes.forEach { it.close() }
            clients.forEach { it.close() }
            executor.shutdownNow()
            assertTrue("Lane workers must stop", executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun Harness.lane(port: Int, onFrame: (IccoaControl.Frame) -> Unit = {},
                             connected: (Socket) -> Unit = {}, disconnected: (Socket) -> Unit = {},
                             log: (String) -> Unit = {}): Pair<IccoaLaneServer, Future<*>> {
        val lane = IccoaLaneServer(port, InetSocketAddress(loopback, 0), loopback,
            connected, disconnected, onFrame, log)
        lanes += lane
        return lane to executor.submit { lane.run() }
    }

    private fun Harness.connect(lane: IccoaLaneServer): Socket =
        Socket(loopback, lane.localPort).also { clients += it }

    private fun failure(future: Future<*>): Throwable =
        assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }.cause!!

    @Test fun lateControlEventsRemainVisibleAfterAudioAndRepeatedMethodsWithoutPayloads() {
        Harness().use { h ->
            val logs = LinkedBlockingQueue<String>()
            val received = CountDownLatch(166)
            val (lane, _) = h.lane(IccoaLaneServer.AUDIO_PORT,
                onFrame = { received.countDown() }, log = { logs.add(it) })
            val stream = h.connect(lane).getOutputStream()
            repeat(20) { stream.write(IccoaControl.frame(4, 9, 0, byteArrayOf(1, 2), it)) }
            repeat(128) { stream.write(IccoaControl.frame(1, 40, 0, "private-value".toByteArray(), it)) }
            repeat(16) { stream.write(IccoaControl.frame(1, 1, 0, byteArrayOf(), it)) }
            stream.write(IccoaControl.frame(1, 25, 0, byteArrayOf(1), 164))
            stream.write(IccoaControl.frame(1, 16, 0, byteArrayOf(), 165))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            val frames = logs.filter { it.contains(" frame ") }
            assertEquals(12, frames.count { it.contains("category=4 method=9 ") })
            assertEquals(9, frames.count { it.contains("category=1 method=40 ") })
            assertEquals(1, frames.count { it.contains("category=1 method=25 ") })
            assertEquals(1, frames.count { it.contains("category=1 method=16 ") })
            assertFalse(frames.any { it.contains("category=1 method=1 ") })
            assertFalse(logs.any { it.contains("private-value") })
        }
    }

    @Test fun auxiliaryEofAllowsReconnectWhileControlStillReceivesAuthenticatedFrames() {
        Harness().use { h ->
            val logs = LinkedBlockingQueue<String>()
            val received = LinkedBlockingQueue<String>()
            val cipher = IccoaControl.Cipher(ByteArray(16) { it.toByte() })
            val (aux, auxWorker) = h.lane(57249, onFrame = {
                received.add("aux:" + cipher.decrypt(it.body).toString(Charsets.UTF_8))
            }, log = { logs.add(it) })
            val (control, controlWorker) = h.lane(IccoaLaneServer.CONTROL_PORT, onFrame = {
                received.add("control:" + cipher.decrypt(it.body).toString(Charsets.UTF_8))
            })
            h.connect(aux).close() // XA60's observed immediate close, before any UCAR frame.
            repeat(3) { assertNotNull(logs.poll(3, TimeUnit.SECONDS)) }
            val essential = h.connect(control)
            essential.getOutputStream().write(IccoaControl.frame(1, 4, 0, cipher.encrypt("ready".toByteArray()), 1))
            assertEquals("control:ready", received.poll(3, TimeUnit.SECONDS))
            val replacement = h.connect(aux)
            replacement.getOutputStream().write(IccoaControl.frame(6, 1, 0, cipher.encrypt("info".toByteArray()), 2))
            assertEquals("aux:info", received.poll(3, TimeUnit.SECONDS))
            replacement.shutdownOutput()
            assertFalse(auxWorker.isDone)
            assertFalse(controlWorker.isDone)
        }
    }

    @Test fun cleanCloseAfterCompleteFrameAlsoAllowsAnotherAuxiliaryConnection() {
        Harness().use { h ->
            val received = LinkedBlockingQueue<Int>()
            val disconnected = LinkedBlockingQueue<Socket>()
            val (lane, worker) = h.lane(IccoaLaneServer.AUDIO_PORT,
                onFrame = { received.add(it.sequence) }, disconnected = { disconnected.add(it) })
            repeat(2) { sequence ->
                h.connect(lane).use {
                    it.getOutputStream().write(IccoaControl.frame(4, 1, 0, byteArrayOf(7), sequence))
                    it.shutdownOutput()
                    assertEquals(sequence, received.poll(3, TimeUnit.SECONDS))
                    assertTrue(disconnected.poll(3, TimeUnit.SECONDS)!!.isClosed)
                }
            }
            assertFalse(worker.isDone)
        }
    }

    @Test fun essentialControlEofRemainsFatalAndClosesAcceptedSocket() {
        Harness().use { h ->
            val accepted = LinkedBlockingQueue<Socket>()
            val (lane, worker) = h.lane(IccoaLaneServer.CONTROL_PORT, connected = { accepted.add(it) })
            h.connect(lane).shutdownOutput()
            assertTrue(failure(worker) is EOFException)
            assertTrue(accepted.poll(3, TimeUnit.SECONDS)!!.isClosed)
        }
    }

    @Test fun partialAuxiliaryHeaderOrBodyIsNotCleanEof() {
        val packet = IccoaControl.frame(1, 4, 0, byteArrayOf(1, 2), 1)
        for (size in listOf(1, 19, 20, packet.size - 1)) Harness().use { h ->
            val (lane, worker) = h.lane(57249)
            h.connect(lane).run {
                getOutputStream().write(packet.copyOf(size))
                shutdownOutput()
            }
            assertTrue("Truncated packet of $size bytes must fail", failure(worker) is EOFException)
        }
    }

    @Test fun auxiliaryCrcFailureIsFatal() {
        Harness().use { h ->
            val (lane, worker) = h.lane(57249)
            val packet = IccoaControl.frame(1, 4, 0, byteArrayOf(1), 1).also { it[4] = 1 }
            h.connect(lane).getOutputStream().write(packet)
            assertEquals(IccoaHandshakeException.Reason.HEADER_CRC,
                (failure(worker) as IccoaHandshakeException).reason)
        }
    }

    @Test fun transportDiagnosticDistinguishesReadFromDispatchFailureWithoutPeerPayload() {
        Harness().use { h ->
            val logs = LinkedBlockingQueue<String>()
            val (lane, worker) = h.lane(57249, onFrame = { throw IllegalArgumentException("private-payload") },
                log = { logs.add(it) })
            h.connect(lane).getOutputStream().write(IccoaControl.frame(6, 1, 0, byteArrayOf(7), 1))
            assertTrue(failure(worker) is IllegalArgumentException)
            val diagnostic = logs.toList().single { it.contains("failed stage=") }
            assertTrue(diagnostic.contains("stage=dispatch frames=1"))
            assertFalse(diagnostic.contains("private-payload"))
        }
        Harness().use { h ->
            val logs = LinkedBlockingQueue<String>()
            val (lane, worker) = h.lane(57249, log = { logs.add(it) })
            h.connect(lane).run {
                getOutputStream().write(byteArrayOf(0))
                shutdownOutput()
            }
            assertTrue(failure(worker) is EOFException)
            val diagnostic = logs.toList().single { it.contains("failed stage=") }
            assertTrue(diagnostic.contains("stage=read-header frames=0"))
            assertTrue(diagnostic.contains("detail=eof"))
        }
    }

    @Test fun encryptedMessageAuthenticationFailureStillEndsAuxiliaryReader() {
        Harness().use { h ->
            val cipher = IccoaControl.Cipher(ByteArray(16))
            val (lane, worker) = h.lane(57249, onFrame = { cipher.decrypt(it.body) })
            val encrypted = cipher.encrypt(byteArrayOf(1)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            h.connect(lane).getOutputStream().write(IccoaControl.frame(6, 1, 0, encrypted, 1))
            assertTrue(failure(worker) is javax.crypto.AEADBadTagException)
        }
    }

    @Test fun stoppingLaneUnblocksAcceptAndReadAndClosesOwnedSockets() {
        Harness().use { h ->
            val (idle, idleWorker) = h.lane(57249)
            idle.close()
            // Stop may happen either before run() or during accept().
            try { idleWorker.get(3, TimeUnit.SECONDS) } catch (e: ExecutionException) {
                assertTrue(e.cause is SocketException)
            }
            val accepted = LinkedBlockingQueue<Socket>()
            val (busy, busyWorker) = h.lane(57239, connected = { accepted.add(it) })
            val client = h.connect(busy).apply { soTimeout = 3000 }
            val owned = accepted.poll(3, TimeUnit.SECONDS)!!
            busy.close()
            try { busyWorker.get(3, TimeUnit.SECONDS) } catch (e: ExecutionException) {
                assertTrue(e.cause is SocketException)
            }
            assertTrue(owned.isClosed)
            assertEquals(-1, client.getInputStream().read())
        }
    }

    @Test fun cleanEofReaderDistinguishesEmptyAndTruncatedInput() {
        assertNull(IccoaControl.readOrNull(ByteArrayInputStream(byteArrayOf())))
        assertThrows(EOFException::class.java) { IccoaControl.read(ByteArrayInputStream(byteArrayOf())) }
        val packet = IccoaControl.frame(1, 4, 0, byteArrayOf(7), 1)
        val input = ByteArrayInputStream(packet)
        assertEquals(4, IccoaControl.readOrNull(input)!!.method)
        assertNull(IccoaControl.readOrNull(input))
        assertThrows(EOFException::class.java) {
            IccoaControl.readOrNull(ByteArrayInputStream(packet.copyOf(1)))
        }
    }
}
