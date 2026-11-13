package app.ft.iccoa

import app.ft.projection.CarTouch
import org.json.JSONObject

/** Some receivers omit dimensions because coordinates already use the negotiated video size. */
internal object IccoaInputEvent {
    fun touch(event: JSONObject, video: IccoaVideoConfig): CarTouch? {
        val rawWidth = event.optInt("width", 0)
        val rawHeight = event.optInt("height", 0)
        if (rawWidth !in 0..8192 || rawHeight !in 0..8192) return null
        val width = rawWidth.takeIf { it > 0 } ?: video.width
        val height = rawHeight.takeIf { it > 0 } ?: video.height
        val count = event.optInt("count", 1)
        if (count !in 1..2) return null // Projection backends support at most two fingers.
        val action = event.optInt("action", -1)
        val kind = action and 255
        if (kind !in setOf(0, 1, 2, 3, 5, 6)) return null
        val index = action ushr 8 and 255
        if (kind in setOf(5, 6) && (count != 2 || index >= count)) return null
        fun coordinate(name: String, source: Int, target: Int): Int? {
            val value = event.optDouble(name, Double.NaN)
            if (!value.isFinite()) return null
            return (value * target / source).toInt().coerceIn(0, target - 1)
        }
        return CarTouch(kind,
            coordinate("x0", width, video.width) ?: return null,
            coordinate("y0", height, video.height) ?: return null,
            if (count > 1) coordinate("x1", width, video.width) ?: return null else -1,
            if (count > 1) coordinate("y1", height, video.height) ?: return null else -1, index)
    }
}
