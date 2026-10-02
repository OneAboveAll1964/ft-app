package app.ft.carlife

import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class CarDiscovery(private val scope: CoroutineScope, private val allowed: (InetAddress) -> Boolean) {
    private val tag = "Beacon"
    private var job: Job? = null
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val s = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            }.getOrElse {
                DiagLog.w(tag, "could not listen for car discovery on udp $PORT: ${it.message}")
                return@launch
            }
            socket = s
            val buf = ByteArray(1024)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(p) }.isFailure) break
                val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
                if (!text.contains("carlifehost")) continue
                val from = p.address
                if (!allowed(from)) continue
                runCatching { s.send(DatagramPacket(READY, READY.size, from, p.port)) }
                    .onSuccess { DiagLog.i(tag, "the car looked for FT from ${from.hostAddress}, answered") }
                    .onFailure { DiagLog.w(tag, "could not answer the car at ${from.hostAddress}: ${it.message}") }
            }
            runCatching { s.close() }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
    }

    companion object {
        const val PORT = 8999
        private val READY = "{\"carlifehost\":\"carlife\",\"status\":\"ready\"}".toByteArray()
    }
}
