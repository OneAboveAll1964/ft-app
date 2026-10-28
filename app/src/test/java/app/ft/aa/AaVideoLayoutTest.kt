package app.ft.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class AaVideoLayoutTest {
    @Test fun wideCarUsesTheActive720RowsOfThe1080pFrame() {
        val layout = AaVideoLayout.forFrame(1920, 720, 1920, 1080)
        assertEquals(1920, layout.contentWidth)
        assertEquals(720, layout.contentHeight)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(1.5f, layout.scaleY, 0.0001f)
        assertEquals(0f, (180f * 720 / 1080 - 360) * layout.scaleY + 360, 0.0001f)
        assertEquals(720f, (900f * 720 / 1080 - 360) * layout.scaleY + 360, 0.0001f)
        val touch = AaViewport(0, 0, layout.contentWidth, layout.contentHeight)
        assertEquals(960 to 360, touch.map(960, 360, 1920, 720))
        assertEquals(1919 to 719, touch.map(1919, 719, 1920, 720))
    }

    @Test fun alreadyCroppedOutputIsNotCroppedTwice() {
        val layout = AaVideoLayout.forFrame(1920, 720, 1920, 720)
        assertEquals(1920, layout.contentWidth)
        assertEquals(720, layout.contentHeight)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(1f, layout.scaleY, 0.0001f)
    }

    @Test fun horizontalAndVerticalMarginsAreRemovedTogether() {
        val layout = AaVideoLayout.forFrame(1000, 600, 1280, 720)
        assertEquals(1000, layout.contentWidth)
        assertEquals(600, layout.contentHeight)
        assertEquals(1.28f, layout.scaleX, 0.0001f)
        assertEquals(1.2f, layout.scaleY, 0.0001f)
    }

    @Test fun standardResolutionsKeepTheirWholeImage() {
        for ((width, height) in listOf(800 to 480, 1280 to 720, 1920 to 1080)) {
            val layout = AaVideoLayout.forFrame(width, height, width, height)
            assertEquals(1f, layout.scaleX, 0.0001f)
            assertEquals(1f, layout.scaleY, 0.0001f)
        }
    }

    @Test fun unexpectedOutputKeepsItsWholeImage() {
        val layout = AaVideoLayout.forFrame(1920, 720, 1280, 720)
        assertEquals(1280, layout.contentWidth)
        assertEquals(720, layout.contentHeight)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(1f, layout.scaleY, 0.0001f)
    }
}
