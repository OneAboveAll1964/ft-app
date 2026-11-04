package app.ft.aa

import app.ft.aa.AaStarterAttempt.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AaStarterAttemptTest {
    @Test fun repeatedEventsDoNotToggleOverflowOrStartTwice() {
        val attempt = AaStarterAttempt(8000)
        assertEquals(Action.OPEN_MENU, attempt.next(0, false, true, overflow = true))
        assertEquals(Action.WAIT, attempt.next(1300, false, true, overflow = true))
        assertEquals(Action.START, attempt.next(1500, false, true, start = true))
        assertEquals(Action.WAIT, attempt.next(1600, false, true, start = true, overflow = true))
        assertEquals(Action.ON, attempt.next(1800, false, true, serverOn = true))
    }

    @Test fun startClickNeedsConfirmation() {
        val attempt = AaStarterAttempt(8000)
        assertEquals(Action.START, attempt.next(0, false, true, start = true))
        assertEquals(Action.WAIT, attempt.next(7999, false, true))
        assertEquals(Action.FAILED, attempt.next(8000, false, true))
    }

    @Test fun stopItemIsNeverToggledEvenIfStartAlsoExists() {
        assertEquals(Action.ON, AaStarterAttempt(8000).next(0, false, true, start = true, stop = true))
    }

    @Test fun startupMayWaitForAaButNeverClicksAnotherPackage() {
        val attempt = AaStarterAttempt(8000)
        assertEquals(Action.WAIT, attempt.next(0, false, false, start = true, overflow = true))
        assertEquals(Action.START, attempt.next(100, false, true, start = true))
    }

    @Test fun leavingAaAbortsEvenIfServerIsNowOn() {
        val attempt = AaStarterAttempt(8000)
        attempt.next(0, false, true)
        assertEquals(Action.FAILED, attempt.next(100, false, false, serverOn = true, start = true))
    }

    @Test fun transientMissingRootDoesNotCauseClicksOrFalseFailure() {
        val attempt = AaStarterAttempt(8000)
        attempt.next(0, false, true)
        assertEquals(Action.WAIT, attempt.next(100, false, null, start = true))
        assertEquals(Action.START, attempt.next(200, false, true, start = true))
    }

    @Test fun lockedPhoneCannotClickOrReportSuccess() {
        assertEquals(Action.LOCKED, AaStarterAttempt(8000).next(0, true, true, serverOn = true, start = true))
    }

    @Test fun noEventsOrAaWindowStillEndsAtDeadline() {
        val attempt = AaStarterAttempt(8000)
        assertEquals(Action.WAIT, attempt.next(7999, false, null))
        assertEquals(Action.FAILED, attempt.next(8000, false, null))
    }

    @Test fun expiredRequestCannotClickOrAcceptLateSuccess() {
        assertEquals(Action.FAILED, AaStarterAttempt(8000).next(8001, false, true, serverOn = true, start = true, stop = true))
    }
}
