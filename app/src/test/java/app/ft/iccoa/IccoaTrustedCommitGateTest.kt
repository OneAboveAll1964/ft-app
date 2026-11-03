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
        assertTrue(gate.onControlReady())
        assertFalse(gate.onControlReady())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun controlCanRaceAheadOfConfirmationWithoutLosingCommit() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onControlReady())
        assertTrue(gate.onConfirmSent())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun cancellationBeforeBothSignalsCannotStoreAPin() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
        gate.cancel()
        assertFalse(gate.onControlReady())
        assertFalse(gate.onConfirmSent())
    }
}
