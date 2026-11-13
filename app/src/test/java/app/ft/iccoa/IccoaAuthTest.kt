package app.ft.iccoa

import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement

class IccoaAuthTest {
    private val pin = "012345"

    private data class PeerResponse(val bytes: ByteArray, val key: ByteArray)

    /** Independent peer performs the other half of ECDH and signs the response transcript. */
    private fun response(request: ByteArray, badMac: Boolean = false, badSignature: Boolean = false): PeerResponse {
        val fields = IccoaControl.Fields(request)
        val identity = IccoaAuth.newKeyPair()
        val agreement = IccoaAuth.newKeyPair()
        val carIdentity = IccoaAuth.publicBytes(identity.public as ECPublicKey)
        val carAgreement = IccoaAuth.publicBytes(agreement.public as ECPublicKey)
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(carAgreement + fields.bytes(4) + nonce + fields.bytes(6))
            sign()
        }.also { if (badSignature) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val mac = IccoaAuth.pinMac(pin, carIdentity + fields.bytes(2), nonce + fields.bytes(6))
            .also { if (badMac) it[0] = (it[0].toInt() xor 1).toByte() }
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(agreement.private)
            doPhase(IccoaAuth.publicKey(fields.bytes(4)), true)
            generateSecret()
        }
        val key = IccoaAuth.hkdf(shared, IccoaAuth.sha256(fields.bytes(6) + nonce), "session_key".toByteArray(), 16)
        val bytes = ProtoWriter().int32(1, 1793).bytes(2, carIdentity).bytes(3, mac)
            .bytes(4, carAgreement).bytes(5, signature).bytes(6, nonce).int32(7, 0).int32(8, 769).toByteArray()
        return PeerResponse(bytes, key)
    }

    @Test fun pinSignInVerifiesPeerAndDerivesMatchingSessionKey() {
        val client = IccoaAuth(pin, "test-phone", "test-model")
        val request = client.request()
        val fields = IccoaControl.Fields(request)
        assertEquals(1L, fields.number(9))
        assertArrayEquals(IccoaAuth.pinMac(pin, fields.bytes(2), fields.bytes(6)), fields.bytes(3))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(IccoaAuth.publicKey(fields.bytes(2)))
            update(fields.bytes(4) + fields.bytes(6))
            verify(fields.bytes(5))
        })
        val peer = response(request)
        val cipher = client.accept(peer.bytes)
        val confirm = IccoaControl.Fields(client.confirm(cipher, "192.168.50.20")).bytes(1)
        assertEquals("192.168.50.20:0", IccoaControl.Cipher(peer.key).decrypt(confirm).toString(Charsets.UTF_8))
    }

    @Test fun wrongPinMacIsRejectedEvenWithValidPeerSignature() {
        val client = IccoaAuth(pin, "test-phone", "test-model")
        val peer = response(client.request(), badMac = true)
        val error = assertThrows(IccoaHandshakeException::class.java) { client.accept(peer.bytes) }
        assertEquals(IccoaHandshakeException.Reason.PIN_MAC, error.reason)
    }

    @Test fun xa60ResponseFlagsReachAuthenticationAndKeepPinVerification() {
        for (badMac in listOf(false, true)) {
            val client = IccoaAuth(pin, "test-phone", "test-model")
            val peer = response(client.request(), badMac = badMac)
            val packet = IccoaControl.frame(3, 2, 2, peer.bytes, 1).also {
                it[12] = 0x12
                java.nio.ByteBuffer.wrap(it).putShort(18, IccoaControl.crc16(it, 18).toShort())
            }
            val frame = IccoaControl.read(java.io.ByteArrayInputStream(packet))
            if (badMac) {
                val error = assertThrows(IccoaHandshakeException::class.java) { client.accept(frame.body) }
                assertEquals(IccoaHandshakeException.Reason.PIN_MAC, error.reason)
            } else {
                val cipher = client.accept(frame.body)
                val confirm = IccoaControl.Fields(client.confirm(cipher, "192.168.50.20")).bytes(1)
                assertEquals("192.168.50.20:0", IccoaControl.Cipher(peer.key).decrypt(confirm).toString(Charsets.UTF_8))
            }
        }
    }

    @Test fun invalidSignatureIsRejectedEvenWithValidPinMac() {
        val client = IccoaAuth(pin, "test-phone", "test-model")
        val peer = response(client.request(), badSignature = true)
        assertThrows(Exception::class.java) { client.accept(peer.bytes) }
    }

    @Test fun rejectedSignInDoesNotAcceptMissingKeys() {
        val client = IccoaAuth(pin, "test-phone", "test-model")
        assertThrows(IllegalArgumentException::class.java) {
            client.accept(ProtoWriter().int32(7, 1).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.accept(ProtoWriter().bytes(7, byteArrayOf(0)).toByteArray())
        }
    }

    @Test fun pinMustBeSixAsciiDigitsAndRetainsLeadingZero() {
        for (bad in listOf("", "12345", "1234567", "12 456", "１２３４５６")) {
            assertThrows(IllegalArgumentException::class.java) { IccoaAuth(bad, "phone", "model") }
        }
        IccoaAuth("000001", "phone", "model")
    }

    @Test fun hkdfMatchesRfc5869Sha256TestCaseOne() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            IccoaAuth.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42))
    }

    @Test fun receiverRejectionRetainsItsNumericReasonWithoutAssumingWrongPin() {
        for (result in 1L..4L) {
            val client = IccoaAuth(pin, "phone", "model")
            val error = assertThrows(IccoaHandshakeException::class.java) {
                client.accept(ProtoWriter().int64(7, result).toByteArray())
            }
            assertEquals(IccoaHandshakeException.Reason.RESPONSE_RESULT, error.reason)
            assertEquals(result, error.result)
            assertEquals("RESPONSE_RESULT result=$result", error.diagnostic)
        }
    }

    @Test fun progressIdentifiesMacFailureWithoutExposingTranscript() {
        val client = IccoaAuth(pin, "private-client-id", "private-model")
        val peer = response(client.request(), badMac = true)
        val progress = mutableListOf<String>()
        assertThrows(IccoaHandshakeException::class.java) { client.accept(peer.bytes, progress::add) }
        assertEquals("verify-pin-mac", progress.last())
        assertTrue(progress.any { it.contains("2:bytes(64)") })
        assertFalse(progress.joinToString().contains(pin))
        assertFalse(progress.joinToString().contains("private-client-id"))
        assertFalse(progress.joinToString().contains("private-model"))
    }
}
