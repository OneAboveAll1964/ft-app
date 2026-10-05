package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private var cmdGeneration = 0
    private var closedHook: ((String) -> Unit)? = null
    private var watch: Job? = null

    override val isOpen: Boolean get() = running.get() && synchronized(sockets) { sockets.isNotEmpty() }
    override val connected: Boolean get() = running.get() && synchronized(sockets) { sockets.containsKey(CarLifeProtocol.CH_CMD) }

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onConnected: () -> Unit, onClosed: (String) -> Unit) {
        running.set(true)
        closedHook = onClosed
        for ((channel, port) in ports) {
            locks[channel] = Any()
            jobs += scope.launch(Dispatchers.IO) {
                try {
                    val ss = NetUtil.listen(port) { running.get() } ?: return@launch
                    synchronized(servers) { servers[channel] = ss }
                    if (!running.get()) {
                        runCatching { ss.close() }
                        return@launch
                    }
                    onEvent("WIFI ${CarLifeProtocol.channelName(channel)} listening on $port")
                    while (isActive && running.get()) {
                        val s = ss.accept()
                        if (refuse(s) != null) {
                            runCatching { s.close() }
                            continue
                        }
                        tune(s, channel)
                        val stale = synchronized(sockets) { sockets[channel] }
                        if (stale != null) {
                            DiagLog.w(tag, "WIFI ${CarLifeProtocol.channelName(channel)} connected again while the old one was still open, starting the connection over")
                            dropAll()
                            onClosed("head unit connected again")
                        }
                        val generation = synchronized(sockets) {
                            sockets[channel] = s
                            if (channel == CarLifeProtocol.CH_CMD) cmdGeneration++
                            cmdGeneration
                        }
                        onEvent("WIFI ${CarLifeProtocol.channelName(channel)} connected from ${s.inetAddress.hostAddress}")
                        if (channel == CarLifeProtocol.CH_CMD) {
                            onConnected()
                            watchCar(scope, s)
                        }
                        scope.launch(Dispatchers.IO) {
                            readLoop(channel, s, onMessage)
                            val current = synchronized(sockets) {
                                val mine = sockets[channel] === s
                                if (mine) sockets.remove(channel)
                                mine && (channel != CarLifeProtocol.CH_CMD || generation == cmdGeneration)
                            }
                            if (!current) return@launch
                            onEvent("WIFI ${CarLifeProtocol.channelName(channel)} disconnected")
                            if (channel == CarLifeProtocol.CH_CMD) {
                                watch?.cancel()
                                onClosed("head unit disconnected")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) DiagLog.w(tag, "WIFI ${CarLifeProtocol.channelName(channel)} server ended: ${t.message}")
                }
            }
        }
    }

    private fun tune(s: Socket, channel: Int) {
        runCatching { s.tcpNoDelay = true }
        runCatching { s.keepAlive = true }
        runCatching { s.setSoLinger(true, 0) }
        runCatching { s.receiveBufferSize = BUFFER }
        runCatching {
            s.sendBufferSize = when (channel) {
                CarLifeProtocol.CH_TTS -> VOICE_BUFFER
                CarLifeProtocol.CH_MEDIA -> MUSIC_BUFFER
                else -> BUFFER
            }
        }
        trafficClass(channel)?.let { tc -> runCatching { s.trafficClass = tc } }
    }

    private fun trafficClass(channel: Int): Int? = when (channel) {
        CarLifeProtocol.CH_MEDIA, CarLifeProtocol.CH_TTS -> TOS_VOICE
        CarLifeProtocol.CH_VIDEO -> TOS_VIDEO
        else -> null
    }

    private fun dropAll() {
        val all = synchronized(sockets) {
            val list = sockets.values.toList()
            sockets.clear()
            cmdGeneration++
            list
        }
        watch?.cancel()
        all.forEach { runCatching { it.close() } }
    }

    private fun watchCar(scope: CoroutineScope, s: Socket) {
        watch?.cancel()
        watch = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive && running.get() && !s.isClosed) {
                delay(REACH_EVERY_MS)
                if (s.isClosed || synchronized(sockets) { sockets[CarLifeProtocol.CH_CMD] !== s }) break
                val reachable = runCatching { s.inetAddress.isReachable(REACH_TIMEOUT_MS) }.getOrDefault(true)
                if (reachable) {
                    misses = 0
                    continue
                }
                misses++
                DiagLog.w(tag, "the head unit did not answer a ping ($misses)")
                if (misses < 2) {
                    delay(REACH_RETRY_MS)
                    val again = runCatching { s.inetAddress.isReachable(REACH_TIMEOUT_MS) }.getOrDefault(true)
                    if (again) {
                        misses = 0
                        continue
                    }
                    misses++
                }
                DiagLog.w(tag, "the head unit is gone, closing the connection")
                dropAll()
                closedHook?.invoke("head unit stopped answering")
                break
            }
        }
    }

    private fun readLoop(channel: Int, s: Socket, onMessage: LinkMessage) {
        try {
            val i = java.io.BufferedInputStream(s.getInputStream(), 32 * 1024)
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
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (synchronized(sockets) { sockets[channel] } == null) return false
            OFF_MAIN.execute { write(channel, inner) }
            return true
        }
        return write(channel, inner)
    }

    private fun write(channel: Int, inner: ByteArray): Boolean {
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
        watch?.cancel()
        synchronized(sockets) { sockets.values.forEach { runCatching { it.close() } }; sockets.clear() }
        synchronized(servers) { servers.values.forEach { runCatching { it.close() } }; servers.clear() }
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    companion object {
        private const val BUFFER = 327_680
        private val OFF_MAIN = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ft-link-main-sends").apply { isDaemon = true } }
        private const val VOICE_BUFFER = 8 * 1024
        private const val MUSIC_BUFFER = 64 * 1024
        private const val TOS_VOICE = 0xB8
        private const val TOS_VIDEO = 0x88
        private const val REACH_EVERY_MS = 180_000L
        private const val REACH_RETRY_MS = 10_000L
        private const val REACH_TIMEOUT_MS = 5000
    }
}

object NetUtil {
    suspend fun listen(port: Int, alive: () -> Boolean): ServerSocket? {
        var tries = 0
        while (alive()) {
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                return ss
            } catch (e: BindException) {
                runCatching { ss.close() }
                if (++tries >= 40) throw e
                if (tries == 1) DiagLog.d("Link", "port $port is still in use, trying again")
                delay(250)
            }
        }
        return null
    }

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

    fun p2pGroupUp(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && it.name.startsWith("p2p-") }
    } catch (_: Throwable) {
        false
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
