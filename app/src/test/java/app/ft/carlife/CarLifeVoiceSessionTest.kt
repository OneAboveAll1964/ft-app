package app.ft.carlife

import android.content.Context
import app.ft.core.Prefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CarLifeVoiceSessionTest {
    @Test fun queuedVoiceCannotUseAReplacementCommandEvenBeforeTheSessionResetCallback() = runTest {
        val link = mock(WifiChannelLink::class.java)
        `when`(link.connected).thenReturn(true)
        `when`(link.commandGeneration).thenReturn(7L)
        val session = CarLifeSession(mock(Context::class.java), link, mock(Prefs::class.java), backgroundScope)
        val first = negotiateVoice(session)
        first.begin(16_000, 1)
        first.data(byteArrayOf(1))
        verify(link, times(2)).sendForCommand(eq(7L), eq(CarLifeProtocol.CH_TTS), anyBytes())
        clearInvocations(link)

        // CMD acceptance has advanced, but its session callback has not run yet.
        `when`(link.commandGeneration).thenReturn(8L)
        first.data(byteArrayOf(2))
        first.begin(16_000, 1)
        first.end()
        verify(link, never()).sendForCommand(anyLong(), anyInt(), anyBytes())

        session.stop()
        assertNull(session.voiceOutput)
        val second = negotiateVoice(session)
        assertNotSame(first, second)
        first.data(byteArrayOf(3))
        second.begin(16_000, 1)
        second.data(byteArrayOf(4))
        verify(link, times(2)).sendForCommand(eq(8L), eq(CarLifeProtocol.CH_TTS), anyBytes())
        verify(link, never()).sendForCommand(eq(7L), anyInt(), anyBytes())
        session.stop()
    }

    // Exercise voice setup without Android's static Build/device-info APIs.
    private fun negotiateVoice(session: CarLifeSession): CarLifeVoice {
        CarLifeSession::class.java.getDeclaredField("matched").apply { isAccessible = true }.set(session, true)
        CarLifeSession::class.java.getDeclaredMethod("openVoiceOutput").apply { isAccessible = true }.invoke(session)
        return requireNotNull(session.voiceOutput)
    }

    private fun anyBytes(): ByteArray = any(ByteArray::class.java) ?: ByteArray(0)
}
