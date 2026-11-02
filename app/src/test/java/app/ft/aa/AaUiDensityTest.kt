package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaUiDensityTest {
    @Test fun legacy160Becomes240WhileCustomValuesArePreserved() {
        assertEquals(240, AaUiDensity.migrate(160))
        assertEquals(200, AaUiDensity.migrate(200))
        assertEquals(240, AaUiDensity.migrate(240))
    }

    @Test fun userWideXa60ScreenGetsOneAndHalfSizeWithoutImageCropping() {
        assertEquals(240, AaUiDensity.effective(240, 1920, 1008))
        assertEquals(240, AaUiDensity.effective(240, 1280, 720))
    }

    @Test fun narrowScreensDoNotLoseMostOfTheInteractiveCanvas() {
        assertEquals(170, AaUiDensity.effective(240, 800, 480))
        assertEquals(160, AaUiDensity.effective(160, 800, 480))
    }

    @Test fun invalidOrExtremeDpiDoesNotOverflowProto() {
        assertEquals(160, AaUiDensity.effective(240, 0, 1008))
        assertEquals(120, AaUiDensity.effective(-5, 1920, 1008))
        assertTrue(AaUiDensity.effective(Int.MAX_VALUE, 1920, 1008) <= 480)
    }
}
