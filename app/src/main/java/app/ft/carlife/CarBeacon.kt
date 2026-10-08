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

class CarBeacon(
    private val scope: CoroutineScope,
    private val targets: () -> Collection<InetAddress>,
    private val groupOwner: () -> InetAddress? = { null },
    private val hello: () -> String = { "" }
) {
    private val tag = "Beacon"
    private var job: Job? = null
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
        val targets = LinkedHashSet<InetAddress>(targets())
        val owner = groupOwner()
        var ok = 0
        var helloSent = false
        if (targets.isNotEmpty() || owner != null) {
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    for (t in targets) {
                        runCatching { s.send(DatagramPacket(HOST, HOST.size, t, CarLifeProtocol.DISCOVERY_PORT)); ok++ }
                            .onFailure { e -> if (sent == 0) DiagLog.d(tag, "send to ${t.hostAddress} failed: ${e.message}") }
                    }
                    if (owner != null) {
                        val h = hello().toByteArray()
                        if (h.isNotEmpty()) runCatching { s.send(DatagramPacket(h, h.size, owner, HELLO_PORT)); helloSent = true }
                            .onFailure { e -> if (sent == 0) DiagLog.d(tag, "hello to ${owner.hostAddress} failed: ${e.message}") }
                    }
                }
            }.onFailure { e -> DiagLog.w(tag, "beacon socket failed: ${e.message}") }
        }
        sent++
        if (sent == 1 || sent == 4 || sent % 40 == 0) {
            DiagLog.i(tag, "discovery beacon #$sent to ${targets.joinToString { it.hostAddress ?: "?" }.ifBlank { "no network" }} udp ${CarLifeProtocol.DISCOVERY_PORT} ($ok sent)" +
                if (helloSent) ", hello to the car at ${owner?.hostAddress} udp $HELLO_PORT" else "")
        }
    }

    companion object {
        const val HELLO_PORT = 8999
        private val HOST = "CarlifeHost".toByteArray()
    }
}
