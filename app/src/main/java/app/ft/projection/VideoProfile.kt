package app.ft.projection

import org.json.JSONObject

/** A working stream, kept separately from the user's automatic/manual choices. */
data class VideoProfile(
    val plan: VideoPlan,
    val settingsKey: String,
    val carKey: String,
    val automaticSize: Boolean,
    val automaticFps: Boolean
) {
    fun resume(candidate: VideoPlan, settings: String, car: String, requestedFps: Int = 0): VideoPlan? =
        plan.takeIf {
            settingsKey == settings && carKey == car &&
                it.contentWidth == candidate.contentWidth && it.contentHeight == candidate.contentHeight
        }?.let { if (requestedFps > 2 || it.fps < candidate.fps) it.copy(fps = candidate.fps) else it }

    fun encode(): String = JSONObject().apply {
        put("settings", settingsKey)
        put("car", carKey)
        put("autoSize", automaticSize)
        put("autoFps", automaticFps)
        put("cw", plan.contentWidth); put("ch", plan.contentHeight)
        put("w", plan.streamWidth); put("h", plan.streamHeight)
        put("fps", plan.fps); put("bitrate", plan.bitrate); put("qp", plan.qpFloor)
    }.toString()

    companion object {
        fun decode(value: String): VideoProfile? = runCatching {
            val j = JSONObject(value)
            val p = VideoPlan(j.getInt("cw"), j.getInt("ch"), j.getInt("w"), j.getInt("h"),
                j.getInt("fps"), j.getInt("bitrate"), j.getInt("qp"))
            require(p.contentWidth in 320..4096 && p.contentHeight in 240..2160)
            require(p.streamWidth in 320..4096 && p.streamHeight in 240..2160)
            require(p.streamWidth % 2 == 0 && p.streamHeight % 2 == 0)
            require(p.fps in 1..60 && p.bitrate in 300_000..40_000_000 && p.qpFloor in 0..51)
            VideoProfile(p, j.getString("settings"), j.getString("car"), j.getBoolean("autoSize"), j.getBoolean("autoFps"))
        }.getOrNull()
    }
}
