package app.ft.supercast.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is AdbMessage && command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = command * 31 + arg0 * 17 + arg1 * 13 + payload.contentHashCode()
}

object AdbWire {
    const val CNXN = 0x4E584E43
    const val AUTH = 0x41555448
    const val OPEN = 0x4F50454E
    const val OKAY = 0x4F4B4159
    const val CLSE = 0x434C5345
    const val WRTE = 0x57525445

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_PUBLIC_KEY = 3

    const val VERSION = 0x01000001
    const val MAX_DATA = 1024 * 1024
    const val HEADER = 24
    const val IDENTITY = "host::\u0000"

    const val KEY_SIZE = 2048
    const val MODULUS_BYTES = KEY_SIZE / 8
    const val BLOB_SIZE = 524

    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (byte in payload) sum += byte.toInt() and 0xFF
        return sum
    }

    fun frame(message: AdbMessage): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER + message.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(message.command)
        buffer.putInt(message.arg0)
        buffer.putInt(message.arg1)
        buffer.putInt(message.payload.size)
        buffer.putInt(checksum(message.payload))
        buffer.putInt(message.command.inv())
        buffer.put(message.payload)
        return buffer.array()
    }

    fun readHeader(header: ByteArray): IntArray {
        require(header.size >= HEADER) { "short adb header" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(6) { buffer.int }
    }

    fun headerValid(fields: IntArray): Boolean =
        fields.size == 6 && fields[0] == fields[5].inv() && fields[3] in 0..MAX_DATA

    fun connect(): AdbMessage = AdbMessage(CNXN, VERSION, MAX_DATA, IDENTITY.toByteArray())

    fun open(localId: Int, service: String): AdbMessage =
        AdbMessage(OPEN, localId, 0, (service + "\u0000").toByteArray())

    fun write(localId: Int, remoteId: Int, data: ByteArray): AdbMessage =
        AdbMessage(WRTE, localId, remoteId, data)

    fun okay(localId: Int, remoteId: Int): AdbMessage = AdbMessage(OKAY, localId, remoteId, ByteArray(0))

    fun close(localId: Int, remoteId: Int): AdbMessage = AdbMessage(CLSE, localId, remoteId, ByteArray(0))

    fun publicKeyBlob(key: RSAPublicKey): ByteArray {
        val modulus = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val n0inv = word.subtract(modulus.mod(word).modInverse(word)).toLong().toInt()
        val rr = BigInteger.ZERO.setBit(KEY_SIZE).modPow(BigInteger.valueOf(2), modulus)
        val buffer = ByteBuffer.allocate(BLOB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_BYTES / 4)
        buffer.putInt(n0inv)
        buffer.put(littleEndian(modulus))
        buffer.put(littleEndian(rr))
        buffer.putInt(key.publicExponent.toInt())
        return buffer.array()
    }

    fun publicKeyLine(key: RSAPublicKey, name: String): ByteArray {
        val encoded = java.util.Base64.getEncoder().encodeToString(publicKeyBlob(key))
        return ("$encoded $name\u0000").toByteArray()
    }

    fun signingBlock(token: ByteArray): ByteArray {
        val digest = SHA1_PREFIX + token
        val padding = MODULUS_BYTES - digest.size - 3
        require(padding > 0) { "token too long to sign" }
        val out = ByteArray(MODULUS_BYTES)
        out[0] = 0x00
        out[1] = 0x01
        for (i in 0 until padding) out[2 + i] = 0xFF.toByte()
        out[2 + padding] = 0x00
        System.arraycopy(digest, 0, out, 3 + padding, digest.size)
        return out
    }

    internal fun littleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray()
        val trimmed = if (big.size > MODULUS_BYTES) big.copyOfRange(big.size - MODULUS_BYTES, big.size) else big
        val out = ByteArray(MODULUS_BYTES)
        for (i in trimmed.indices) out[i] = trimmed[trimmed.size - 1 - i]
        return out
    }
}
