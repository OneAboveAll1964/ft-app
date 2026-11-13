package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaTrustedCommitGateTest {
    @Test fun confirmationAloneCannotSaveTheCredential() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
    }

    @Test fun authenticatedControlMustBeOpenedAfterAuthConfirm() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
        assertTrue(gate.onAuthenticatedControl())
        assertFalse(gate.onAuthenticatedControl())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun controlCanRaceAheadOfConfirmationWithoutLosingCommit() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onAuthenticatedControl())
        assertTrue(gate.onConfirmSent())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun cancellationBeforeBothSignalsCannotStoreAKey() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
        gate.cancel()
        assertFalse(gate.onAuthenticatedControl())
        assertFalse(gate.onConfirmSent())
    }
}
