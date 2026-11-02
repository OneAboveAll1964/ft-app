package app.ft.carlife

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WifiChannelLinkTest {
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = WifiChannelLink(ports, onBound = { bound.put(it) })
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
        } finally {
            link.stop()
            clients.forEach { runCatching { it.close() } }
            scope.cancel()
        }
    }
}
