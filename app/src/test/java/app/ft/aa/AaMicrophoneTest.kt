package app.ft.aa

import android.media.AudioRecord
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AaMicrophoneTest {
    @Test
    fun aRevokedPermissionReleasesTheRecorderAndLeavesTheMicrophoneInactive() {
        mockConstruction(AudioRecord::class.java) { recorder, _ ->
            `when`(recorder.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            doThrow(SecurityException("permission revoked")).`when`(recorder).startRecording()
        }.use { records ->
            val microphone = AaMicrophone { error("no audio should be delivered") }
            assertFalse(microphone.start())
            assertFalse(microphone.active)
            verify(records.constructed().single()).release()
        }
    }
}
