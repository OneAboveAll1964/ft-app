package app.ft.aa

data class AaViewport(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun contains(x: Int, y: Int): Boolean =
        width > 0 && height > 0 && x >= left && x < left + width && y >= top && y < top + height

    fun map(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> =
        (((x - left).toLong() * targetWidth / width.coerceAtLeast(1)).toInt()
            .coerceIn(0, (targetWidth - 1).coerceAtLeast(0))) to
        (((y - top).toLong() * targetHeight / height.coerceAtLeast(1)).toInt()
            .coerceIn(0, (targetHeight - 1).coerceAtLeast(0)))
}

object AaTouchViewport {
    @Volatile var bounds: AaViewport? = null
}

/**
 * Freeze both physical viewport and negotiated AA input dimensions at DOWN.
 * A rotation or layout remeasurement during a drag must not remap MOVE/UP
 * into a different coordinate space. New geometry applies to the next DOWN.
 */
internal class AaTouchGestureViewport {
    private var active: AaViewport? = null
    private var targetWidth = 0
    private var targetHeight = 0

    fun map(
        action: Int, x: Int, y: Int,
        viewport: AaViewport?, width: Int, height: Int
    ): Pair<Int, Int>? {
        if (action !in 0..2) {
            reset()
            return null
        }
        if (action == 0) {
            active = null
            if (viewport == null || !viewport.contains(x, y) || width <= 0 || height <= 0)
                return null
            active = viewport.copy()
            targetWidth = width
            targetHeight = height
        }
        val captured = active ?: return null
        val mapped = captured.map(x, y, targetWidth, targetHeight)
        if (action == 1) active = null
        return mapped
    }

    fun reset() { active = null }
}
