package app.ft.supercast

import app.ft.supercast.PhoneScreenControl.Result.*
import org.junit.Assert.*
import org.junit.Test

class PhoneScreenControlTest {
    private class Panel : PhoneScreenControl.Backend {
        var awakeResult: Boolean? = true
        var manageResult: Boolean? = true
        var restoreResult: Boolean? = true
        var releaseResult: Boolean? = true
        var held = false
        var dark = false
        val calls = mutableListOf<String>()
        override fun awake(on: Boolean): Boolean? {
            calls += "awake:$on"
            val result = if (on) awakeResult else releaseResult
            if (result == true) held = on
            return result
        }
        override fun manage(darken: Boolean): Boolean? {
            calls += "manage:$darken"
            if (manageResult == true) dark = darken
            return manageResult
        }
        override fun restore(): Boolean? {
            calls += "restore"
            if (restoreResult == true) dark = false
            return restoreResult
        }
    }

    @Test fun aaDisconnectRestoresPanelAndReleasesWakeLock() {
        val panel = Panel()
        val control = PhoneScreenControl(panel)
        assertEquals(ACTIVE, control.apply(true, true))
        assertTrue(panel.dark)
        assertTrue(panel.held)
        assertEquals(IDLE, control.apply(null, true))
        assertFalse(panel.dark)
        assertFalse(panel.held)
        assertEquals(listOf("awake:true", "manage:true", "restore", "awake:false"), panel.calls)
    }

    @Test fun switchingFromDarkAaToLitPanesRestoresPhysicalPanel() {
        val panel = Panel()
        val control = PhoneScreenControl(panel)
        control.apply(true, true)
        assertEquals(ACTIVE, control.apply(false, true))
        assertFalse(panel.dark)
        assertTrue(panel.held)
        control.apply(null, true)
        assertFalse(panel.held)
    }

    @Test fun refusedPanelDoesNotLeaveBrightWakeLockHeld() {
        val panel = Panel().apply { manageResult = false }
        val control = PhoneScreenControl(panel)
        assertEquals(REFUSED, control.apply(true, true))
        assertFalse(panel.held)
        assertTrue("restore attempted even after rejected screen command", panel.calls.contains("restore"))
        panel.manageResult = true
        assertEquals(ACTIVE, control.apply(true, true))
    }

    @Test fun unavailableHelperDoesNotAttemptPanelControl() {
        val panel = Panel()
        val control = PhoneScreenControl(panel)
        assertEquals(UNAVAILABLE, control.apply(true, false))
        assertTrue(panel.calls.isEmpty())
        assertEquals(ACTIVE, control.apply(true, true))
    }

    @Test fun awakeFailureNeverTurnsPanelOff() {
        val panel = Panel().apply { awakeResult = false }
        assertEquals(REFUSED, PhoneScreenControl(panel).apply(true, true))
        assertFalse(panel.held)
        assertFalse(panel.dark)
        assertEquals(listOf("awake:true", "awake:false"), panel.calls)
    }

    @Test fun lostHelperIsReportedAndNewHelperCanAcquireScreen() {
        val panel = Panel()
        val control = PhoneScreenControl(panel)
        control.apply(true, true)
        panel.calls.clear()
        assertEquals(UNAVAILABLE, control.apply(true, false))
        assertTrue(panel.calls.isEmpty())
        assertEquals(ACTIVE, control.apply(true, true))
        assertEquals(listOf("awake:true", "manage:true"), panel.calls)
    }

    @Test fun failedRestoreStillReleasesWakeLockAndReportsFailure() {
        val panel = Panel().apply { restoreResult = false }
        val control = PhoneScreenControl(panel)
        control.apply(true, true)
        assertEquals(RESTORE_FAILED, control.apply(null, true))
        assertFalse(panel.held)
    }

    @Test fun rejectedRestoreIsRetriedUntilConfirmed() {
        val panel = Panel().apply { restoreResult = false }
        val control = PhoneScreenControl(panel)
        control.apply(true, true)
        assertEquals(RESTORE_FAILED, control.apply(null, true))
        assertEquals(RESTORE_FAILED, control.apply(null, true))
        panel.restoreResult = true
        assertEquals(IDLE, control.apply(null, true))
        assertFalse(panel.dark)
        assertEquals(3, panel.calls.count { it == "restore" })
    }

    @Test fun failedWakeReleaseIsRetriedWithoutAcquiringAgain() {
        val panel = Panel().apply { releaseResult = false }
        val control = PhoneScreenControl(panel)
        control.apply(true, true)
        assertEquals(RESTORE_FAILED, control.apply(null, true))
        assertTrue(panel.held)
        assertEquals(RESTORE_FAILED, control.apply(true, true))
        assertEquals(1, panel.calls.count { it == "awake:true" })
        panel.releaseResult = true
        assertEquals(IDLE, control.apply(null, true))
        assertFalse(panel.held)
    }
}
