package app.ft.iccoa

import android.content.Context
import app.ft.core.Prefs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Trust-on-first-verified-NORMAL car identity, anchored by PIN HMAC + ECDSA and
 * confirmed CONTROL. The 64-byte public key is encrypted/authenticated at rest,
 * bound to the approved car name and stable client ID, and cannot be silently reset.
 */
internal class IccoaTrustedCarKey(context: Context, vehicleProfileId: String = Prefs(context).profileId) {
    private val prefs = context.getSharedPreferences(IccoaVehicleStores.name("iccoa-trusted-quick-v1", vehicleProfileId), Context.MODE_PRIVATE)

    fun load(profileId: String, carName: String): ByteArray? {
        if (carName.isBlank()) return null
        val sealed = prefs.getString("car_auth_pk", null) ?: return null
        return runCatching {
            val key = key(false) ?: return null
            val input = Base64.getDecoder().decode(sealed)
            require(input.size == 12 + 64 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, input.copyOfRange(0, 12)))
            cipher.updateAAD(binding(profileId, carName))
            cipher.doFinal(input.copyOfRange(12, input.size)).takeIf { it.size == 64 }
        }.getOrNull()
    }

    fun saveIfAbsent(profileId: String, carName: String, verifiedPublicKey: ByteArray): Boolean {
        if (carName.isBlank() || verifiedPublicKey.size != 64) return false
        val existing = load(profileId, carName)
        if (existing != null) return java.security.MessageDigest.isEqual(existing, verifiedPublicKey)
        // A present but undecryptable entry is NOT consent to silently trust a new key.
        if (prefs.contains("car_auth_pk")) return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true) ?: return false)
            cipher.updateAAD(binding(profileId, carName))
            val sealed = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(verifiedPublicKey))
            prefs.edit().putString("car_auth_pk", sealed).commit()
        }.getOrDefault(false)
    }

    /** Only the owner can explicitly forget a verified car after HU replacement/reset. */
    fun clear(): Boolean = prefs.edit().remove("car_auth_pk").commit()

    private fun binding(profileId: String, carName: String) =
        ("FT_ICCOA_CAR_AUTH_V1|" + profileId + "|" + carName.trim().lowercase(Locale.ROOT))
            .toByteArray(Charsets.UTF_8)

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as? SecretKey
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(false).build())
        return generator.generateKey()
    }

    private companion object { const val ALIAS = "ft_iccoa_trusted_car_pubkey_v1" }
}
