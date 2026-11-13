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

class SongInfoSessionTest {
    @Test fun temporarySuspensionKeepsThePreferenceAndCanBeResumed() = runTest {
        val f = Fixture(backgroundScope)
        f.session.allowSongInfo(false)
        f.song()
        f.session.sendSongPosition(1000)
        assertTrue(f.link.sent.isEmpty())
        assertTrue(f.prefs.carSongInfo)
        verify(f.editor, never()).putBoolean(eq("carSongInfo"), anyBoolean())

        f.session.allowSongInfo(true)
        f.song()
        f.session.sendSongPosition(2000)
        assertEquals(listOf(CarLifeProtocol.CMD_MEDIA_INFO, CarLifeProtocol.CMD_MEDIA_PROGRESS_BAR), f.link.sent)
    }

    @Test fun onlyAcceptedMetadataUpdatesTheDisconnectHeuristic() = runTest {
        val f = Fixture(backgroundScope)
        var notifications = 0
        f.session.onSongInfo = { notifications++ }
        f.link.accept = false
        f.song()
        assertEquals(0, notifications)
        f.link.accept = true
        f.song()
        assertEquals(1, notifications)
    }

    @Test fun suspensionSurvivesTransportResetInTheSameSession() = runTest {
        val f = Fixture(backgroundScope)
        f.session.allowSongInfo(false)
        f.session.stop()
        f.session.start()
        f.startVideo()
        f.song()
        assertTrue(f.link.sent.isEmpty())
    }

    private class Fixture(scope: CoroutineScope) {
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        private val shared = mock(SharedPreferences::class.java)
        private val context = mock(Context::class.java)
        val link = TestLink()
        val prefs: Prefs
        val session: CarLifeSession
        init {
            `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(shared)
            `when`(shared.edit()).thenReturn(editor)
            `when`(shared.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
            `when`(shared.getString(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
            `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
            prefs = Prefs(context)
            session = CarLifeSession(context, link, prefs, scope)
            session.start()
            startVideo()
        }
        fun startVideo() {
            link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT,
                ProtoWriter().int32(1, 1280).int32(2, 720).toByteArray())
            link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_START)
            link.sent.clear()
        }
        fun song() = session.sendSong("Song", "Artist", "Album", null, 120_000, 1, "id")
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
