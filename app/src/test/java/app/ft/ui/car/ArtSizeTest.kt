package app.ft.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtSizeTest {
    @Test
    fun songRowsAskForSmallPictures() {
        assertEquals(96, artSize(78))
        assertEquals(128, artSize(108))
    }

    @Test
    fun picturesWithoutAFixedSizeGetAMiddleSize() = assertEquals(320, artSize(null))

    @Test
    fun bigPicturesStopAtTheLargestSize() = assertEquals(512, artSize(900))
}
