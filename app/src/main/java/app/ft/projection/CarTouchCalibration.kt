package app.ft.projection

import android.content.Context
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Raw UIBC point in the FT virtual-display coordinate system. */
internal data class TouchPoint(val x: Double, val y: Double)

/**
 * Inverts the bilinear quadrilateral formed by four measured HU touches:
 * upper-left, upper-right, lower-left, lower-right. Unlike separate X/Y
 * gains, this handles a small shear and unequal horizontal/vertical scaling.
 */
internal class TouchQuad private constructor(
    private val topLeft: TouchPoint,
    private val topRight: TouchPoint,
    private val bottomLeft: TouchPoint,
    private val bottomRight: TouchPoint
) {
    fun map(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
        val bX = topRight.x - topLeft.x
        val bY = topRight.y - topLeft.y
        val cX = bottomLeft.x - topLeft.x
        val cY = bottomLeft.y - topLeft.y
        val dX = topLeft.x - topRight.x - bottomLeft.x + bottomRight.x
        val dY = topLeft.y - topRight.y - bottomLeft.y + bottomRight.y
        var u = 0.5
        var v = 0.5
        repeat(14) {
            val eX = topLeft.x + bX * u + cX * v + dX * u * v - x
            val eY = topLeft.y + bY * u + cY * v + dY * u * v - y
            val j11 = bX + dX * v
            val j12 = cX + dX * u
            val j21 = bY + dY * v
            val j22 = cY + dY * u
            val determinant = j11 * j22 - j12 * j21
            if (!determinant.isFinite() || abs(determinant) < 1e-5) return x to y
            val stepU = (eX * j22 - eY * j12) / determinant
            val stepV = (eY * j11 - eX * j21) / determinant
            u -= stepU
            v -= stepV
            if (!u.isFinite() || !v.isFinite() || abs(u) > 5 || abs(v) > 5) return x to y
        }
        val outX = ((0.15 + 0.70 * u) * width).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val outY = ((0.15 + 0.70 * v) * height).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        return outX to outY
    }

    fun serialize(): String = listOf(topLeft, topRight, bottomLeft, bottomRight)
        .joinToString(",") { "${it.x},${it.y}" }

    companion object {
        fun fromSamples(points: List<TouchPoint>, width: Int, height: Int): TouchQuad? {
            if (points.size != 4 || width < 200 || height < 150) return null
            if (points.any { !it.x.isFinite() || !it.y.isFinite() ||
                    it.x !in 0.0..width.toDouble() || it.y !in 0.0..height.toDouble() }) return null
            val (tl, tr, bl, br) = points
            val minSpanX = width * 0.25
            val minSpanY = height * 0.25
            if (tr.x - tl.x < minSpanX || br.x - bl.x < minSpanX ||
                bl.y - tl.y < minSpanY || br.y - tr.y < minSpanY) return null
            // Reject a folded or near-singular quadrilateral, including inverted corners.
            val model = TouchQuad(tl, tr, bl, br)
            val cx = (tl.x + tr.x + bl.x + br.x) / 4
            val cy = (tl.y + tr.y + bl.y + br.y) / 4
            val bx = (tr.x + br.x - tl.x - bl.x) / 2
            val by = (tr.y + br.y - tl.y - bl.y) / 2
            val dx = (bl.x + br.x - tl.x - tr.x) / 2
            val dy = (bl.y + br.y - tl.y - tr.y) / 2
            if (bx * dy - by * dx < width * height * 0.06) return null
            val center = model.map(cx.roundToInt(), cy.roundToInt(), width, height)
            if (abs(center.first - width / 2) > width / 10 ||
                abs(center.second - height / 2) > height / 10) return null
            return model
        }

        fun deserialize(text: String, width: Int, height: Int): TouchQuad? {
            val items = text.split(",")
            if (items.size != 8) return null
            val values = items.map { it.toDoubleOrNull() ?: return null }
            return fromSamples(values.chunked(2).map { TouchPoint(it[0], it[1]) }, width, height)
        }
    }
}

/** FT157 X-only profile stays valid until four new points have been recorded. */
internal data class TouchScale(val gain: Double, val offsetPx: Double) {
    fun map(x: Int, width: Int): Int =
        (x * gain + offsetPx).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))

    companion object {
        fun fromTwoTargets(width: Int, measuredLeft: Int, measuredRight: Int): TouchScale? {
            if (width < 200 || measuredRight - measuredLeft < width / 4) return null
            val wantedLeft = width * 0.20
            val wantedRight = width * 0.80
            val gain = (wantedRight - wantedLeft) / (measuredRight - measuredLeft).toDouble()
            if (!gain.isFinite() || gain !in 0.60..1.80) return null
            val offset = wantedLeft - measuredLeft * gain
            if (!offset.isFinite() || abs(offset) > width * 0.35) return null
            return TouchScale(gain, offset)
        }
    }
}

internal data class TouchCalibrationState(val step: Int = 0, val width: Int = 0, val height: Int = 0)

/** FT/phone-mirror HU calibration, independent of Android Auto's touch protocol. */
internal object CarTouchCalibration {
    private val _state = MutableStateFlow(TouchCalibrationState())
    val state: StateFlow<TouchCalibrationState> = _state
    private val points = mutableListOf<TouchPoint>()
    private var swallowUntilUp = false

    fun begin(width: Int, height: Int) {
        if (width < 200 || height < 150) return
        points.clear()
        swallowUntilUp = false
        _state.value = TouchCalibrationState(step = 1, width = width, height = height)
    }

    fun cancel() {
        points.clear()
        _state.value = TouchCalibrationState()
    }

    fun reset(context: Context, width: Int, height: Int) {
        cancel()
        val key = "${width}x${height}"
        context.getSharedPreferences("ft-iccoa-touch", Context.MODE_PRIVATE).edit()
            .remove("${key}_quad4").remove("${key}_gain").remove("${key}_offset").apply()
        DiagLog.i("ICCOA", "FT touch calibration reset for $key")
    }

    /** Receive uncorrected incoming touches; preserve whole DOWN..UP gesture. */
    fun consume(context: Context, action: Int, x: Int, y: Int): Boolean {
        val c = _state.value
        if (c.step !in 1..4) {
            if (!swallowUntilUp) return false
            if (action == CarTouch.UP) swallowUntilUp = false
            return true
        }
        if (action != CarTouch.DOWN) {
            if (action == CarTouch.UP) swallowUntilUp = false
            return true
        }
        swallowUntilUp = true
        points.add(TouchPoint(x.toDouble(), y.toDouble()))
        if (points.size < 4) {
            _state.value = c.copy(step = points.size + 1)
            return true
        }
        val model = TouchQuad.fromSamples(points, c.width, c.height)
        if (model == null) {
            DiagLog.w("ICCOA", "Four-point calibration rejected: invalid geometry; restart from first point")
            points.clear()
            _state.value = c.copy(step = 1)
        } else {
            val key = "${c.width}x${c.height}"
            context.getSharedPreferences("ft-iccoa-touch", Context.MODE_PRIVATE).edit()
                .putString("${key}_quad4", model.serialize()).apply()
            DiagLog.i("ICCOA", "FT four-point X/Y touch calibration saved for $key")
            cancel()
        }
        return true
    }

    fun correct(context: Context, x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("ft-iccoa-touch", Context.MODE_PRIVATE)
        val key = "${width}x${height}"
        val saved = prefs.getString("${key}_quad4", null)
        if (saved != null) {
            TouchQuad.deserialize(saved, width, height)?.let { return it.map(x, y, width, height) }
        }
        val gain = prefs.getFloat("${key}_gain", 1f).toDouble()
        val offset = prefs.getFloat("${key}_offset", 0f).toDouble()
        if (!gain.isFinite() || gain !in 0.60..1.80 || !offset.isFinite() ||
            abs(offset) > width * 0.35) return x to y
        return TouchScale(gain, offset).map(x, width) to y
    }
}
