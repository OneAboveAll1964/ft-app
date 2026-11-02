package app.ft.iccoa

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/** H.264/AAC -> MPEG-TS (ISO 13818-1) -> RTP PT 33, framed using RFC 4571. */
internal class IccoaTsMuxer(private val output: OutputStream) {
    private val continuity = IntArray(8192)
    private var sequence = SecureRandom().nextInt(65536)
    private val ssrc = SecureRandom().nextInt()
    private var config = byteArrayOf()
    private var tablesAt = Long.MIN_VALUE
    private val rtpPacket = ByteArray(14 + 7 * 188)
    private val rtpHeader = ByteBuffer.wrap(rtpPacket)
    private val tsPacket = ByteArray(188)
    private val pesHeader = byteArrayOf(0, 0, 1, 0, 0, 0, 0x80.toByte(), 0x80.toByte(), 5, 0, 0, 0, 0, 0)
    private val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, 0, 0, 0, 0xfc.toByte())
    private val empty = byteArrayOf()
    private var packetCount = 0
    var writtenRtpPackets = 0L
        private set
    private var timestamp = 0L

    fun configure(bytes: ByteArray) { config = annexB(bytes) }

    fun video(bytes: ByteArray, key: Boolean, ptsUs: Long) {
        val pts = ptsUs * 90 / 1000 and 0x1ffffffffL
        val accessUnit = annexB(bytes)
        val prefix = if (key && !hasNal(accessUnit, 7)) config else empty
        begin(pts)
        if (tablesAt == Long.MIN_VALUE || pts < tablesAt || pts - tablesAt >= 90000) {
            append(psi(0, section(byteArrayOf(0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0))))
            append(psi(4096, section(byteArrayOf(2, 0xb0.toByte(), 23, 0, 1, 0xc1.toByte(), 0, 0,
                0xe1.toByte(), 0, 0xf0.toByte(), 0, 27, 0xe1.toByte(), 0, 0xf0.toByte(), 0,
                15, 0xe1.toByte(), 1, 0xf0.toByte(), 0))))
            tablesAt = pts
        }
        ts(256, 0xe0, prefix, accessUnit, pts, key)
        finish()
    }

    fun audio(aac: ByteArray, ptsUs: Long) {
        require(aac.size in 1..8184)
        val size = aac.size + 7
        // AAC-LC, 48 kHz, stereo; matches wfd_audio_codecs AAC 00000001.
        adts[3] = (0x80 or (size ushr 11)).toByte()
        adts[4] = (size ushr 3).toByte()
        adts[5] = (((size and 7) shl 5) or 31).toByte()
        val pts = ptsUs * 90 / 1000 and 0x1ffffffffL
        begin(pts)
        ts(257, 0xc0, adts, aac, pts)
        finish()
    }

    private fun pes(id: Int, size: Int, pts: Long) {
        val length = if (id == 0xe0 && size + 8 > 65535) 0 else size + 8
        pesHeader[3] = id.toByte()
        pesHeader[4] = (length ushr 8).toByte()
        pesHeader[5] = length.toByte()
        pesHeader[9] = (0x21 or (((pts ushr 30).toInt() and 7) shl 1)).toByte()
        pesHeader[10] = (pts ushr 22).toByte()
        pesHeader[11] = ((((pts ushr 15).toInt() and 127) shl 1) or 1).toByte()
        pesHeader[12] = (pts ushr 7).toByte()
        pesHeader[13] = (((pts.toInt() and 127) shl 1) or 1).toByte()
    }

    // One writer owns these reusable headers. Copy the three segments directly
    // into TS packets instead of allocating an entire prefixed access unit/PES.
    private fun ts(pid: Int, id: Int, prefix: ByteArray, data: ByteArray, pts: Long, key: Boolean = false) {
        pes(id, prefix.size + data.size, pts)
        val size = pesHeader.size + prefix.size + data.size
        var offset = 0
        while (offset < size) {
            val first = offset == 0
            val pcr = first && id == 0xe0
            val payload = minOf(size - offset, if (pcr) 176 else 184)
            val adaptation = 184 - payload
            val packet = tsPacket
            packet.fill(0xff.toByte())
            packet[0] = 0x47
            packet[1] = ((pid ushr 8) or if (first) 0x40 else 0).toByte()
            packet[2] = pid.toByte()
            packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or next(pid)).toByte()
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) packet[5] = (if (pcr) 0x10 or (if (key) 0x40 else 0) else 0).toByte()
                if (pcr) {
                    val clock = pts
                    packet[6] = (clock ushr 25).toByte(); packet[7] = (clock ushr 17).toByte()
                    packet[8] = (clock ushr 9).toByte(); packet[9] = (clock ushr 1).toByte()
                    packet[10] = (((clock and 1).toInt() shl 7) or 0x7e).toByte(); packet[11] = 0
                }
            }
            var copied = 0
            val target = 4 + adaptation
            if (offset < pesHeader.size) {
                val count = minOf(payload, pesHeader.size - offset)
                pesHeader.copyInto(packet, target, offset, offset + count)
                copied += count
            }
            val prefixAt = offset + copied - pesHeader.size
            if (copied < payload && prefixAt < prefix.size) {
                val count = minOf(payload - copied, prefix.size - prefixAt)
                prefix.copyInto(packet, target + copied, prefixAt, prefixAt + count)
                copied += count
            }
            if (copied < payload) {
                val dataAt = offset + copied - pesHeader.size - prefix.size
                data.copyInto(packet, target + copied, dataAt, dataAt + payload - copied)
            }
            append(packet)
            offset += payload
        }
    }

    private fun psi(pid: Int, section: ByteArray) = ByteArray(188) { 0xff.toByte() }.apply {
        this[0] = 0x47; this[1] = ((pid ushr 8) or 0x40).toByte(); this[2] = pid.toByte()
        this[3] = (0x10 or next(pid)).toByte(); this[4] = 0
        section.copyInto(this, 5)
    }

    private fun next(pid: Int): Int = (continuity[pid]++ and 15)

    private fun begin(pts: Long) {
        check(packetCount == 0)
        timestamp = pts
    }

    // Retain a full batch until the next TS packet so an exact multiple of seven
    // still gets the RTP marker on its last packet. No per-TS allocations/list.
    private fun append(packet: ByteArray) {
        if (packetCount == 7) flushPacket(false)
        packet.copyInto(rtpPacket, 14 + packetCount * 188)
        packetCount++
    }

    private fun flushPacket(last: Boolean) {
        if (packetCount == 0) return
        val size = 12 + packetCount * 188
        rtpHeader.clear()
        rtpHeader.putShort(size.toShort()).put(0x80.toByte())
            .put((33 or if (last) 0x80 else 0).toByte())
            .putShort((sequence++).toShort()).putInt(timestamp.toInt()).putInt(ssrc)
        output.write(rtpPacket, 0, size + 2)
        writtenRtpPackets++
        packetCount = 0
    }

    private fun finish() {
        flushPacket(true)
        output.flush()
    }

    companion object {
        internal fun section(data: ByteArray): ByteArray {
            var crc = -1
            data.forEach { byte ->
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            return data + ByteBuffer.allocate(4).putInt(crc).array()
        }

        internal fun annexB(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            if (startCode(data, 0) != 0) return data
            val out = ByteArrayOutputStream()
            var offset = 0
            while (offset + 4 <= data.size) {
                val size = ByteBuffer.wrap(data, offset, 4).int
                if (size <= 0 || size > data.size - offset - 4) break
                out.write(byteArrayOf(0, 0, 0, 1)); out.write(data, offset + 4, size)
                offset += size + 4
            }
            return if (offset == data.size) out.toByteArray() else byteArrayOf(0, 0, 0, 1) + data
        }

        private fun startCode(data: ByteArray, at: Int): Int = when {
            at + 3 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() && data[at + 2] == 1.toByte() -> 3
            at + 4 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte() -> 4
            else -> 0
        }
        private fun hasNal(bytes: ByteArray, type: Int): Boolean {
            var at = 0
            while (at < bytes.size) {
                val n = startCode(bytes, at)
                if (n != 0 && at + n < bytes.size) {
                    val nalType = bytes[at + n].toInt() and 31
                    if (nalType == type) return true
                    // Parameter sets must precede slices; do not scan a large IDR.
                    // No fixed byte limit: AUD/SEI before the SPS may be long.
                    if (nalType in 1..5) return false
                    at += n + 1
                } else at++
            }
            return false
        }
    }
}
