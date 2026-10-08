package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

object AoaFraming {
    const val HEAD = 8
    const val MAX_PACKET = 8 * 1024 * 1024

    fun head(channel: Int, length: Int): ByteArray {
        val h = ByteArray(HEAD)
        Bytes.putU32(channel, h, 0)
        Bytes.putU32(length, h, 4)
        return h
    }

    fun channel(head: ByteArray): Int = Bytes.u32(head, 0)

    fun length(head: ByteArray): Int {
        val n = Bytes.u32(head, 4)
        if (n < 0 || n > MAX_PACKET) throw IllegalStateException("bad usb packet: channel ${channel(head)}, $n bytes")
        return n
    }

    fun known(channel: Int) = channel in CarLifeProtocol.CH_CMD..CarLifeProtocol.CH_CTRL

    class Demux(private val onMessage: LinkMessage) {
        private val streams = HashMap<Int, Growable>()

        fun packet(channel: Int, src: ByteArray, from: Int = 0, length: Int = src.size) {
            val s = streams.getOrPut(channel) { Growable() }
            s.append(src, from, length)
            val hl = CarLifeFraming.headLen(channel)
            var at = 0
            while (s.size - at >= hl) {
                val head = s.data.copyOfRange(at, at + hl)
                val len = CarLifeFraming.bodyLen(channel, head)
                if (len < 0 || len > CarLifeFraming.maxBody(channel)) throw IllegalStateException("bad ${CarLifeProtocol.channelName(channel)} message: $len bytes")
                if (s.size - at - hl < len) break
                val body = s.data.copyOfRange(at + hl, at + hl + len)
                at += hl + len
                onMessage(channel, head, body)
            }
            s.drop(at)
        }
    }

    private class Growable {
        var data = ByteArray(32 * 1024)
        var size = 0

        fun append(src: ByteArray, from: Int, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(size + count, data.size * 2))
            System.arraycopy(src, from, data, size, count)
            size += count
        }

        fun drop(count: Int) {
            if (count <= 0) return
            System.arraycopy(data, count, data, 0, size - count)
            size -= count
        }
    }
}

object UsbCar {
    fun isCarLife(a: android.hardware.usb.UsbAccessory): Boolean = isCarLife(a.manufacturer, a.model)

    fun isCarLife(manufacturer: String?, model: String?): Boolean =
        manufacturer.equals("Baidu", true) && model?.startsWith("CarLife", true) == true

    fun describe(a: android.hardware.usb.UsbAccessory): String =
        listOfNotNull(a.manufacturer, a.model, a.version, a.description).filter { it.isNotBlank() }.joinToString(" ")
}

class AccessoryLink(
    override val name: String,
    private val what: String,
    private val open: () -> Pipe?
) : CarLifeLink {
    class Pipe(val input: InputStream, val output: OutputStream, val close: () -> Unit)

    private val tag = "USB"
    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var pipe: Pipe? = null
    private var reader: Thread? = null
    private var unknownNoted = false

    override val isOpen: Boolean get() = running.get() && pipe != null
    override val connected: Boolean get() = isOpen

    override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit, onConnected: () -> Unit, onClosed: (String) -> Unit) {
        running.set(true)
        closing.set(false)
        reader = Thread({
            val p = runCatching { open() }.onFailure { DiagLog.e(tag, "$name: could not open $what", it) }.getOrNull()
            if (p == null) {
                if (running.get()) onClosed("$what could not be opened")
                return@Thread
            }
            if (!running.get()) {
                runCatching { p.close() }
                return@Thread
            }
            pipe = p
            onEvent("$name connected to $what")
            onConnected()
            val why = read(p, onMessage)
            val mine = closing.compareAndSet(false, true)
            pipe = null
            runCatching { p.close() }
            if (mine && running.get()) {
                onEvent("$name disconnected: $why")
                onClosed(why)
            }
        }, "ft-usb-read").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun read(p: Pipe, onMessage: LinkMessage): String {
        val demux = AoaFraming.Demux(onMessage)
        val head = ByteArray(AoaFraming.HEAD)
        var body = ByteArray(16 * 1024)
        return try {
            while (running.get()) {
                exactly(p.input, head, AoaFraming.HEAD)
                val channel = AoaFraming.channel(head)
                val length = AoaFraming.length(head)
                if (length > body.size) body = ByteArray(length)
                exactly(p.input, body, length)
                if (AoaFraming.known(channel)) {
                    demux.packet(channel, body, 0, length)
                } else if (!unknownNoted) {
                    unknownNoted = true
                    DiagLog.i(tag, "$name: the car sent $length bytes on channel $channel, which CarLife does not use, skipping them")
                }
            }
            "stopped"
        } catch (t: Throwable) {
            if (running.get()) DiagLog.d(tag, "$name read ended: ${t.javaClass.simpleName} ${t.message ?: ""}")
            t.message?.let { "cable link ended ($it)" } ?: "cable link ended"
        }
    }

    private fun exactly(input: InputStream, dst: ByteArray, count: Int) {
        var off = 0
        var ends = 0
        while (off < count) {
            val n = input.read(dst, off, count - off)
            if (n < 0) {
                if (++ends > 3) throw java.io.EOFException("the car closed the cable link")
                continue
            }
            ends = 0
            off += n
        }
    }

    override fun send(channel: Int, inner: ByteArray): Boolean {
        if (pipe == null) return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            OFF_MAIN.execute { write(channel, inner) }
            return true
        }
        return write(channel, inner)
    }

    private fun write(channel: Int, inner: ByteArray): Boolean {
        val p = pipe ?: return false
        synchronized(writeLock) {
            return try {
                p.output.write(AoaFraming.head(channel, inner.size))
                p.output.write(inner)
                p.output.flush()
                true
            } catch (t: Throwable) {
                if (running.get() && !closing.get()) DiagLog.e(tag, "$name ${CarLifeProtocol.channelName(channel)} write failed", t)
                false
            }
        }
    }

    override fun stop() {
        running.set(false)
        closing.set(true)
        val p = pipe
        pipe = null
        p?.let { runCatching { it.close() } }
        reader?.interrupt()
        reader = null
    }

    companion object {
        private val OFF_MAIN = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ft-usb-main-sends").apply { isDaemon = true } }
    }
}
