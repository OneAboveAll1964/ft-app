package app.ft.iccoa

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** ICCOA control wire primitives. No Android state or process-wide session keys. */
object IccoaControl {
    const val HEADER_SIZE = 20
    const val MAX_FRAME_SIZE = 4_000_000

    data class Frame(val sequence: Int, val category: Int, val method: Int,
                     val type: Int, val format: Int, val body: ByteArray)

    fun crc16(bytes: ByteArray, length: Int = bytes.size): Int {
        require(length in 0..bytes.size)
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return crc
    }

    fun frame(category: Int, method: Int, type: Int, body: ByteArray,
              sequence: Int, format: Int = 1,
              timeSeconds: Int = (System.currentTimeMillis() / 1000).toInt()): ByteArray {
        require(category in 0..255 && method in 0..65535 && type in 0..31 && format in 0..3)
        require(body.size <= MAX_FRAME_SIZE - HEADER_SIZE)
        val result = ByteBuffer.allocate(HEADER_SIZE + body.size)
            .putInt(HEADER_SIZE + body.size).putInt(sequence).putInt(timeSeconds)
            .put(((type shl 3) or (format shl 1) or 1).toByte()).put(category.toByte())
            .putShort(method.toShort()).putShort(0).putShort(0).put(body).array()
        ByteBuffer.wrap(result).putShort(18, crc16(result, 18).toShort())
        return result
    }

    /** Read the fixed header before allocating the untrusted body. */
    fun read(input: InputStream, onHeader: ((String) -> Unit)? = null): Frame =
        readOrNull(input, onHeader) ?: throw EOFException("ICCOA channel closed")

    /** Only EOF between complete frames is clean; a partial header/body still throws. */
    fun readOrNull(input: InputStream, onHeader: ((String) -> Unit)? = null): Frame? {
        val stream = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        val first = stream.read()
        if (first < 0) return null
        header[0] = first.toByte()
        stream.readFully(header, 1, HEADER_SIZE - 1)
        val view = ByteBuffer.wrap(header)
        val length = view.int
        val flags = header[12].toInt() and 255
        val crcValid = (view.getShort(18).toInt() and 65535) == crc16(header, 18)
        // Scalar header metadata only; never dump payloads, keys, PINs or credentials.
        onHeader?.invoke("length=$length category=${header[13].toInt() and 255} method=${view.getShort(14).toInt() and 65535} flags=$flags crcValid=$crcValid")
        if (length !in HEADER_SIZE..MAX_FRAME_SIZE) throw IccoaHandshakeException(IccoaHandshakeException.Reason.FRAME_LENGTH)
        if (!crcValid) throw IccoaHandshakeException(IccoaHandshakeException.Reason.HEADER_CRC)
        // XA60 replies with bit 0 clear (e.g. 0x12). It is not a required version marker.
        // Length and CRC checks still apply before reading the body; authentication follows separately.
        val body = ByteArray(length - HEADER_SIZE)
        stream.readFully(body)
        return Frame(view.getInt(4), header[13].toInt() and 255,
            view.getShort(14).toInt() and 65535, flags ushr 3, (flags ushr 1) and 3, body)
    }

    /** Session-local AES-GCM envelope, also used by the UIBC channel. */
    class Cipher(key: ByteArray) {
        private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 16) }, "AES")
        private val random = SecureRandom()
        // Each lane/thread owns separate encrypt/decrypt state. Every message
        // still gets a fresh IV and init; neither a Cipher nor its state is shared.
        private class LocalCipher : ThreadLocal<javax.crypto.Cipher>() {
            override fun initialValue(): javax.crypto.Cipher =
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        }
        private val encryption = LocalCipher()
        private val decryption = LocalCipher()

        fun encrypt(plain: ByteArray): ByteArray {
            require(plain.size <= MAX_FRAME_SIZE - 56)
            val iv = ByteArray(12).also(random::nextBytes)
            val aes = encryption.get()!!
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            val encrypted = aes.doFinal(plain)
            return ByteBuffer.allocate(8 + iv.size + encrypted.size)
                .putInt(iv.size).put(iv).putInt(encrypted.size).put(encrypted).array()
        }

        fun decrypt(envelope: ByteArray): ByteArray {
            require(envelope.size in 36..MAX_FRAME_SIZE) { "Invalid ICCOA encrypted body size" }
            val input = ByteBuffer.wrap(envelope)
            val result = ByteArrayOutputStream()
            while (input.hasRemaining()) {
                require(input.remaining() >= 36 && input.int == 12) { "Invalid ICCOA GCM IV" }
                val iv = ByteArray(12).also(input::get)
                val size = input.int
                require(size in 16..input.remaining()) { "Invalid ICCOA GCM length" }
                val encrypted = ByteArray(size).also(input::get)
                val aes = decryption.get()!!
                aes.init(javax.crypto.Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
                result.write(aes.doFinal(encrypted))
            }
            return result.toByteArray()
        }

        fun decryptUibc(envelope: ByteArray): ByteArray = decrypt(envelope)
    }

    fun isEncrypted(body: ByteArray): Boolean = body.size >= 4 && ByteBuffer.wrap(body).int == 12

    /** Strict, bounded protobuf reader for handshake fields; accepts unknown ordinary wire types. */
    class Fields(bytes: ByteArray) {
        private val fields = mutableMapOf<Int, Any>()
        init {
            var offset = 0
            fun varint(): Long {
                var value = 0L
                for (shift in 0..63 step 7) {
                    require(offset < bytes.size) { "Truncated protobuf integer" }
                    val next = bytes[offset++].toInt() and 255
                    require(shift != 63 || next <= 1) { "Protobuf integer overflow" }
                    value = value or ((next and 127).toLong() shl shift)
                    if (next and 128 == 0) return value
                }
                error("Protobuf integer overflow")
            }
            fun take(size: Int): ByteArray {
                require(size >= 0 && size <= bytes.size - offset) { "Truncated protobuf field" }
                return bytes.copyOfRange(offset, offset + size).also { offset += size }
            }
            while (offset < bytes.size) {
                val tag = varint()
                require(tag > 0 && tag ushr 3 <= 0x1fffffff) { "Invalid protobuf tag" }
                val field = (tag ushr 3).toInt()
                require(field > 0)
                val value: Any = when ((tag and 7).toInt()) {
                    0 -> varint()
                    1 -> take(8)
                    2 -> { val size = varint(); require(size in 0..bytes.size.toLong()); take(size.toInt()) }
                    5 -> take(4)
                    else -> error("Unsupported protobuf wire type")
                }
                require(!fields.containsKey(field)) { "Duplicate handshake field" }
                fields[field] = value
            }
        }
        fun number(field: Int, default: Long = 0): Long {
            val value = fields[field] ?: return default
            require(value is Long) { "Invalid handshake integer field $field" }
            return value
        }
        fun bytes(field: Int): ByteArray = fields[field] as? ByteArray ?: error("Missing handshake field $field")

        /** Field numbers/types/lengths, with all values deliberately omitted. */
        fun shape(): String = fields.entries.joinToString(",") { (field, value) ->
            "$field:" + if (value is ByteArray) "bytes(${value.size})" else "number"
        }
    }
}
