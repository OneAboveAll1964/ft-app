package app.ft.projection

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coordinates seen on Toyota are in the full FT display. The mirror image is
 * letterboxed in that display AND can be downscaled on its capture Surface.
 * Compute against the actual output Surface size, not the source phone aspect
 * ratio which can be stale during an Android rotation.
 */
internal data class MirrorTouchMap(
    val carWidth: Int,
    val carHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val phoneWidth: Int,
    val phoneHeight: Int
) {
    val valid: Boolean get() = carWidth > 0 && carHeight > 0 &&
        outputWidth > 0 && outputHeight > 0 && phoneWidth > 0 && phoneHeight > 0

    fun map(x: Int, y: Int, clipToBounds: Boolean = false): Pair<Float, Float>? {
        if (!valid) return null
        val scale = min(carWidth.toDouble() / outputWidth, carHeight.toDouble() / outputHeight)
        val visibleWidth = outputWidth * scale
        val visibleHeight = outputHeight * scale
        val left = (carWidth - visibleWidth) / 2.0
        val top = (carHeight - visibleHeight) / 2.0
        if (!clipToBounds && (x < left || x >= left + visibleWidth || y < top || y >= top + visibleHeight)) return null
        return (((x - left) / visibleWidth * phoneWidth).coerceIn(0.0, (phoneWidth - 1).toDouble())).toFloat() to
            (((y - top) / visibleHeight * phoneHeight).coerceIn(0.0, (phoneHeight - 1).toDouble())).toFloat()
    }
}
