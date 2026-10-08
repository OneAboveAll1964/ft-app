package app.ft.carlife

import app.ft.core.Bytes
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
            val heard = HashMap<String, Long>()
            fun note(key: String, line: () -> String) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - (heard[key] ?: 0L) < 30_000) return
                heard[key] = now
                DiagLog.d(tag, line())
            }
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(p) }.isFailure) break
                val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
                val from = p.address
                if (!text.contains("carlifehost")) {
                    val shown = text.take(120).replace(Regex("[^\\x20-\\x7E]"), ".")
                    note("other ${from.hostAddress} ${text.take(12)}") { "udp $PORT packet from ${from.hostAddress}:${p.port} (${p.length} bytes) is not a CarLife discovery, ignored: '$shown' ${Bytes.hex(p.data.copyOfRange(p.offset, p.offset + p.length), 48)}" }
                    continue
                }
                if (!allowed(from)) {
                    note("blocked ${from.hostAddress}") { "the car looked for FT from ${from.hostAddress}, but that address is not on the link FT is using, ignored: '${text.take(120)}'" }
                    continue
                }
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
