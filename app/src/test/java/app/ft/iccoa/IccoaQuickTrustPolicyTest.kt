package app.ft.iccoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaQuickTrustPolicyTest {
    @Test fun trustedScopeDoesNotDependOnBlankOrRotatingBluetoothName() {
        assertEquals("owner-approved-primary-car-v1", IccoaQuickTrustPolicy.PRIMARY_TRUST_SCOPE)
        assertTrue(IccoaQuickTrustPolicy.PRIMARY_TRUST_SCOPE.isNotBlank())
    }

    @Test fun rememberedApprovedCarMayTryQuick() {
        assertTrue(IccoaQuickTrustPolicy.mayAttemptQuick(
            remembered = true, explicitlySelected = false, forceCurrentPin = false))
    }

    @Test fun explicitlySelectedReceiverMayProbePinnedKey() {
        // Actual authentication still needs a locally stored pinned car EC key
        // and strict verification of the car's signed ephemeral agreement.
        assertTrue(IccoaQuickTrustPolicy.mayAttemptQuick(
            remembered = false, explicitlySelected = true, forceCurrentPin = false))
    }

    @Test fun unknownUnselectedReceiverNeverTriesQuick() {
        assertFalse(IccoaQuickTrustPolicy.mayAttemptQuick(
            remembered = false, explicitlySelected = false, forceCurrentPin = false))
    }

    @Test fun ownerForcedNormalAndOemQuickFallbackNeverTryQuickAgain() {
        assertFalse(IccoaQuickTrustPolicy.mayAttemptQuick(
            remembered = true, explicitlySelected = true, forceCurrentPin = true))
    }
}
