package app.ft.projection

import android.graphics.Rect
import java.util.concurrent.ConcurrentHashMap

object CarTouchZones {
    private val zones = ConcurrentHashMap<String, Rect>()

    fun put(key: String, rect: Rect) {
        zones[key] = rect
    }

    fun remove(key: String) {
        zones.remove(key)
    }

    fun hit(x: Int, y: Int): Boolean = zones.values.any { it.contains(x, y) }
}
