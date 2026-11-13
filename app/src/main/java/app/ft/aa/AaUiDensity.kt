package app.ft.aa

import kotlin.math.min

/**
 * UI icon/label sizing is negotiated with the Android Auto phone as DPI.
 * Do not crop/scale the received video or alter the touch coordinate mapping.
 */
internal object AaUiDensity {
    const val LEGACY_DPI = 160
    const val LARGE_DPI = 240
    private const val MIN_WIDTH_DP = 748
    private const val MIN_HEIGHT_DP = 450

    /** One-time FT166 -> FT167 user-requested larger UI; preserve other custom DPI. */
    fun migrate(previousDpi: Int): Int =
        if (previousDpi == LEGACY_DPI) LARGE_DPI else previousDpi

    /**
     * Avoid advertising an unreadably narrow logical canvas for low-resolution
     * Android Auto displays; standard 1920x1008 and 1280x720 retain 240 DPI.
     */
    fun effective(requestedDpi: Int, widthPx: Int, heightPx: Int): Int {
        val requested = requestedDpi.coerceIn(120, 480)
        if (widthPx <= 0 || heightPx <= 0) return LEGACY_DPI
        val widthLimit = widthPx.toLong() * 160L / MIN_WIDTH_DP
        val heightLimit = heightPx.toLong() * 160L / MIN_HEIGHT_DP
        val safeLimit = min(widthLimit, heightLimit).coerceAtLeast(120L).toInt()
        return requested.coerceAtMost(safeLimit)
    }
}
