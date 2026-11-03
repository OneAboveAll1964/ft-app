package app.ft.iccoa

import android.content.Context
import app.ft.core.Prefs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One owner-approved remembered-car PIN, encrypted by non-exportable AndroidKeyStore.
 * Old PIN may be rejected by OEM on subsequent pairing. Try once, then prompt owner.
 * Ciphertext in Android backup cannot be decrypted without the on-device keystore key.
 */
internal class IccoaTrustedPin(context: Context, vehicleProfileId: String = Prefs(context).profileId) {
    private val storage = context.getSharedPreferences(IccoaVehicleStores.name("iccoa-trusted-reconnect-v1", vehicleProfileId), Context.MODE_PRIVATE)

    fun load(profileId: String, carName: String): String? {
        if (carName.isBlank()) return null
        val sealed = storage.getString("sealed", null) ?: return null
        return runCatching {
            val k = key(createIfAbsent = false) ?: return null
            IccoaTrustedPinCodec.unseal(sealed, k, binding(profileId, carName))
        }.getOrNull()
    }

    fun save(profileId: String, carName: String, pin: String): Boolean {
        if (carName.isBlank() || !pin.matches(Regex("[0-9]{6}"))) return false
        return runCatching {
            val k = key(createIfAbsent = true) ?: return false
            val sealed = IccoaTrustedPinCodec.seal(pin, k, binding(profileId, carName))
            storage.edit().putString("sealed", sealed).commit()
        }.getOrDefault(false)
    }

    fun clear() { storage.edit().remove("sealed").commit() }

    private fun binding(profileId: String, carName: String): ByteArray =
        ("FT_ICCOA_PIN_V1|" + profileId + "|" + carName.trim().lowercase(Locale.ROOT))
            .toByteArray(Charsets.UTF_8)

    private fun key(createIfAbsent: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS))
            return store.getKey(ALIAS, null) as? SecretKey
        if (!createIfAbsent) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "ft_iccoa_remembered_pin_v1"
    }
}
