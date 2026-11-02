package app.ft.aa

import app.ft.core.ProtoWriter
import app.ft.core.VehicleLocation
import kotlin.math.roundToInt

/**
 * Android Auto SENSOR_LOCATION bridge for verified decimal-degree car fixes.
 *
 * The source must explicitly declare Android Location API / WGS84 provenance.
 * Baidu integer GPS is NOT forwarded until its scale/CRS is verified.
 * No guessed speed, bearing, accuracy, altitude or Dead Reckoning flags are sent.
 */
internal object AaCarGps {
    const val LOCATION = AaProtocol.SENSOR_LOCATION
    private const val CRS = "ANDROID_LOCATION_WGS84"

    fun event(location: VehicleLocation?, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (location == null || location.source != "ICCOA" || location.coordinatesUnit != CRS ||
            !location.positionFresh(nowMs)) return null
        val latitude = location.displayLatitude ?: return null
        val longitude = location.displayLongitude ?: return null
        // An uninitialized OEM fix often uses Null Island: never send it to AA Maps.
        if (latitude == 0.0 && longitude == 0.0) return null
        // Android Auto GPSLocationData: repeated field 1; sub-fields 2 & 3 are required
        // int32 microdegrees, NOT ICCOA's double or Baidu's raw int32.
        val latMicro = (latitude * 1_000_000.0).roundToInt()
        val lonMicro = (longitude * 1_000_000.0).roundToInt()
        return ProtoWriter().message(1, ProtoWriter()
            .int32(2, latMicro)
            .int32(3, lonMicro)
        ).toByteArray()
    }

    fun sensorError(status: Int): ByteArray =
        ProtoWriter().enum(1, LOCATION).enum(2, status).toByteArray()
}


/**
 * Suppress isolated GNSS jumps (including RF interference) before feeding maps.
 * Require three consecutive consistent distinct car fixes to acquire/reacquire.
 * Output and source selection remain independent of diagnostic 10-second sampling.
 */
internal class AaGpsGate {
    private var previous: VehicleLocation? = null
    // The last fix actually delivered to AA is the trusted movement anchor.
    // A self-consistent cluster of erroneous fixes must not move this anchor.
    private var lastDelivered: VehicleLocation? = null
    private var lastObservedAt = Long.MIN_VALUE
    private var consecutive = 0

    fun accept(sample: VehicleLocation?, nowMs: Long): ByteArray? {
        val encoded = AaCarGps.event(sample, nowMs)
        if (encoded == null || sample == null) {
            if (previous != null && nowMs - lastObservedAt > 3_000L) reset()
            return null
        }
        val preceding = previous
        if (preceding != null && preceding.positionAtMs == sample.positionAtMs) return null
        if (preceding != null) {
            val elapsedMs = sample.positionAtMs - preceding.positionAtMs
            val lat1 = preceding.displayLatitude
            val lon1 = preceding.displayLongitude
            val lat2 = sample.displayLatitude
            val lon2 = sample.displayLongitude
            if (elapsedMs !in 1L..3_000L || lat1 == null || lon1 == null || lat2 == null || lon2 == null ||
                unreasonableJump(lat1, lon1, lat2, lon2, elapsedMs)) {
                previous = sample
                consecutive = 1
                lastObservedAt = nowMs
                return null
            }
        }
        previous = sample
        lastObservedAt = nowMs
        consecutive++
        if (consecutive < 3) return null
        val anchor = lastDelivered
        if (anchor != null) {
            val elapsedMs = sample.positionAtMs - anchor.positionAtMs
            val lat1 = anchor.displayLatitude ?: return null
            val lon1 = anchor.displayLongitude ?: return null
            val lat2 = sample.displayLatitude ?: return null
            val lon2 = sample.displayLongitude ?: return null
            // Guard against a sustained false GPS cluster, not just a single spike.
            // The stream must genuinely go stale before a far-away fix can reacquire.
            if (elapsedMs !in 1L..3_000L ||
                unreasonableJump(lat1, lon1, lat2, lon2, elapsedMs)) return null
        }
        lastDelivered = sample
        return encoded
    }

    fun reset() {
        previous = null
        lastDelivered = null
        consecutive = 0
        lastObservedAt = Long.MIN_VALUE
    }

    private fun unreasonableJump(lat1: Double, lon1: Double, lat2: Double, lon2: Double, ms: Long): Boolean {
        val dy = Math.toRadians(lat2 - lat1)
        val dx = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dy / 2) * kotlin.math.sin(dy / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dx / 2) * kotlin.math.sin(dx / 2)
        val meters = 2.0 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        // Allow up to 300 km/h plus 30 m of GNSS jitter between readings.
        return meters > 30.0 + (ms / 1_000.0) * (300.0 / 3.6)
    }
}
