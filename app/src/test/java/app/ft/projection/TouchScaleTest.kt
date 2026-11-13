package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchScaleTest {
    @Test fun increasingLeftDriftAtRightIsCorrectedByScaleNotConstantShift() {
        val width = 2400
        // On the HU, taps at 20% and 80% arrive as 16% and 68%.
        val scale = TouchScale.fromTwoTargets(width, 384, 1632)!!
        assertEquals(480, scale.map(384, width))
        assertEquals(1920, scale.map(1632, width))
        assertEquals(0, scale.map(-1000, width))
        assertEquals(2399, scale.map(3000, width))
    }

    @Test fun identityHasNoUnnecessaryCorrection() {
        val scale = TouchScale.fromTwoTargets(2400, 480, 1920)!!
        assertEquals(1200, scale.map(1200, 2400))
    }

    @Test fun rejectsSwappedOrTooClosePoints() {
        assertNull(TouchScale.fromTwoTargets(2400, 2000, 400))
        assertNull(TouchScale.fromTwoTargets(2400, 1000, 1020))
    }
}
