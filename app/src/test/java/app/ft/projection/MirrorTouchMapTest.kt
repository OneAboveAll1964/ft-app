package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MirrorTouchMapTest {
    @Test fun portraitCenterAndCornersUseActualScaledOutput() {
        val map = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        val center = map.map(1211, 636)!!
        assertEquals(540f, center.first, 1.5f)
        assertEquals(1170f, center.second, 1.5f)
        assertNull(map.map(500, 636))
        assertNull(map.map(1900, 636))
        val top = map.map(1211, 0)!!
        val bottom = map.map(1211, 1271)!!
        assertEquals(0f, top.second, 1f)
        assertEquals(2338f, bottom.second, 3f)
    }

    @Test fun landscapeLetterboxAndRotationRecomputesGeometry() {
        val landscape = MirrorTouchMap(2422, 1272, 2340, 1080, 2340, 1080)
        val center = landscape.map(1211, 636)!!
        assertEquals(1170f, center.first, 1f)
        assertEquals(540f, center.second, 1f)
        assertNull(landscape.map(1211, 30))
        val portrait = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        assertNotNull(portrait.map(1211, 30))
        assertNull(portrait.map(600, 636))
    }

    @Test fun releaseOutsideImageIsClampedInsteadOfDroppingGesture() {
        val map = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        assertNull(map.map(2300, 640))
        val move = map.map(2300, 640, clipToBounds = true)!!
        assertEquals(1079f, move.first, 0.1f)
        assertEquals(1177f, move.second, 3f)
    }

    @Test fun uninitializedCaptureDoesNotInventCoordinates() {
        assertNull(MirrorTouchMap(2422, 1272, 0, 0, 1080, 2340).map(1200, 600))
    }
}
