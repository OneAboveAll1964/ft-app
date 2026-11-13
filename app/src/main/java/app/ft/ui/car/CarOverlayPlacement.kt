package app.ft.ui.car

import app.ft.aa.AaDisplayConfig
import app.ft.aa.AaVideoLayout
import kotlin.math.min

internal object CarOverlayPlacement {
    const val ABOVE_MICROPHONE = 4

    /** Keep AA close targets free; use bottom left outside AA for the rail-only option. */
    fun corner(requested: Int, aa: Boolean): Int {
        val bounded = requested.coerceIn(0, ABOVE_MICROPHONE)
        if (bounded == ABOVE_MICROPHONE) return if (aa) ABOVE_MICROPHONE else 2
        return if (aa && bounded < 2) bounded + 2 else bounded
    }

    data class ButtonBounds(val left: Float, val top: Float, val size: Float)

    /**
     * Opt-in placement for a vertical AA rail, above its launcher and microphone slots.
     * AA exposes a video image, not bounds of individual controls. These rail metrics
     * are a layout estimate; they cannot detect a different layout chosen by the phone.
     * Convert from negotiated AA pixels through the displayed active image, including
     * decoder resizing and fit-center margins, rather than using FT's Compose density.
     */
    fun aboveMicrophone(
        width: Float, height: Float, layout: AaVideoLayout, display: AaDisplayConfig
    ): ButtonBounds? {
        if (width <= 0 || height <= 0 || layout.contentWidth <= 0 || layout.contentHeight <= 0 ||
            display.width <= 0 || display.height <= 0) return null
        val fit = min(width / layout.contentWidth, height / layout.contentHeight)
        val imageWidth = layout.contentWidth * fit
        val imageHeight = layout.contentHeight * fit
        val imageLeft = (width - imageWidth) / 2
        val imageTop = (height - imageHeight) / 2
        val dpX = display.effectiveDpi / 160f * imageWidth / display.width
        val dpY = display.effectiveDpi / 160f * imageHeight / display.height
        val size = min(48 * min(dpX, dpY), min(imageWidth, imageHeight))
        val centerX = if (display.controlsLeft) 32 * dpX else imageWidth - 32 * dpX
        // Reserve both bottom controls; the mic is above the launcher in this layout.
        val bottom = 2 * 64 * dpY + 8 * dpY
        return ButtonBounds(
            imageLeft + (centerX - size / 2).coerceIn(0f, imageWidth - size),
            imageTop + (imageHeight - bottom - size).coerceIn(0f, imageHeight - size),
            size
        )
    }
}
