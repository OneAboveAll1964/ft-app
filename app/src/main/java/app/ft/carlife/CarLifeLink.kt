package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

typealias LinkMessage = (channel: Int, head: ByteArray, body: ByteArray) -> Unit

interface CarLifeLink {
    val name: String
    val isOpen: Boolean
    val connected: Boolean
    fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onConnected: () -> Unit, onClosed: (String) -> Unit)
    fun send(channel: Int, inner: ByteArray): Boolean
    fun stop()
}

class WifiChannelLink(
    private val ports: Map<Int, Int>,
    private val refuse: (Socket) -> String? = { null }
) : CarLifeLink {
    override val name = "WiFi"
    private val tag = "Link"
    private val running = AtomicBoolean(false)
    private val servers = HashMap<Int, ServerSocket>()
    private val sockets = HashMap<Int, Socket>()
    private val locks = HashMap<Int, Any>()
    private val jobs = ArrayList<Job>()

    override val isOpen: Boolean get() = running.get() && synchronized(sockets) { sockets.isNotEmpty() }
    override val connected: Boolean get() = running.get() && synchronized(sockets) { sockets.containsKey(CarLifeProtocol.CH_CMD) }

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onConnected: () -> Unit, onClosed: (String) -> Unit) {
        running.set(true)
        for ((channel, port) in ports) {
            locks[channel] = Any()
            jobs += scope.launch(Dispatchers.IO) {
                try {
                    val ss = ServerSocket(port).also { it.reuseAddress = true }
                    synchronized(servers) { servers[channel] = ss }
                    onEvent("WIFI ${CarLifeProtocol.channelName(channel)} listening on $port")
                    while (isActive && running.get()) {
                        val s = ss.accept()
                        if (refuse(s) != null) {
                            runCatching { s.close() }
                            continue
                        }
                        s.tcpNoDelay = true
                        sendBuffer(channel)?.let { size -> runCatching { s.sendBufferSize = size } }
                        synchronized(sockets) {
                            sockets[channel]?.let { runCatching { it.close() } }
                            sockets[channel] = s
                        }
                        onEvent("WIFI ${CarLifeProtocol.channelName(channel)} connected from ${s.inetAddress.hostAddress}")
                        if (channel == CarLifeProtocol.CH_CMD) onConnected()
                        readLoop(channel, s, onMessage)
                        synchronized(sockets) { if (sockets[channel] === s) sockets.remove(channel) }
                        onEvent("WIFI ${CarLifeProtocol.channelName(channel)} disconnected")
                        if (channel == CarLifeProtocol.CH_CMD) onClosed("head unit disconnected")
                    }
                } catch (t: Throwable) {
                    if (running.get()) DiagLog.w(tag, "WIFI ${CarLifeProtocol.channelName(channel)} server ended: ${t.message}")
                }
            }
        }
    }

    private fun sendBuffer(channel: Int): Int? = if (channel == CarLifeProtocol.CH_TTS) 8 * 1024 else null

    private fun readLoop(channel: Int, s: Socket, onMessage: LinkMessage) {
        try {
            val i = s.getInputStream()
            val hl = CarLifeFraming.headLen(channel)
            while (running.get() && !s.isClosed) {
                val head = ByteArray(hl)
                Bytes.readFully(i, head)
                val len = CarLifeFraming.bodyLen(channel, head)
                if (len < 0 || len > CarLifeFraming.maxBody(channel)) throw IllegalStateException("bad body length $len")
                val body = ByteArray(len)
                Bytes.readFully(i, body)
                onMessage(channel, head, body)
            }
        } catch (t: Throwable) {
            if (running.get()) DiagLog.d(tag, "WIFI ${CarLifeProtocol.channelName(channel)} read ended: ${t.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    override fun send(channel: Int, inner: ByteArray): Boolean {
        val s = synchronized(sockets) { sockets[channel] } ?: return false
        val lock = locks[channel] ?: Any()
        synchronized(lock) {
            return try {
                val o = s.getOutputStream()
                o.write(inner)
                o.flush()
                true
            } catch (t: Throwable) {
                DiagLog.e(tag, "WIFI ${CarLifeProtocol.channelName(channel)} write failed", t)
                runCatching { s.close() }
                false
            }
        }
    }

    override fun stop() {
        running.set(false)
        synchronized(sockets) { sockets.values.forEach { runCatching { it.close() } }; sockets.clear() }
        synchronized(servers) { servers.values.forEach { runCatching { it.close() } }; servers.clear() }
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

object NetUtil {
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    fun wifiDirectIpv4(iface: String?): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && (it.name == iface || it.name.startsWith("p2p")) }
            .sortedByDescending { it.name == iface }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    fun hotspotIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { n -> n.isUp && listOf("swlan", "ap", "softap").any { n.name.startsWith(it) } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    fun broadcastAddresses(include: (String) -> Boolean): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && include(it.name) }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .filter { it is Inet4Address }
            .distinct()
    } catch (_: Throwable) {
        emptyList()
    }

    fun interfaceFor(remote: InetAddress): String? = try {
        if (remote.isLoopbackAddress) "lo"
        else NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .firstOrNull { n -> n.interfaceAddresses.any { a -> sameSubnet(a.address, remote, a.networkPrefixLength.toInt()) } }
            ?.name
    } catch (_: Throwable) {
        null
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        var bits = prefix.coerceAtMost(x.size * 8)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }
}
