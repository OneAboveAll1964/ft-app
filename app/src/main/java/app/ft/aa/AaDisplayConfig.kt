package app.ft.aa

/** The active display settings actually sent to the AA phone for this session. */
data class AaDisplayConfig(
    val width: Int,
    val height: Int,
    val density: Int,
    val controlsLeft: Boolean
) {
    val effectiveDpi: Int get() = AaUiDensity.effective(density, width, height)
}
