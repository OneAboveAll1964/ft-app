package app.ft.core

import java.io.ByteArrayOutputStream

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        writeVarint(value)
        return this
    }

    fun int32(field: Int, value: Int) = varint(field, value.toLong() and 0xffffffffL)
    fun int64(field: Int, value: Long) = varint(field, value)
    fun uint32(field: Int, value: Int) = varint(field, value.toLong() and 0xffffffffL)
    fun uint64(field: Int, value: Long) = varint(field, value)
    fun sint32(field: Int, value: Int) = varint(field, ((value shl 1) xor (value shr 31)).toLong() and 0xffffffffL)
    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)
    fun enum(field: Int, value: Int) = varint(field, value.toLong())
    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun message(field: Int, value: ProtoWriter) = bytes(field, value.toByteArray())

    fun fixed64(field: Int, value: Long): ProtoWriter {
        tag(field, 1)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
        return this
    }

    fun fixed32(field: Int, value: Int): ProtoWriter {
        tag(field, 5)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xff)
        return this
    }

    fun packedInts(field: Int, values: List<Int>): ProtoWriter {
        val tmp = ByteArrayOutputStream()
        for (v in values) {
            var x = v.toLong() and 0xffffffffL
            while (x and 0x7fL.inv() != 0L) {
                tmp.write(((x and 0x7fL) or 0x80L).toInt())
                x = x ushr 7
            }
            tmp.write(x.toInt())
        }
        return bytes(field, tmp.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeVarint((field.toLong() shl 3) or wire.toLong())

    private fun writeVarint(v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7fL) or 0x80L).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }
}

class ProtoReader(private val data: ByteArray) {
    val fields: Map<Int, List<Any>>

    init {
        val m = LinkedHashMap<Int, MutableList<Any>>()
        var pos = 0
        try {
            while (pos < data.size) {
                val (tag, p1) = readVarint(pos)
                pos = p1
                val field = (tag ushr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> {
                        val (v, p) = readVarint(pos)
                        pos = p
                        m.getOrPut(field) { ArrayList() }.add(v)
                    }
                    1 -> {
                        var v = 0L
                        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xff) shl (8 * i))
                        pos += 8
                        m.getOrPut(field) { ArrayList() }.add(v)
                    }
                    2 -> {
                        val (len, p) = readVarint(pos)
                        pos = p
                        val n = len.toInt()
                        if (n < 0 || pos + n > data.size) break
                        m.getOrPut(field) { ArrayList() }.add(data.copyOfRange(pos, pos + n))
                        pos += n
                    }
                    5 -> {
                        var v = 0
                        for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xff) shl (8 * i))
                        pos += 4
                        m.getOrPut(field) { ArrayList() }.add(v)
                    }
                    else -> break
                }
            }
        } catch (_: Exception) {
        }
        fields = m
    }

    private fun readVarint(start: Int): Pair<Long, Int> {
        var r = 0L
        var shift = 0
        var pos = start
        while (pos < data.size && shift < 64) {
            val b = data[pos++].toInt() and 0xff
            r = r or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return r to pos
            shift += 7
        }
        throw IllegalArgumentException("varint")
    }

    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int, def: Long = 0L): Long = (fields[field]?.firstOrNull() as? Long) ?: def
    fun int(field: Int, def: Int = 0): Int {
        val v = fields[field]?.firstOrNull() ?: return def
        return when (v) {
            is Long -> v.toInt()
            is Int -> v
            else -> def
        }
    }
    fun bool(field: Int, def: Boolean = false) = long(field, if (def) 1L else 0L) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): ProtoReader? = bytes(field)?.let { ProtoReader(it) }
    fun messages(field: Int): List<ProtoReader> =
        fields[field]?.filterIsInstance<ByteArray>()?.map { ProtoReader(it) } ?: emptyList()
    fun ints(field: Int): List<Int> = fields[field]?.mapNotNull { (it as? Long)?.toInt() } ?: emptyList()
}
