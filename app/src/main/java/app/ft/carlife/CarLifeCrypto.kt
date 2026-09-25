package app.ft.carlife

import android.util.Base64
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CarLifeCrypto {
    @Volatile var started = false
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var incoming = false
    @Volatile private var outgoing = false

    val active: Boolean get() = outgoing

    fun reset() {
        started = false
        key = null
        incoming = false
        outgoing = false
    }

    fun aesKeyRequest(rsaPublicKeyBase64: String): String? = runCatching {
        val spki = Base64.decode(rsaPublicKeyBase64.trim(), Base64.NO_WRAP)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val k = String(CharArray(16) { alphabet[rnd.nextInt(alphabet.length)] })
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, pub)
        val wrapped = c.doFinal(k.toByteArray(Charsets.UTF_8))
        key = SecretKeySpec(k.toByteArray(Charsets.UTF_8), "AES")
        Base64.encodeToString(wrapped, Base64.NO_WRAP)
    }.getOrNull()

    fun armIncoming() { incoming = true }

    fun enableOutgoing() { outgoing = true }

    fun encryptOut(payload: ByteArray): ByteArray {
        val k = key ?: return payload
        if (!outgoing || payload.isEmpty()) return payload
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, k)
        return c.doFinal(payload)
    }

    fun decryptIn(payload: ByteArray): ByteArray {
        val k = key ?: return payload
        if (!incoming || payload.isEmpty()) return payload
        return try {
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, k)
            c.doFinal(payload)
        } catch (_: Throwable) {
            payload
        }
    }
}
