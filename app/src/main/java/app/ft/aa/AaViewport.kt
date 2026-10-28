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
