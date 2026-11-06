package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class AssistantPanelControllerTest {
    private val panel = AssistantPanelController()
    private val car = Any()

    @Test fun voiceKeyTogglesTheSameCarPanel() {
        panel.toggle(car)
        assertTrue(panel.isOpen(car))
        panel.toggle(car)
        assertNull(panel.state.value)
    }

    @Test fun oldViewDisposalCannotDismissAReplacement() {
        panel.toggle(car)
        val old = panel.state.value!!
        panel.toggle(car)
        panel.toggle(car)
        val fresh = panel.state.value!!
        panel.dismiss(old)
        assertTrue(panel.isCurrent(fresh))
        assertNotEquals(old.paneName, fresh.paneName)
    }

    @Test fun disconnectOfOldBackendCannotCloseNewBackendPanel() {
        val other = Any()
        panel.toggle(car)
        panel.toggle(other)
        assertFalse(panel.close(car))
        assertTrue(panel.isOpen(other))
        assertTrue(panel.close(other))
    }

    @Test fun closingPanelMidGestureKeepsItsRemainingTouchAwayFromAa() {
        panel.toggle(car)
        assertTrue(panel.routesTouch(car, 0))
        panel.close(car)
        assertTrue(panel.routesTouch(car, 2))
        assertTrue(panel.routesTouch(car, 1))
        assertFalse(panel.routesTouch(car, 0))
    }

    @Test fun openingPanelMidGestureDoesNotSplitAnAaGesture() {
        assertFalse(panel.routesTouch(car, 0))
        panel.toggle(car)
        assertFalse(panel.routesTouch(car, 1))
        assertTrue(panel.routesTouch(car, 0))
        assertTrue(panel.routesTouch(car, 3))
        panel.close(car)
        assertFalse(panel.routesTouch(car, 2))
    }

    @Test fun touchesFromOtherBackendAreNotClaimed() {
        panel.toggle(car)
        assertFalse(panel.routesTouch(Any(), 0))
    }
}
