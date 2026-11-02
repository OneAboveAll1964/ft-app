package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class IccoaHeartbeatTest {
    @Test fun controlAcceptWritesEncryptedHeartbeatBeforeAnyScheduledCallback() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val scheduled = LinkedBlockingQueue<Pair<Long, () -> Unit>>()
        val cipher = IccoaControl.Cipher(ByteArray(16) { it.toByte() })
        val executor = Executors.newSingleThreadExecutor()
        var heartbeat: IccoaHeartbeat? = null
        val lane = IccoaLaneServer(IccoaLaneServer.CONTROL_PORT, InetSocketAddress(loopback, 0), loopback,
            onConnected = { socket ->
                heartbeat = IccoaHeartbeat(send = { body ->
                    socket.getOutputStream().write(IccoaControl.frame(1, 1, 0, cipher.encrypt(body), 1000, 1))
                    true
                }, schedule = { delay, action -> scheduled.add(delay to action) })
                heartbeat!!.start()
            }, onDisconnected = {}, onFrame = {}, log = {})
        val worker = executor.submit { lane.run() }
        try {
            val before = System.currentTimeMillis()
            Socket(loopback, lane.localPort).use { client ->
                client.soTimeout = 3000
                val frame = IccoaControl.read(client.getInputStream())
                assertEquals(1, frame.category)
                assertEquals(1, frame.method)
                assertEquals(1, frame.format)
                val timestamp = IccoaControl.Fields(cipher.decrypt(frame.body)).number(1)
                assertTrue(timestamp >= before && timestamp <= System.currentTimeMillis())
                val next = scheduled.poll(3, TimeUnit.SECONDS)!!
                assertEquals(2000L, next.first)
                heartbeat!!.start() // Starting twice must not duplicate timers or writes.
                assertTrue(scheduled.isEmpty())
                next.second()
                assertEquals(1, IccoaControl.read(client.getInputStream()).method)
                assertEquals(2000L, scheduled.poll(3, TimeUnit.SECONDS)!!.first)
            }
        } finally {
            heartbeat?.close()
            lane.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(worker.isDone)
        }
    }

    @Test fun closedAttemptCannotSendAgainEvenIfItsTimerRunsDuringRetry() {
        val oldTimers = mutableListOf<() -> Unit>()
        var oldSends = 0
        val old = IccoaHeartbeat({ oldSends++; true }, { _, action -> oldTimers.add(action) })
        old.start()
        old.close()
        var newSends = 0
        val replacement = IccoaHeartbeat({ newSends++; true }, { _, _ -> })
        replacement.start()
        oldTimers.single()()
        old.start()
        assertEquals(1, oldSends)
        assertEquals(1, newSends)
        replacement.close()
    }

    @Test fun failedEnqueueStopsHeartbeatWithoutSchedulingOrRestarting() {
        var sends = 0
        val heartbeat = IccoaHeartbeat({ sends++; false }, { _, _ -> fail("Failed send must not schedule") })
        heartbeat.start()
        heartbeat.start()
        assertEquals(1, sends)
        heartbeat.close()
    }
}
