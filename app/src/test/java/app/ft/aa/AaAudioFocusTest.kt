package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class AaAudioFocusTest {
    @Test fun phoneCallTemporarilyInterruptsMediaEvenDuringNavigation() {
        val focus = AaAudioFocus()
        assertEquals(AaProtocol.AUDIO_FOCUS_GAIN, focus.request(AaProtocol.AUDIO_REQUEST_GAIN))
        assertEquals(AaProtocol.AUDIO_FOCUS_GAIN_TRANSIENT,
            focus.request(AaProtocol.AUDIO_REQUEST_GAIN_TRANSIENT_MAY_DUCK))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT, focus.updateCall(true))
        assertNull(focus.updateCall(true))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT,
            focus.request(AaProtocol.AUDIO_REQUEST_GAIN_TRANSIENT))
        assertEquals(AaProtocol.AUDIO_FOCUS_GAIN, focus.updateCall(false))
        assertNull(focus.updateCall(false))
    }

    @Test fun mediaRevokedByTheCarDoesNotResumeAfterTheCall() {
        val focus = AaAudioFocus()
        assertNull(focus.updateCall(true))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT, focus.request(AaProtocol.AUDIO_REQUEST_GAIN))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS, focus.revokeMedia())
        assertNull(focus.revokeMedia())
        assertNull(focus.updateCall(false))
    }

    @Test fun phoneReleaseCancelsPendingMediaRestoration() {
        val focus = AaAudioFocus()
        focus.request(AaProtocol.AUDIO_REQUEST_GAIN)
        focus.updateCall(true)
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS, focus.request(AaProtocol.AUDIO_REQUEST_RELEASE))
        assertNull(focus.updateCall(false))
    }

    @Test fun carMediaPauseLeavesStandaloneNavigationFocusAlone() {
        val focus = AaAudioFocus()
        focus.request(AaProtocol.AUDIO_REQUEST_GAIN_TRANSIENT)
        assertNull(focus.revokeMedia())
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT, focus.updateCall(true))
        assertNull(focus.updateCall(false))
    }

    @Test fun notificationsIdentifyChangesInitiatedByTheCar() {
        val state = AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT
        val unsolicited = ProtoReader(AaMessages.audioFocusNotification(state, unrequested = true))
        assertEquals(state, unsolicited.int(1))
        assertTrue(unsolicited.bool(2))
        val response = ProtoReader(AaMessages.audioFocusNotification(state, unrequested = false))
        assertFalse(response.bool(2))
    }
}
