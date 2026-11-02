package app.ft.carlife

import android.content.Context
import android.content.SharedPreferences
import app.ft.core.Prefs
import app.ft.core.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.security.KeyPairGenerator

class CarLifeEncryptionSessionTest {
    @Test fun anUnsolicitedOrFailedKeyExchangeDoesNotSendEncryptReady() = runTest {
        CarLifeCryptoTest.withBase64 {
            val link = TestLink()
            session(link, backgroundScope)
            link.command(CarLifeProtocol.CMD_HU_AES_REC_RESPONSE)
            link.command(CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY_RESPONSE, keyPayload("bad key"))
            link.command(CarLifeProtocol.CMD_HU_AES_REC_RESPONSE)
            assertFalse(link.sent.contains(CarLifeProtocol.CMD_MD_ENCRYPT_READY))
        }
    }

    @Test fun aSuccessfulKeyExchangeSendsReadyOnlyOnce() = runTest {
        CarLifeCryptoTest.withBase64 {
            val link = TestLink()
            session(link, backgroundScope)
            link.command(CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY_RESPONSE, keyPayload(publicKey()))
            link.command(CarLifeProtocol.CMD_HU_AES_REC_RESPONSE)
            link.command(CarLifeProtocol.CMD_HU_AES_REC_RESPONSE)
            assertEquals(1, link.sent.count { it == CarLifeProtocol.CMD_MD_AES_KEY_SEND_REQUEST })
            assertEquals(1, link.sent.count { it == CarLifeProtocol.CMD_MD_ENCRYPT_READY })
        }
    }

    @Test fun aFailedKeySendCannotBeAcknowledged() = runTest {
        CarLifeCryptoTest.withBase64 {
            val link = TestLink().apply { accept = false }
            session(link, backgroundScope)
            link.command(CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY_RESPONSE, keyPayload(publicKey()))
            link.command(CarLifeProtocol.CMD_HU_AES_REC_RESPONSE)
            assertFalse(link.sent.contains(CarLifeProtocol.CMD_MD_ENCRYPT_READY))
        }
    }

    private fun publicKey(): String = java.util.Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public.encoded)
    private fun keyPayload(key: String) = ProtoWriter().string(1, key).toByteArray()

    private fun session(link: TestLink, scope: CoroutineScope): CarLifeSession {
        val context = mock(Context::class.java)
        val shared = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(shared)
        `when`(shared.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
        `when`(shared.getString(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
        `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
        return CarLifeSession(context, link, Prefs(context), scope).also { it.start() }
    }

    private class TestLink : CarLifeLink {
        override val name = "test"
        override val connected = true
        override val isOpen = true
        var accept = true
        val sent = mutableListOf<Int>()
        private lateinit var receive: LinkMessage
        override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit,
                           onConnected: () -> Unit, onClosed: (String) -> Unit) { receive = onMessage }
        override fun send(channel: Int, inner: ByteArray): Boolean {
            sent += CarLifeFraming.serviceId(channel, inner)
            return accept
        }
        override fun stop() = Unit
        fun command(id: Int, payload: ByteArray = ByteArray(0)) {
            val packet = CarLifeFraming.cmd(id, payload)
            receive(CarLifeProtocol.CH_CMD, packet.copyOfRange(0, CarLifeProtocol.HEAD_CMD), payload)
        }
    }
}
