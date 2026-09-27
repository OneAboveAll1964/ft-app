package app.ft.carlife

import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class CarBeacon(private val scope: CoroutineScope, private val name: () -> String) {
    private val tag = "Beacon"
    private var job: Job? = null
    @Volatile var target: String? = null
    @Volatile var onlyTarget = false
    private var sent = 0

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        sent = 0
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                tick()
                delay(1500)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun tick() {
        run {
            val targets = LinkedHashSet<InetAddress>()
            target?.let { t -> runCatching { InetAddress.getByName(t) }.getOrNull()?.let(targets::add) }
            if (!onlyTarget) targets += NetUtil.broadcastAddresses()
            val payload = name().toByteArray()
            var ok = 0
            if (targets.isNotEmpty()) {
                runCatching {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        for (t in targets) {
                            runCatching { s.send(DatagramPacket(payload, payload.size, t, CarLifeProtocol.DISCOVERY_PORT)); ok++ }
                                .onFailure { e -> if (sent == 0) DiagLog.d(tag, "send to ${t.hostAddress} failed: ${e.message}") }
                        }
                    }
                }.onFailure { e -> DiagLog.w(tag, "beacon socket failed: ${e.message}") }
            }
            sent++
            if (sent == 1 || sent == 4 || sent % 40 == 0) {
                DiagLog.i(tag, "discovery beacon #$sent to ${targets.joinToString { it.hostAddress ?: "?" }.ifBlank { "no network" }} udp ${CarLifeProtocol.DISCOVERY_PORT} ($ok sent)")
            }
        }
    }
}
