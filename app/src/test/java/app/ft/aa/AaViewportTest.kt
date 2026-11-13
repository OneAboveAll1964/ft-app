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

    @Test fun gestureFreezesViewportAcrossRotationAndResolutionChange() {
        val gesture = AaTouchGestureViewport()
        val landscape = AaViewport(100, 40, 800, 450)
        val portrait = AaViewport(40, 100, 450, 800)
        assertEquals(640 to 360, gesture.map(0, 500, 265, landscape, 1280, 720))
        // The UI rotates mid-gesture. MOVE/UP still use DOWN's original
        // physical rectangle and negotiated input size.
        assertEquals(800 to 400, gesture.map(2, 600, 290, portrait, 720, 1280))
        assertEquals(960 to 480, gesture.map(1, 700, 340, portrait, 720, 1280))
        assertEquals(null, gesture.map(2, 700, 340, portrait, 720, 1280))
        // A fresh DOWN adopts the new portrait viewport and AA dimensions.
        assertEquals(360 to 640, gesture.map(0, 265, 500, portrait, 720, 1280))
        assertEquals(360 to 640, gesture.map(1, 265, 500, portrait, 720, 1280))
    }

    @Test fun gestureRejectsOutsideDownButClampsBottomRowDragRelease() {
        for ((w, h) in listOf(800 to 480, 1280 to 720, 1920 to 1008, 2560 to 1440)) {
            val gesture = AaTouchGestureViewport()
            val bounds = AaViewport(30, 20, w, h)
            assertEquals(null, gesture.map(0, 29, 21, bounds, w, h))
            assertEquals(null, gesture.map(1, 30, 21, bounds, w, h))
            assertEquals((w - 1) to (h - 1),
                gesture.map(0, 30 + w - 1, 20 + h - 1, bounds, w, h))
            assertEquals((w - 1) to (h - 1),
                gesture.map(2, 30 + w + 200, 20 + h + 200, null, 1, 1))
            assertEquals((w - 1) to (h - 1),
                gesture.map(1, 30 + w + 200, 20 + h + 200, null, 1, 1))
            assertEquals(null, gesture.map(1, 30, 20, bounds, w, h))
        }
    }

    @Test fun routeSwitchInvalidatesCapturedAaGestureUntilNewDown() {
        val gesture = AaTouchGestureViewport()
        val viewport = AaViewport(100, 40, 800, 450)
        val changed = AaViewport(40, 100, 450, 800)

        // DOWN was routed to AA before the user switched to FT/mirroring.
        assertEquals(640 to 360, gesture.map(0, 500, 265, viewport, 1280, 720))
        gesture.reset() // CarLifeService.routeTouch: FT zone or non-AA route.
        assertEquals(null, gesture.map(2, 600, 290, changed, 720, 1280))
        assertEquals(null, gesture.map(1, 700, 340, changed, 720, 1280))

        // A new AA DOWN starts a new gesture in the current geometry.
        assertEquals(360 to 640, gesture.map(0, 265, 500, changed, 720, 1280))
        assertEquals(360 to 640, gesture.map(2, 265, 500, changed, 720, 1280))
        assertEquals(360 to 640, gesture.map(1, 265, 500, changed, 720, 1280))
        assertEquals(null, gesture.map(2, 265, 500, changed, 720, 1280))

        // FT-zone selection is a separate early-return path and must reset too.
        assertEquals(640 to 360, gesture.map(0, 500, 265, viewport, 1280, 720))
        gesture.reset()
        assertEquals(null, gesture.map(1, 500, 265, viewport, 1280, 720))
    }


    @Test fun cancelledOrUnknownAaActionInvalidatesDownBeforeLaterMoveAndUp() {
        val viewport = AaViewport(100, 40, 800, 450)
        for (invalidAction in listOf(-1, 3, 4, 99)) {
            val gesture = AaTouchGestureViewport()
            assertEquals(640 to 360, gesture.map(0, 500, 265, viewport, 1280, 720))
            assertEquals(null, gesture.map(invalidAction, 500, 265, viewport, 1280, 720))
            assertEquals(null, gesture.map(2, 600, 290, viewport, 1280, 720))
            assertEquals(null, gesture.map(1, 600, 290, viewport, 1280, 720))
            // The next valid DOWN is a fresh gesture, not a continuation.
            assertEquals(800 to 400, gesture.map(0, 600, 290, viewport, 1280, 720))
            assertEquals(800 to 400, gesture.map(1, 600, 290, viewport, 1280, 720))
        }
    }

    @Test fun routeSwitchWithoutIntermediateTouchMustDiscardStaleDown() {
        val gesture = AaTouchGestureViewport()
        val original = AaViewport(100, 40, 800, 450)
        val afterReturn = AaViewport(40, 100, 450, 800)
        assertEquals(640 to 360, gesture.map(0, 500, 265, original, 1280, 720))
        // CarLifeService.aaOverlay(false) is called even when FT receives no
        // touch event; mirrorStart() also invalidates the captured AA gesture.
        gesture.reset()
        // AA becomes active again without any intermediate FT/mirror touch.
        assertEquals(null, gesture.map(2, 265, 500, afterReturn, 720, 1280))
        assertEquals(null, gesture.map(1, 265, 500, afterReturn, 720, 1280))
        // A genuine new AA DOWN still captures the current geometry.
        assertEquals(360 to 640, gesture.map(0, 265, 500, afterReturn, 720, 1280))
        assertEquals(360 to 640, gesture.map(2, 265, 500, afterReturn, 720, 1280))
        assertEquals(360 to 640, gesture.map(1, 265, 500, afterReturn, 720, 1280))
    }
}
