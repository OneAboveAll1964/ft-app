package app.ft.aa

import app.ft.core.Bytes
import java.io.ByteArrayOutputStream
import java.io.InputStream

object AaFraming {
    class Frame(val channel: Int, val flags: Int, val payload: ByteArray) {
        val frameType get() = flags and 3
        val encrypted get() = flags and AaProtocol.FLAG_ENCRYPTED != 0
        val control get() = flags and AaProtocol.FLAG_CONTROL != 0
    }

    class Message(val channel: Int, val control: Boolean, val encrypted: Boolean, val payload: ByteArray) {
        val id: Int get() = if (payload.size >= 2) Bytes.u16(payload, 0) else -1
        val body: ByteArray get() = if (payload.size >= 2) payload.copyOfRange(2, payload.size) else ByteArray(0)
    }

    fun encode(channel: Int, payload: ByteArray, encrypted: Boolean, control: Boolean, transform: (ByteArray) -> ByteArray): List<ByteArray> {
        val base = (if (encrypted) AaProtocol.FLAG_ENCRYPTED else 0) or (if (control) AaProtocol.FLAG_CONTROL else 0)
        val out = ArrayList<ByteArray>()
        if (payload.size < AaProtocol.MAX_FRAME_PAYLOAD) {
            out += frame(channel, base or AaProtocol.FRAME_BULK, transform(payload), -1)
            return out
        }
        var offset = 0
        var remaining = payload.size
        while (remaining > 0) {
            val n = minOf(remaining, AaProtocol.MAX_FRAME_PAYLOAD)
            val chunk = payload.copyOfRange(offset, offset + n)
            val type = when {
                offset == 0 -> AaProtocol.FRAME_FIRST
                remaining - n > 0 -> AaProtocol.FRAME_MIDDLE
                else -> AaProtocol.FRAME_LAST
            }
            out += frame(channel, base or type, transform(chunk), if (type == AaProtocol.FRAME_FIRST) payload.size else -1)
            offset += n
            remaining -= n
        }
        return out
    }

    private fun frame(channel: Int, flags: Int, data: ByteArray, total: Int): ByteArray {
        val ext = total >= 0
        val head = if (ext) 8 else 4
        val f = ByteArray(head + data.size)
        f[0] = channel.toByte()
        f[1] = flags.toByte()
        Bytes.putU16(data.size, f, 2)
        if (ext) Bytes.putU32(total, f, 4)
        System.arraycopy(data, 0, f, head, data.size)
        return f
    }

    fun readFrame(input: InputStream): Frame {
        val h = ByteArray(2)
        Bytes.readFully(input, h)
        val channel = h[0].toInt() and 0xff
        val flags = h[1].toInt() and 0xff
        val sz = ByteArray(2)
        Bytes.readFully(input, sz)
        val size = Bytes.u16(sz, 0)
        if (flags and 3 == AaProtocol.FRAME_FIRST) {
            val t = ByteArray(4)
            Bytes.readFully(input, t)
        }
        val payload = ByteArray(size)
        Bytes.readFully(input, payload)
        return Frame(channel, flags, payload)
    }

    class Assembler(private val decrypt: (ByteArray) -> ByteArray) {
        private val buffers = HashMap<Int, ByteArrayOutputStream>()

        fun feed(f: Frame): Message? {
            val chunk = if (f.encrypted) decrypt(f.payload) else f.payload
            val type = f.frameType
            if (type == AaProtocol.FRAME_BULK) {
                buffers.remove(f.channel)
                return Message(f.channel, f.control, f.encrypted, chunk)
            }
            val buf = if (type == AaProtocol.FRAME_FIRST) ByteArrayOutputStream().also { buffers[f.channel] = it }
            else buffers.getOrPut(f.channel) { ByteArrayOutputStream() }
            buf.write(chunk)
            if (type == AaProtocol.FRAME_LAST) {
                buffers.remove(f.channel)
                return Message(f.channel, f.control, f.encrypted, buf.toByteArray())
            }
            return null
        }
    }
}
