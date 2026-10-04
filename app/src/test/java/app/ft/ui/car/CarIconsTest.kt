package app.ft.ui.car

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Test

class CarIconsTest {
    @Test
    fun theAndroidAutoLogoParsesIntoItsTwoShapes() {
        val nodes = addPathNodes(CarIcons.ANDROID_AUTO_PATH)
        assertEquals("the frame and the road are two closed shapes", 2, nodes.count { it is PathNode.Close })
        assertEquals("the three rounded corners are arcs", 3, nodes.count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo })
        assertEquals(2, nodes.count { it is PathNode.MoveTo || it is PathNode.RelativeMoveTo })
    }

    @Test
    fun theAndroidAutoIconBuilds() {
        val icon = CarIcons.AndroidAuto
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
        assertEquals("AndroidAuto", icon.name)
    }
}
