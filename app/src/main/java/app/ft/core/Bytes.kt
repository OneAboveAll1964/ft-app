package app.ft.core

import java.io.EOFException
import java.io.InputStream

object Bytes {
    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    fun u32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    fun putU16(v: Int, b: ByteArray, off: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putU32(v: Int, b: ByteArray, off: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun putU64(v: Long, b: ByteArray, off: Int) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun hex(b: ByteArray, max: Int = 32): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder(n * 3 + 12)
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            if (i < n - 1) sb.append(' ')
        }
        if (b.size > max) sb.append(" …(").append(b.size).append(')')
        return sb.toString()
    }

    fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }
}
