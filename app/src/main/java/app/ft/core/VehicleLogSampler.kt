package app.ft.core

/**
 * Rate limiter for diagnostic vehicle telemetry only.
 * Never throttles the live Baidu/ICCOA sensor feed or FT's widgets.
 */
internal class VehicleLogSampler(
    private val normalIntervalMs: Long = 10_000L,
    private val eventIntervalMs: Long = 1_000L,
    private val eventWindowMs: Long = 20_000L,
    private val streamGapMs: Long = 3_000L
) {
    data class Decision(val reason: String, val fast: Boolean)

    private var previousSource: String? = null
    private var previousHasFix: Boolean? = null
    private var previousFrameMs: Long? = null
    private var lastOutputMs: Long? = null
    private var fastUntilMs = Long.MIN_VALUE
    private var pendingEvent: String? = null

    fun accept(source: String, nowMs: Long, hasFix: Boolean): Decision? {
        if (nowMs < 0L) return null
        val reason = when {
            previousFrameMs != null && nowMs < previousFrameMs!! -> "CLOCK_RESET"
            previousSource != null && previousSource != source -> "SOURCE_SWITCH"
            previousFrameMs != null && nowMs - previousFrameMs!! > streamGapMs -> "STREAM_RESUMED"
            previousHasFix == true && !hasFix -> "FIX_LOST"
            previousHasFix == false && hasFix -> "FIX_REACQUIRED"
            else -> null
        }
        previousSource = source
        previousHasFix = hasFix
        previousFrameMs = nowMs
        if (reason != null) {
            fastUntilMs = nowMs + eventWindowMs
            pendingEvent = reason
        }

        val fast = nowMs <= fastUntilMs
        val interval = if (fast) eventIntervalMs else normalIntervalMs
        val output = lastOutputMs
        // Hard rate cap, including rapid back-to-back GPS events. Keep pending event
        // reason until the next permitted report sample.
        if (output == null || nowMs < output || nowMs - output >= interval) {
            lastOutputMs = nowMs
            val event = pendingEvent ?: "PERIODIC"
            pendingEvent = null
            return Decision(event, fast)
        }
        return null
    }

    fun reset() {
        previousSource = null
        previousHasFix = null
        previousFrameMs = null
        lastOutputMs = null
        fastUntilMs = Long.MIN_VALUE
        pendingEvent = null
    }
}
