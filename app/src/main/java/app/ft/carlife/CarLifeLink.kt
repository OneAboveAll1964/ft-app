package app.ft.carlife

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import app.ft.core.Bytes
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onClosed: (String) -> Unit)
    fun send(channel: Int, inner: ByteArray): Boolean
    fun stop()
}

open class MuxStreamLink(override val name: String) : CarLifeLink {
    private val tag = "Link"
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    private var job: Job? = null
    private var closer: (() -> Unit)? = null

    override val isOpen: Boolean get() = running.get()

    fun attach(input: InputStream, output: OutputStream, closer: () -> Unit) {
        this.input = input
        this.output = output
        this.closer = closer
    }

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onClosed: (String) -> Unit) {
        val i = input ?: return onClosed("no stream")
        running.set(true)
        onEvent("$name link open")
        job = scope.launch(Dispatchers.IO) {
            try {
                val outer = ByteArray(CarLifeProtocol.USB_OUTER)
                while (isActive && running.get()) {
                    Bytes.readFully(i, outer)
                    val channel = outer[3].toInt() and 0xff
                    val innerLen = Bytes.u32(outer, 4)
                    if (innerLen < 0 || innerLen > CarLifeProtocol.MAX_STREAM_BODY + 16) throw IllegalStateException("bad outer length $innerLen")
                    val inner = ByteArray(innerLen)
                    Bytes.readFully(i, inner)
                    val split = CarLifeFraming.splitInner(channel, inner)
                    if (split == null) {
                        DiagLog.w(tag, "short inner on ${CarLifeProtocol.channelName(channel)} len=$innerLen")
                        continue
                    }
                    onMessage(channel, split.first, split.second)
                }
            } catch (t: Throwable) {
                if (running.get()) DiagLog.w(tag, "$name read loop ended: ${t.message}")
            } finally {
                val was = running.getAndSet(false)
                runCatching { closer?.invoke() }
                if (was) onClosed("$name closed")
            }
        }
    }

    override fun send(channel: Int, inner: ByteArray): Boolean {
        val o = output ?: return false
        if (!running.get()) return false
        val packet = CarLifeFraming.usbPacket(channel, inner)
        synchronized(writeLock) {
            return try {
                o.write(packet)
                o.flush()
                true
            } catch (t: Throwable) {
                DiagLog.e(tag, "$name write failed", t)
                stop()
                false
            }
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { closer?.invoke() }
        job?.cancel()
    }
}

class UsbAoaLink(private val usbManager: UsbManager, private val accessory: UsbAccessory) : MuxStreamLink("USB") {
    private var pfd: ParcelFileDescriptor? = null

    fun open(): Boolean {
        val fd = runCatching { usbManager.openAccessory(accessory) }.getOrNull() ?: return false
        pfd = fd
        attach(FileInputStream(fd.fileDescriptor), FileOutputStream(fd.fileDescriptor)) { runCatching { fd.close() } }
        return true
    }
}

class DebugMuxTcpLink(private val port: Int) : MuxStreamLink("USB-SIM") {
    private var server: ServerSocket? = null
    private var socket: Socket? = null

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onClosed: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port).also { it.reuseAddress = true }
                server = ss
                onEvent("USB-SIM listening on $port")
                val s = ss.accept()
                s.tcpNoDelay = true
                socket = s
                attach(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() } }
                super.start(scope, onMessage, onEvent, onClosed)
            } catch (t: Throwable) {
                onClosed("USB-SIM accept failed: ${t.message}")
            }
        }
    }

    override fun stop() {
        super.stop()
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }
}

class WifiChannelLink(private val ports: Map<Int, Int>) : CarLifeLink {
    override val name = "WiFi"
    private val tag = "Link"
    private val running = AtomicBoolean(false)
    private val servers = HashMap<Int, ServerSocket>()
    private val sockets = HashMap<Int, Socket>()
    private val locks = HashMap<Int, Any>()
    private val jobs = ArrayList<Job>()

    override val isOpen: Boolean get() = running.get() && synchronized(sockets) { sockets.isNotEmpty() }

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onClosed: (String) -> Unit) {
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
                        s.tcpNoDelay = true
                        synchronized(sockets) {
                            sockets[channel]?.let { runCatching { it.close() } }
                            sockets[channel] = s
                        }
                        onEvent("WIFI ${CarLifeProtocol.channelName(channel)} connected from ${s.inetAddress.hostAddress}")
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

    fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .filter { it is Inet4Address }
            .distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}
