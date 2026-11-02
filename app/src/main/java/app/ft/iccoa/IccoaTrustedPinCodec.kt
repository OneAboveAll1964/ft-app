package app.ft.iccoa

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for a single six-digit previously accepted ICCOA code. */
internal object IccoaTrustedPinCodec {
    fun seal(pin: String, key: SecretKey, associatedData: ByteArray): String {
        require(pin.matches(Regex("[0-9]{6}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        require(iv.size == 12)
        return Base64.getEncoder().encodeToString(
            iv + cipher.doFinal(pin.toByteArray(Charsets.US_ASCII)))
    }

    fun unseal(value: String, key: SecretKey, associatedData: ByteArray): String? =
        runCatching {
            val bytes = Base64.getDecoder().decode(value)
            require(bytes.size in 34..128)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(associatedData)
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.US_ASCII)
                .takeIf { it.matches(Regex("[0-9]{6}")) }
        }.getOrNull()
}
