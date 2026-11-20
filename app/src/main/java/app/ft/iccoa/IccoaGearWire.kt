package app.ft.iccoa

import app.ft.core.VehicleTelemetry
import app.ft.core.readVarintPair

/**
 * OEM ucar.proto GearInfo: enum gear=1, uint32 speed=2, method SENSOR/6.
 * Speed unit is unknown: preserve the exact wire integer without scaling.
 * An incomplete protobuf MUST NOT update the shared vehicle telemetry.
 */
internal object IccoaGearWire {
    const val SENSOR_METHOD = 6

    fun accept(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > 128) return false
        val (gear, speed) = readVarintPair(payload) ?: return false
        if (gear != null && gear !in 0L..4L) return false
        if (speed == null || speed !in 0L..100_000L) return false
        VehicleTelemetry.updateIccoaSpeed(speed, gear)
        return true
    }
}
