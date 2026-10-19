package app.ft.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaViewportTest {
    @Test fun sideRailAndLetterboxAreOutsideThePicture() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertFalse(viewport.contains(103, 100))
        assertFalse(viewport.contains(400, 49))
        assertFalse(viewport.contains(904, 100))
        assertTrue(viewport.contains(104, 50))
        assertTrue(viewport.contains(903, 499))
    }

    @Test fun scaledPictureMapsToTheAndroidAutoCoordinates() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertEquals(0 to 0, viewport.map(104, 50, 1280, 720))
        assertEquals(640 to 360, viewport.map(504, 275, 1280, 720))
        assertEquals(640 to 360, AaViewport(0, 0, 800, 450).map(400, 225, 1280, 720))
    }

    @Test fun draggingOutsideThePictureStaysWithinTheInputRange() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertEquals(0 to 0, viewport.map(-50, -50, 1280, 720))
        assertEquals(1279 to 719, viewport.map(1000, 600, 1280, 720))
    }
}
