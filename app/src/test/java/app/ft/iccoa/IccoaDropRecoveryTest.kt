package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaDropRecoveryTest {
    private fun lost(guard: IccoaDropRecovery, connected: Boolean = false, transport: Boolean = true) =
        guard.onFailure(connected, autoConnectEnabled = true, retryableTransportFailure = transport)

    @Test fun initialRememberedCarAutoConnectAlsoGetsThreeTransportAttempts() {
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        assertEquals(2, lost(guard))
        assertEquals(2, guard.onInitialRememberedCarAutoSelect())
        assertEquals(3, lost(guard))
        assertNull(lost(guard))
        assertFalse(guard.active)
    }

    @Test fun initialAutoConnectStillStopsOnPinPromptAndAuthRejection() {
        val prompt = IccoaDropRecovery()
        assertEquals(1, prompt.onInitialRememberedCarAutoSelect())
        prompt.onPinRequired()
        assertNull(lost(prompt))
        val rejected = IccoaDropRecovery()
        assertEquals(1, rejected.onInitialRememberedCarAutoSelect())
        assertNull(lost(rejected, transport = false))
        assertFalse(rejected.active)
    }

    @Test fun cachedPinGattQueueFailureCanRetryTransportButNeverFourthTime() {
        val guard = IccoaDropRecovery()
        // Initial remembered-car connection counted before the cached PIN is sent.
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        // A local GATT queue failure or confirmed ATT write failure cannot
        // imply credential rejection; reconnect may retry after rediscovery.
        assertEquals(2, lost(guard, transport = true))
        assertEquals(3, lost(guard, transport = true))
        assertNull(lost(guard, transport = true))
        assertFalse(guard.active)
    }

    @Test fun ambiguousSavedPinSilenceOrRejectionRequiresManualInput() {
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        assertEquals(2, lost(guard, transport = true))
        // Silence after a transmitted PIN may be OEM rejection, so it is
        // intentionally NOT a typed transport failure and prompts the owner.
        assertNull(lost(guard, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard, transport = true))
    }

    @Test fun firstEstablishedLossStartsAttemptOneOfThree() {
        val guard = IccoaDropRecovery()
        assertEquals(3, IccoaDropRecovery.MAX_ATTEMPTS)
        assertNull(lost(guard)) // No prior established session.
        assertEquals(1, lost(guard, connected = true))
        assertTrue(guard.active)
    }

    @Test fun twoMoreTransportFailuresConsumeOnlyTwoRemainingSlots() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
        assertEquals(3, lost(guard))
        assertNull(lost(guard)) // Strictly never a fourth reconnect.
        assertFalse(guard.active)
        assertNull(lost(guard)) // Exhausted episode stays stopped.
    }

    @Test fun authenticationOrPinFailureStopsRecoveryWithoutRetry() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertNull(lost(guard, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard))
    }

    @Test fun explicitPinPromptCancelsRemainingAttempts() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        guard.onPinRequired()
        assertFalse(guard.active)
        assertNull(lost(guard))
    }

    @Test fun disabledAutoConnectCannotStartNorContinueRecovery() {
        val guard = IccoaDropRecovery()
        assertNull(guard.onFailure(true, false, true))
        assertFalse(guard.active)
        assertEquals(1, lost(guard, connected = true))
        assertNull(guard.onFailure(false, false, true))
        assertNull(lost(guard))
    }

    @Test fun successfulReconnectEndsEpisodeAndNextRealDropStartsFreshBudget() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
        guard.onConnected()
        assertNull(lost(guard)) // New failure before a connection does not restart.
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
    }

    @Test fun manuallySelectedCarOrScanEndsOldRecoveryEpisode() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        guard.onExplicitOwnerRequest()
        assertFalse(guard.active)
        assertNull(lost(guard))
        assertEquals(1, lost(guard, connected = true))
    }

    @Test fun failureNotClassifiedAsTransportNeverInitiatesAutoReconnect() {
        val guard = IccoaDropRecovery()
        assertNull(lost(guard, connected = true, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard))
    }
}
