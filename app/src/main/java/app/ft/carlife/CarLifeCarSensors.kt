package app.ft.carlife

import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import app.ft.core.VehicleTelemetry

/** Original Baidu CarlifeCarGpsProto / CarlifeCarSpeedProto field layout.
 * CarLife's coordinates are int32, not ICCOA's double. Their multiplier and
 * datum cannot be inferred from the protobuf descriptor alone.
 */
internal object CarLifeCarSensors {
    const val GPS = CarLifeProtocol.CMD_CAR_GPS
    const val SPEED = CarLifeProtocol.CMD_CAR_VELOCITY

    fun onGps(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > 512) return false
        val fields = ProtoReader(payload)
        // Required fields in the original Baidu schema: antenna, signal, lat/lon,
        // height, speed, heading, date/time, fix, DOP, satellites, errors, speeds.
        if (!fields.has(3) || !fields.has(4) || !fields.has(6) || !fields.has(7)) return false
        val lat = fields.int(3).toLong()
        val lon = fields.int(4).toLong()
        // Coordinate scale is OEM-specific: retain integers until verified.
        val used = fields.long(18).takeIf { it in 0..255L }
        val visible = fields.long(19).takeIf { it in 0..255L }
        VehicleTelemetry.publish(VehicleLocation(
            source = "BAIDU",
            receivedAtMs = System.currentTimeMillis(),
            rawLatitude = lat,
            rawLongitude = lon,
            speedRaw = fields.long(6).takeIf { it in 0..100_000L },
            headingRaw = fields.long(7).takeIf { it in 0..360_000L },
            satellitesUsed = used,
            satellitesVisible = visible,
            deviceTimestampRaw = fields.long(25).takeIf { it > 0L }
        ))
        return true
    }

    fun onSpeed(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > 64) return false
        val fields = ProtoReader(payload)
        if (!fields.has(1)) return false
        val speed = fields.int(1).toLong()
        if (speed !in 0..100_000L) return false
        VehicleTelemetry.updateBaiduSpeed(speed, fields.long(2).takeIf { it > 0L })
        return true
    }
}
