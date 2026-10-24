package app.ft.aa

/** AA pads non-standard displays to an enumerated video size with equal margins. */
data class AaVideoLayout(
    val frameWidth: Int,
    val frameHeight: Int,
    val contentWidth: Int,
    val contentHeight: Int
) {
    val scaleX: Float get() = frameWidth.toFloat() / contentWidth
    val scaleY: Float get() = frameHeight.toFloat() / contentHeight

    companion object {
        fun forFrame(width: Int, height: Int, frameWidth: Int, frameHeight: Int): AaVideoLayout {
            val (standardWidth, standardHeight) = AaProtocol.standardSize(width, height)
            // Some decoders already return the active image. Only remove the margins
            // when the output still has the full size we advertised to Android Auto.
            val padded = frameWidth == standardWidth && frameHeight == standardHeight
            return AaVideoLayout(
                frameWidth, frameHeight,
                if (padded) width.coerceIn(1, frameWidth) else frameWidth,
                if (padded) height.coerceIn(1, frameHeight) else frameHeight
            )
        }
    }
}
