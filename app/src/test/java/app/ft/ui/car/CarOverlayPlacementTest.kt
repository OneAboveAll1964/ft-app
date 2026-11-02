package app.ft.ui.car

import app.ft.aa.AaDisplayConfig
import app.ft.aa.AaVideoLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarOverlayPlacementTest {
    @Test fun androidAutoNeverObscuresTopCornerCloseTargets() {
        assertEquals(2, CarOverlayPlacement.corner(0, aa = true))
        assertEquals(3, CarOverlayPlacement.corner(1, aa = true))
        assertEquals(2, CarOverlayPlacement.corner(2, aa = true))
        assertEquals(3, CarOverlayPlacement.corner(3, aa = true))
    }

    @Test fun mirrorAndOrdinaryViewsPreserveUserCorner() {
        for (corner in 0..3) assertEquals(corner, CarOverlayPlacement.corner(corner, aa = false))
    }

    @Test fun microphonePositionIsOnlyUsedInsideAndroidAuto() {
        assertEquals(4, CarOverlayPlacement.corner(4, aa = true))
        assertEquals(2, CarOverlayPlacement.corner(4, aa = false))
    }

    @Test fun paddedVideoLeavesBothBottomControlsFree() {
        val bounds = bounds()
        assertEquals(8f, bounds.left, 0.001f)
        assertEquals(536f, bounds.top, 0.001f)
        assertEquals(48f, bounds.size, 0.001f)
    }

    @Test fun rightRailAndAaDensityChangePlacementTogether() {
        val bounds = bounds(display = AaDisplayConfig(1920, 720, 240, false))
        assertEquals(1836f, bounds.left, 0.001f)
        assertEquals(444f, bounds.top, 0.001f)
        assertEquals(72f, bounds.size, 0.001f)
    }

    @Test fun resizedDecoderKeepsTheSamePhysicalButtonBounds() {
        val resized = bounds(layout = AaVideoLayout.forFrame(1920, 720, 960, 360))
        assertEquals(bounds(), resized)
    }

    @Test fun letterboxingOffsetsTheButtonWithTheActiveImage() {
        val bounds = bounds(width = 1000f, height = 1000f)
        assertEquals(4.166667f, bounds.left, 0.001f)
        assertEquals(591.666667f, bounds.top, 0.001f)
        assertEquals(25f, bounds.size, 0.001f)
    }

    @Test fun smallViewportKeepsTheButtonInsideTheImage() {
        val bounds = bounds(width = 60f, height = 30f)
        assertTrue(bounds.left >= 0f)
        assertTrue(bounds.top >= 0f)
        assertTrue(bounds.left + bounds.size <= 60f)
        assertTrue(bounds.top + bounds.size <= 30f)
    }

    @Test fun unavailableViewportDoesNotCreateATouchTarget() {
        assertNull(CarOverlayPlacement.aboveMicrophone(
            0f, 720f, AaVideoLayout.forFrame(1920, 720, 1920, 1080),
            AaDisplayConfig(1920, 720, 160, true)
        ))
    }

    private fun bounds(
        width: Float = 1920f,
        height: Float = 720f,
        layout: AaVideoLayout = AaVideoLayout.forFrame(1920, 720, 1920, 1080),
        display: AaDisplayConfig = AaDisplayConfig(1920, 720, 160, true)
    ) = requireNotNull(CarOverlayPlacement.aboveMicrophone(width, height, layout, display))
}
