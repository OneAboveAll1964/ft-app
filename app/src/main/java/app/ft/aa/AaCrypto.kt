package app.ft.aa

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AaCrypto(context: Context) {
    private val engine: SSLEngine
    private val lock = Any()
    private var netOut = ByteBuffer.allocate(1 shl 16)
    private var appIn = ByteBuffer.allocate(1 shl 16)
    private var pending = ByteArray(0)
    @Volatile var handshakeDone = false
        private set

    init {
        val cert = context.assets.open("aa_hu_cert.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val key = loadKey(context.assets.open("aa_hu_key.pem").use { it.readBytes().toString(Charsets.US_ASCII) })
        val pwd = "ft".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("aa", key, pwd, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pwd) }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, trustAll, SecureRandom()) }
        engine = ctx.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
    }

    private fun loadKey(pem: String): PrivateKey {
        val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        val der = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    fun handshakeStep(incoming: ByteArray?): ByteArray = synchronized(lock) {
        val out = ByteArrayOutputStream()
        val netIn = ByteBuffer.wrap(if (incoming != null && incoming.isNotEmpty()) pending + incoming else pending)
        pending = ByteArray(0)
        var guard = 0
        loop@ while (guard++ < 64) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(ByteBuffer.allocate(0), netOut)
                    netOut.flip()
                    val b = ByteArray(netOut.remaining())
                    netOut.get(b)
                    out.write(b)
                    if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) handshakeDone = true
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netIn.hasRemaining()) break@loop
                    appIn.clear()
                    val r = engine.unwrap(netIn, appIn)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        val rest = ByteArray(netIn.remaining())
                        netIn.get(rest)
                        pending = rest
                        break@loop
                    }
                    if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) handshakeDone = true
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var t = engine.delegatedTask
                    while (t != null) {
                        t.run()
                        t = engine.delegatedTask
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    handshakeDone = true
                    break@loop
                }
                else -> break@loop
            }
        }
        if (netIn.hasRemaining()) {
            val rest = ByteArray(netIn.remaining())
            netIn.get(rest)
            pending = rest
        }
        out.toByteArray()
    }

    fun encrypt(plain: ByteArray): ByteArray = synchronized(lock) {
        val src = ByteBuffer.wrap(plain)
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            netOut.clear()
            val r = engine.wrap(src, netOut)
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                continue
            }
            if (r.status != SSLEngineResult.Status.OK) throw IllegalStateException("tls wrap ${r.status}")
            netOut.flip()
            val b = ByteArray(netOut.remaining())
            netOut.get(b)
            out.write(b)
        }
        out.toByteArray()
    }

    fun decrypt(cipher: ByteArray): ByteArray = synchronized(lock) {
        val src = ByteBuffer.wrap(cipher)
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            appIn.clear()
            val r = engine.unwrap(src, appIn)
            when (r.status) {
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    val b = ByteArray(appIn.remaining())
                    appIn.get(b)
                    out.write(b)
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                else -> break
            }
        }
        out.toByteArray()
    }
}
