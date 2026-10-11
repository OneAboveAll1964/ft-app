package app.ft.supercast.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class AdbWireTest {
    private val key: RSAPublicKey = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(AdbWire.KEY_SIZE) }
        .generateKeyPair().public as RSAPublicKey

    @Test
    fun aMessageFramesIntoTwentyFourBytesPlusPayload() {
        val message = AdbMessage(AdbWire.OPEN, 7, 0, "shell:id\u0000".toByteArray())
        val framed = AdbWire.frame(message)
        assertEquals(AdbWire.HEADER + message.payload.size, framed.size)
        val fields = AdbWire.readHeader(framed)
        assertEquals(AdbWire.OPEN, fields[0])
        assertEquals(7, fields[1])
        assertEquals(message.payload.size, fields[3])
        assertEquals(AdbWire.checksum(message.payload), fields[4])
        assertTrue(AdbWire.headerValid(fields))
    }

    @Test
    fun theMagicMustBeTheInverseOfTheCommand() {
        val fields = AdbWire.readHeader(AdbWire.frame(AdbWire.connect()))
        assertTrue(AdbWire.headerValid(fields))
        assertFalse(AdbWire.headerValid(fields.copyOf().also { it[5] = 0 }))
    }

    @Test
    fun anOversizedPayloadIsRefused() {
        assertFalse(AdbWire.headerValid(intArrayOf(AdbWire.WRTE, 0, 0, AdbWire.MAX_DATA + 1, 0, AdbWire.WRTE.inv())))
    }

    @Test
    fun theChecksumIsTheUnsignedByteSum() {
        assertEquals(0, AdbWire.checksum(ByteArray(0)))
        assertEquals(256, AdbWire.checksum(byteArrayOf(0xFF.toByte(), 0x01)))
    }

    @Test
    fun theConnectMessageAnnouncesHost() {
        val message = AdbWire.connect()
        assertEquals(AdbWire.CNXN, message.command)
        assertEquals(AdbWire.VERSION, message.arg0)
        assertEquals("host::\u0000", String(message.payload))
    }

    @Test
    fun anOpenedServiceIsNullTerminated() {
        assertEquals("sync:\u0000", String(AdbWire.open(3, "sync:").payload))
    }

    @Test
    fun thePublicKeyBlobHasTheAndroidShape() {
        val blob = AdbWire.publicKeyBlob(key)
        assertEquals(AdbWire.BLOB_SIZE, blob.size)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        buffer.int
        val modulus = ByteArray(AdbWire.MODULUS_BYTES).also { buffer.get(it) }
        val rr = ByteArray(AdbWire.MODULUS_BYTES).also { buffer.get(it) }
        assertEquals(key.publicExponent.toInt(), buffer.int)
        assertArrayEquals(AdbWire.littleEndian(key.modulus), modulus)
        assertArrayEquals(
            AdbWire.littleEndian(BigInteger.ZERO.setBit(AdbWire.KEY_SIZE).modPow(BigInteger.valueOf(2), key.modulus)),
            rr
        )
    }

    @Test
    fun theMontgomeryInverseIsCorrect() {
        val buffer = ByteBuffer.wrap(AdbWire.publicKeyBlob(key)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int
        val n0inv = buffer.int.toLong() and 0xFFFFFFFFL
        val word = BigInteger.ONE.shiftLeft(32)
        assertEquals(word.subtract(key.modulus.mod(word).modInverse(word)).toLong() and 0xFFFFFFFFL, n0inv)
    }

    @Test
    fun thePublicKeyLineCarriesANameAndEndsWithNul() {
        val line = String(AdbWire.publicKeyLine(key, "FT"))
        assertTrue(line.endsWith(" FT\u0000"))
        assertFalse(line.contains('\n'))
    }

    @Test
    fun theSigningBlockIsExactlyTheModulusSize() {
        val block = AdbWire.signingBlock(ByteArray(20) { it.toByte() })
        assertEquals(AdbWire.MODULUS_BYTES, block.size)
        assertEquals(0x00, block[0].toInt())
        assertEquals(0x01, block[1].toInt())
        assertEquals(0xFF, block[2].toInt() and 0xFF)
        assertEquals(0x00, block[AdbWire.MODULUS_BYTES - 36].toInt())
    }

    @Test
    fun littleEndianIsTheReverseOfBigEndianAndPadded() {
        val little = AdbWire.littleEndian(BigInteger("0102030405", 16))
        assertEquals(AdbWire.MODULUS_BYTES, little.size)
        assertEquals(0x05, little[0].toInt())
        assertEquals(0x01, little[4].toInt())
        assertEquals(0x00, little[5].toInt())
    }
}
