package app.ft.core

import app.ft.FTApp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared, protocol-neutral vehicle sensors for FT widgets and later AA location forwarding.
 * No coordinate datum, speed multiplier or heading unit is guessed.
 */
data class VehicleLocation(
    val source: String,
    val receivedAtMs: Long,
    val positionAtMs: Long = receivedAtMs,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rawLatitude: Long? = null,
    val rawLongitude: Long? = null,
    val speedRaw: Long? = null,
    // OEM GearInfo and OEM GPS speed are different raw units; never conflate them.
    val gearSpeedRaw: Long? = null,
    val gearRaw: Long? = null,
    val gearAtMs: Long = 0L,
    val headingRaw: Long? = null,
    val speedKmh: Float? = null,
    val headingDegrees: Float? = null,
    val satellitesUsed: Long? = null,
    val satellitesVisible: Long? = null,
    val deviceTimestampRaw: Long? = null,
    val coordinatesUnit: String = "UNKNOWN",
    val speedUnit: String = "UNKNOWN",
    val headingUnit: String = "UNKNOWN"
) {
    fun fresh(nowMs: Long = System.currentTimeMillis()) =
        nowMs >= receivedAtMs && nowMs - receivedAtMs <= 3_000L

    fun positionFresh(nowMs: Long = System.currentTimeMillis()) =
        positionAtMs > 0L && nowMs >= positionAtMs && nowMs - positionAtMs <= 3_000L

    val displayLatitude: Double? get() = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val displayLongitude: Double? get() = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
}

/** Both Baidu CarLife and ICCOA publish here. All consumers observe one stable state. */
object VehicleTelemetry {
    private val _current = MutableStateFlow<VehicleLocation?>(null)
    val current: StateFlow<VehicleLocation?> = _current
    private val logSampler = VehicleLogSampler()

    /**
     * 10s routine samples, temporary 1Hz bursts after source/fix changes or a telemetry gap.
     * Does not throttle real-time position delivery.
     */
    private fun note(sample: VehicleLocation) {
        val now = sample.receivedAtMs
        val hasFix = sample.positionFresh(now) &&
            ((sample.displayLatitude != null && sample.displayLongitude != null) ||
             (sample.rawLatitude != null && sample.rawLongitude != null))
        val decision = logSampler.accept(sample.source, now, hasFix) ?: return
        runCatching {
            DiagLog.i("VehicleGPS", VehicleCoordinateLog.format(
                sample, decision, FTApp.instance.prefs.vehicleCoordinateLogging
            ))
        }
    }

    @Synchronized fun publish(location: VehicleLocation) {
        require(location.source == "ICCOA" || location.source == "BAIDU")
        val previous = _current.value?.takeIf {
            it.source == location.source && it.fresh(location.receivedAtMs)
        }
        // A new GPS frame must not erase a recent, separately sourced GearInfo frame.
        val preserved = if (location.source == "ICCOA" && previous != null)
            location.copy(gearSpeedRaw = previous.gearSpeedRaw,
                gearRaw = previous.gearRaw, gearAtMs = previous.gearAtMs) else location
        _current.value = preserved
        note(preserved)
    }

    @Synchronized fun updateBaiduSpeed(rawSpeed: Long, deviceTimestamp: Long? = null) {
        if (rawSpeed < 0L || rawSpeed > 100_000L) return
        val old = _current.value?.takeIf { it.source == "BAIDU" && it.fresh() }
        _current.value = (old ?: VehicleLocation("BAIDU", System.currentTimeMillis(), positionAtMs = 0L)).copy(
            receivedAtMs = System.currentTimeMillis(), speedRaw = rawSpeed,
            deviceTimestampRaw = deviceTimestamp ?: old?.deviceTimestampRaw
        )
        _current.value?.let(::note)
    }

    @Synchronized fun updateIccoaSpeed(rawSpeed: Long, gearRaw: Long? = null) {
        if (rawSpeed < 0L || rawSpeed > 100_000L || (gearRaw != null && gearRaw !in 0L..4L)) return
        val now = System.currentTimeMillis()
        // Keep the last GPS fix even after a long GearInfo gap. Its positionAtMs
        // remains unchanged: stale data is display-only and never eligible for AA.
        val old = _current.value?.takeIf { it.source == "ICCOA" }
        _current.value = (old ?: VehicleLocation("ICCOA", now, positionAtMs = 0L)).copy(
            receivedAtMs = now,
            gearSpeedRaw = rawSpeed,
            gearRaw = gearRaw ?: old?.gearRaw,
            gearAtMs = if (gearRaw != null) now else old?.gearAtMs ?: 0L,
            // Keep unknown GPS raw speed distinct from the received GearInfo integer.
            speedRaw = old?.speedRaw
        )
        _current.value?.let(::note)
    }

    @Synchronized fun clear(source: String) {
        if (_current.value?.source == source) {
            _current.value = null
            runCatching { DiagLog.i("VehicleGPS", "reason=SOURCE_DISCONNECTED protocol=$source") }
            logSampler.reset()
        }
    }
}
