package app.ft.aa

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaPreparationRetryTest {
    @After fun reset() { AaKeep.preparing() }

    @Test fun failedAttemptDoesNotSuppressAnotherVehicleOrDisabledOption() {
        for (result in AaSwitch.Result.entries.filter { it.failedAttempt }) {
            AaKeep.prepared("car-a", result)
            assertTrue(AaKeep.preparationFailed("car-a", true))
            assertFalse(AaKeep.preparationFailed("car-b", true))
            assertFalse(AaKeep.preparationFailed("car-a", false))
            AaKeep.preparing()
            assertFalse(AaKeep.preparationFailed("car-a", true))
        }
    }

    @Test fun unlockAndHelperEnableCanRecoverWithoutTenMinuteWait() {
        for (result in listOf(AaSwitch.Result.LOCKED, AaSwitch.Result.NO_HELPER, AaSwitch.Result.ON)) {
            AaKeep.prepared("car-a", result)
            assertFalse(AaKeep.preparationFailed("car-a", true))
        }
    }
}
