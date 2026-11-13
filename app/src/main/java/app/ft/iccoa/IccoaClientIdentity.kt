package app.ft.iccoa

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Long-lived, non-exportable client signing identity.
 * Unlike ECDH session keys this identity must survive application restarts.
 * Never silently substitute a fresh key when a stored entry fails to load.
 */
internal object IccoaClientIdentity {
    private const val ALIAS = "ft_iccoa_client_identity_v1"

    @Synchronized fun load(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            val privateKey = store.getKey(ALIAS, null) as? PrivateKey
                ?: error("Stored ICCOA identity is inaccessible")
            val publicKey = store.getCertificate(ALIAS)?.publicKey
                ?: error("Stored ICCOA identity certificate is inaccessible")
            return KeyPair(publicKey, privateKey)
        }
        val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }
}
