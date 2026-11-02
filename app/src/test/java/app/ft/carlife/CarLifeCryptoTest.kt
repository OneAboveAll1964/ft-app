package app.ft.carlife

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CarLifeCryptoTest {
    @Test fun anAcknowledgementWithoutAKeyCannotEnableEncryption() {
        val crypto = CarLifeCrypto()
        crypto.armIncoming()
        assertFalse(crypto.enableOutgoing())
        assertFalse(crypto.active)
    }

    @Test fun aWrappedKeyMustBeSentBeforeEncryptionCanBeEnabled() = withBase64 {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val crypto = CarLifeCrypto()
        val request = crypto.aesKeyRequest(java.util.Base64.getEncoder().encodeToString(pair.public.encoded))!!
        assertFalse(crypto.enableOutgoing())
        crypto.armIncoming()
        assertTrue(crypto.enableOutgoing())
        assertFalse("duplicate acknowledgement", crypto.enableOutgoing())

        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.DECRYPT_MODE, pair.private)
        val key = SecretKeySpec(rsa.doFinal(java.util.Base64.getDecoder().decode(request)), "AES")
        val aes = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aes.init(Cipher.DECRYPT_MODE, key)
        val plain = "CarLife payload".toByteArray()
        val encrypted = crypto.encryptOut(plain)
        assertFalse(plain.contentEquals(encrypted))
        assertArrayEquals(plain, aes.doFinal(encrypted))
        assertArrayEquals(plain, crypto.decryptIn(encrypted))

        crypto.reset()
        assertFalse(crypto.enableOutgoing())
        assertFalse(crypto.active)
        assertArrayEquals(plain, crypto.encryptOut(plain))
    }

    @Test fun anInvalidReplacementCannotReuseThePreviousKey() = withBase64 {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val crypto = CarLifeCrypto()
        assertNotNull(crypto.aesKeyRequest(java.util.Base64.getEncoder().encodeToString(pair.public.encoded)))
        crypto.armIncoming()
        assertNull(crypto.aesKeyRequest("not a public key"))
        assertFalse(crypto.enableOutgoing())
        assertFalse(crypto.active)
    }

    companion object {
        fun withBase64(block: () -> Unit) {
            mockStatic(Base64::class.java).use { base64 ->
                base64.`when`<ByteArray> { Base64.decode(anyString(), anyInt()) }
                    .thenAnswer { java.util.Base64.getDecoder().decode(it.getArgument<String>(0)) }
                base64.`when`<String> { Base64.encodeToString(any(ByteArray::class.java), anyInt()) }
                    .thenAnswer { java.util.Base64.getEncoder().encodeToString(it.getArgument(0)) }
                block()
            }
        }
    }
}
