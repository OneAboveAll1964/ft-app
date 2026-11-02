package app.ft.ui.car

import org.junit.Assert.assertEquals
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
}
