package app.ft.projection

import android.content.Context
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Measured content rectangle on the HU, not guessed from the phone resolution. */
internal data class MirrorTouchBounds(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val frameWidth: Int, val frameHeight: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val portrait: Boolean get() = frameHeight > frameWidth
    fun validFor(frame: MirrorFrame): Boolean =
        width > 100 && height > 100 && frame.width == frameWidth &&
            frame.height == frameHeight && frame.width > 0 && frame.height > 0
    fun inside(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    fun normalized(x: Int, y: Int): Pair<Int, Int> =
        ((x - left).toDouble() * 1000 / width).roundToInt().coerceIn(0, 999) to
            ((y - top).toDouble() * 1000 / height).roundToInt().coerceIn(0, 999)
}

internal object MirrorTouchViewport {
    @Volatile var bounds: MirrorTouchBounds? = null
        private set
    fun update(left: Int, top: Int, right: Int, bottom: Int, frameWidth: Int, frameHeight: Int) {
        bounds = MirrorTouchBounds(left, top, right, bottom, frameWidth, frameHeight)
    }
    fun current(frame: MirrorFrame): MirrorTouchBounds? = bounds?.takeIf { it.validFor(frame) }
    fun clear() { bounds = null }
}

internal data class MirrorCalibrationState(val step: Int = 0, val portrait: Boolean = true)

/**
 * Mirror-specific calibration is deliberately separate from the full-width FT UI.
 * Values are normalized to the visible phone-image rectangle, so rotating/resizing
 * the phone cannot apply landscape pixel dimensions to a portrait viewport.
 * Also keeps independent per-orientation profiles on the same Toyota HU.
 */
internal object MirrorTouchCalibration {
    private const val UNIT = 1000
    private const val PREFS = "ft-iccoa-touch"
    private val _state = MutableStateFlow(MirrorCalibrationState())
    val state: StateFlow<MirrorCalibrationState> = _state
    private val taps = mutableListOf<TouchPoint>()
    private var swallowUp = false

    fun begin(bounds: MirrorTouchBounds) {
        taps.clear()
        swallowUp = false
        _state.value = MirrorCalibrationState(step = 1, portrait = bounds.portrait)
    }

    fun cancel() {
        taps.clear()
        _state.value = MirrorCalibrationState()
    }

    private fun key(bounds: MirrorTouchBounds, carWidth: Int, carHeight: Int): String =
        "${carWidth}x${carHeight}_mirror_${if (bounds.portrait) "portrait" else "landscape"}_quad4"

    fun reset(context: Context, bounds: MirrorTouchBounds, carWidth: Int, carHeight: Int) {
        cancel()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(bounds, carWidth, carHeight)).apply()
        DiagLog.i("ICCOA", "Mirror ${if (bounds.portrait) "portrait" else "landscape"} calibration reset")
    }

    fun consume(context: Context, action: Int, x: Int, y: Int, bounds: MirrorTouchBounds,
                carWidth: Int, carHeight: Int): Boolean {
        val st = _state.value
        if (st.step !in 1..4) {
            if (!swallowUp) return false
            if (action == CarTouch.UP) swallowUp = false
            return true
        }
        if (st.portrait != bounds.portrait) {
            cancel()
            DiagLog.w("ICCOA", "Mirror orientation changed during calibration; restart")
            return true
        }
        if (action != CarTouch.DOWN) {
            if (action == CarTouch.UP) swallowUp = false
            return true
        }
        swallowUp = true
        val (nx, ny) = bounds.normalized(x, y)
        taps.add(TouchPoint(nx.toDouble(), ny.toDouble()))
        if (taps.size < 4) {
            _state.value = st.copy(step = taps.size + 1)
            return true
        }
        val quad = TouchQuad.fromSamples(taps, UNIT, UNIT)
        if (quad == null) {
            taps.clear()
            _state.value = st.copy(step = 1)
            DiagLog.w("ICCOA", "Mirror 4-point touch geometry rejected, restart calibration")
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(bounds, carWidth, carHeight), quad.serialize()).apply()
            DiagLog.i("ICCOA", "Mirror four-point calibration saved orientation=${if (bounds.portrait) "portrait" else "landscape"}")
            cancel()
        }
        return true
    }

    fun map(context: Context, bounds: MirrorTouchBounds, x: Int, y: Int,
            carWidth: Int, carHeight: Int): Pair<Int, Int> {
        val normalized = bounds.normalized(x, y)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(bounds, carWidth, carHeight), null) ?: return normalized
        return TouchQuad.deserialize(saved, UNIT, UNIT)?.map(normalized.first, normalized.second, UNIT, UNIT)
            ?: normalized
    }
}
