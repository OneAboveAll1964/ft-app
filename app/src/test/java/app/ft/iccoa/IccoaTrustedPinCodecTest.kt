package app.ft.iccoa

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class IccoaTrustedPinCodecTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    private val aad = "FT_ICCOA_PIN_V1|one-identity|saved-car".toByteArray()

    @Test fun encryptedTrustedCodeSurvivesOnlyCorrectBinding() {
        val k = key()
        val sealed = IccoaTrustedPinCodec.seal("654321", k, aad)
        assertFalse(sealed.contains("654321"))
        assertEquals("654321", IccoaTrustedPinCodec.unseal(sealed, k, aad))
        assertNull(IccoaTrustedPinCodec.unseal(sealed, k, "another-car".toByteArray()))
        assertNull(IccoaTrustedPinCodec.unseal(sealed, key(), aad))
    }

    @Test fun tamperingAndInvalidPinNeverPass() {
        val k = key()
        val sealed = IccoaTrustedPinCodec.seal("123456", k, aad)
        val tampered = Base64.getDecoder().decode(sealed).also { it[it.lastIndex] =
            (it.last().toInt() xor 1).toByte() }
        assertNull(IccoaTrustedPinCodec.unseal(Base64.getEncoder().encodeToString(tampered), k, aad))
        assertNull(IccoaTrustedPinCodec.unseal("invalid", k, aad))
        assertThrows(IllegalArgumentException::class.java) {
            IccoaTrustedPinCodec.seal("not-a-pin", k, aad)
        }
    }
}
