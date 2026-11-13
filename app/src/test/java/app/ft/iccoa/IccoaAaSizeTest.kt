package app.ft.iccoa

import app.ft.aa.AaVideoLayout
import app.ft.aa.AaViewport
import org.junit.Assert.*
import org.junit.Test

class IccoaAaSizeTest {
    @Test fun xa60FitsTheSupportedFrameAndKeepsItsAspectRatio() {
        val (width, height) = IccoaAaSize.fit(2422, 1272)
        assertEquals(1920 to 1008, width to height)
        assertEquals(2422.0 / 1272, width.toDouble() / height, 0.002)
        val layout = AaVideoLayout.forFrame(width, height, 1920, 1080)
        assertEquals(width, layout.contentWidth)
        assertEquals(height, layout.contentHeight)
        assertEquals(1080f / 1008, layout.scaleY, 0.0001f)
    }

    @Test fun supportedCarSizesArePreserved() {
        for (size in listOf(800 to 480, 1280 to 720, 1920 to 1080, 1920 to 720)) {
            assertEquals(size, IccoaAaSize.fit(size.first, size.second))
        }
    }

    @Test fun tallAndUltraWideScreensFitBothLimits() {
        assertEquals(1920 to 1080, IccoaAaSize.fit(3840, 2160))
        assertEquals(540 to 1080, IccoaAaSize.fit(1080, 2160))
        assertEquals(1920 to 480, IccoaAaSize.fit(3840, 960))
    }

    @Test fun xa60TouchesFollowThePictureInsteadOfTheWholeCarDisplay() {
        // 1920x1080 AA picture fitted inside the 2422x1272 car display in build 142.
        val viewport = AaViewport(80, 0, 2261, 1272)
        assertFalse(viewport.contains(40, 600))
        assertEquals(0 to 0, viewport.map(80, 0, 2422, 1272))
        assertEquals(1210 to 636, viewport.map(1210, 636, 2422, 1272))
        // Once AA is matched to 1920x1008, the same car centre maps into that input range.
        assertEquals(960 to 504, AaViewport(0, 0, 2422, 1272).map(1211, 636, 1920, 1008))
        assertEquals(1919 to 1007, AaViewport(0, 0, 2422, 1272).map(2421, 1271, 1920, 1008))
    }
}
