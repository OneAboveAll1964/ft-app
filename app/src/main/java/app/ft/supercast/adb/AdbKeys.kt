package app.ft.supercast.adb

import android.content.Context
import app.ft.core.DiagLog
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

class AdbKeys private constructor(val privateKey: PrivateKey, val publicKey: RSAPublicKey) {
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(AdbWire.signingBlock(token))
    }

    fun line(name: String): ByteArray = AdbWire.publicKeyLine(publicKey, name)

    companion object {
        private const val TAG = "SuperCast"
        private const val FILE = "adb_private.key"

        fun of(context: Context): AdbKeys? = runCatching {
            val file = File(context.filesDir, FILE)
            val factory = KeyFactory.getInstance("RSA")
            val private = if (file.exists()) {
                factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
            } else {
                val made = KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(AdbWire.KEY_SIZE) }
                    .generateKeyPair()
                file.writeBytes(made.private.encoded)
                file.setReadable(false, false)
                file.setReadable(true, true)
                DiagLog.i(TAG, "made a fresh key for talking to this phone's own debug bridge")
                made.private
            }
            val crt = private as RSAPrivateCrtKey
            val public = factory.generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey
            AdbKeys(private, public)
        }.onFailure { DiagLog.e(TAG, "could not prepare the debug bridge key", it) }.getOrNull()
    }
}
