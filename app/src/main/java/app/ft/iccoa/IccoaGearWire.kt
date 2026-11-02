package app.ft.iccoa

import app.ft.core.VehicleTelemetry

/**
 * OEM ucar.proto GearInfo: enum gear=1, uint32 speed=2, method SENSOR/6.
 * Speed unit is unknown: preserve the exact wire integer without scaling.
 * An incomplete protobuf MUST NOT update the shared vehicle telemetry.
 */
internal object IccoaGearWire {
    const val SENSOR_METHOD = 6

    fun accept(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > 128) return false
        val parsed = runCatching {
            var offset = 0
            var gear: Long? = null
            var speed: Long? = null
            fun varint(): Long {
                var result = 0L
                for (index in 0..9) {
                    require(offset < payload.size) { "Truncated gear field" }
                    val octet = payload[offset++].toInt() and 255
                    if (index == 9) require(octet <= 1) { "Overflow gear field" }
                    result = result or ((octet and 127).toLong() shl (index * 7))
                    if (octet and 128 == 0) return result
                }
                error("Invalid gear varint")
            }
            while (offset < payload.size) {
                when (varint()) {
                    0x08L -> {
                        require(gear == null) { "Duplicate gear" }
                        gear = varint()
                    }
                    0x10L -> {
                        require(speed == null) { "Duplicate speed" }
                        speed = varint()
                    }
                    else -> error("Unexpected GearInfo field/wire type")
                }
            }
            require(gear == null || gear in 0L..4L)
            val raw = requireNotNull(speed) { "Missing speed" }
            require(raw in 0L..100_000L)
            raw
        }.getOrNull() ?: return false
        VehicleTelemetry.updateIccoaSpeed(parsed)
        return true
    }
}
