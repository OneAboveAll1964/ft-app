package app.ft.iccoa

/**
 * GPS payload in the original FAW Toyota Carlink.apk ucar.proto.
 * This decoder does not infer a coordinate reference system or units for speed/heading/time.
 */
internal data class IccoaGpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val antennaState: Long,
    val pdop: Long,
    val speedRaw: Long,
    val headingRaw: Long,
    val satellitesUsed: Long,
    val satellitesVisible: Long,
    val timestampRaw: Long
)

internal object IccoaGpsWire {
    const val SENSOR_CATEGORY = 2
    const val GPS_METHOD = 1
    private const val MAX_BYTES = 512

    /**
     * The OEM ucar.proto defines both direct Gps and SensorIndex.gps (field 1).
     * Exact on-air nesting is unconfirmed on this HU: accept either *only* when
     * the corresponding complete protobuf form is strictly valid.
     */
    fun decode(body: ByteArray): IccoaGpsFix? {
        if (body.isEmpty() || body.size > MAX_BYTES) return null
        return decodeDirect(body) ?: nestedGps(body)?.let(::decodeDirect)
    }

    private fun nestedGps(body: ByteArray): ByteArray? = runCatching {
        var offset = 0
        var gps: ByteArray? = null
        fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                require(offset < body.size)
                val octet = body[offset++].toInt() and 255
                if (index == 9) require(octet <= 1)
                result = result or ((octet and 127).toLong() shl (index * 7))
                if (octet and 128 == 0) return result
            }
            error("Invalid sensor wrapper integer")
        }
        while (offset < body.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            // All eight SensorIndex fields are protobuf messages, not scalars.
            require(field in 1..8 && (tag and 7) == 2L)
            val length = varint()
            require(length >= 0 && length <= (body.size - offset).toLong())
            if (field == 1) {
                require(gps == null)
                gps = body.copyOfRange(offset, offset + length.toInt())
            }
            offset += length.toInt()
        }
        gps
    }.getOrNull()

    private fun decodeDirect(body: ByteArray): IccoaGpsFix? {
        if (body.isEmpty() || body.size > MAX_BYTES) return null
        var offset = 0
        var latitude: Double? = null
        var longitude: Double? = null
        var altitude = 0.0
        val numbers = LongArray(11)
        fun varint(): Long {
            var value = 0L
            for (index in 0 until 10) {
                require(offset < body.size)
                val octet = body[offset++].toInt() and 255
                if (index == 9) require(octet <= 1)
                value = value or ((octet and 127).toLong() shl (index * 7))
                if (octet and 128 == 0) return value
            }
            error("Invalid protobuf varint")
        }
        fun fixed64(): Double {
            require(body.size - offset >= 8)
            var bits = 0L
            repeat(8) { index -> bits = bits or ((body[offset++].toLong() and 255L) shl (index * 8)) }
            return Double.fromBits(bits)
        }
        return runCatching {
            while (offset < body.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                require(field in 1..2047)
                when (wire) {
                    0 -> {
                        val value = varint()
                        if (field in 4..10) numbers[field] = value
                    }
                    1 -> {
                        val value = fixed64()
                        when (field) {
                            1 -> altitude = value
                            2 -> latitude = value
                            3 -> longitude = value
                        }
                    }
                    2 -> {
                        val size = varint()
                        require(size in 0..(body.size - offset).toLong())
                        offset += size.toInt()
                    }
                    5 -> { require(body.size - offset >= 4); offset += 4 }
                    else -> error("Unsupported protobuf wire type")
                }
            }
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            require(lat.isFinite() && lon.isFinite() && altitude.isFinite())
            require(lat in -90.0..90.0 && lon in -180.0..180.0)
            require(numbers[8] <= 255 && numbers[9] <= 255)
            IccoaGpsFix(lat, lon, altitude, numbers[4], numbers[5], numbers[6],
                numbers[7], numbers[8], numbers[9], numbers[10])
        }.getOrNull()
    }
}

/**
 * OEM CarLinkSensorMgr.updateLocationInfo reads Android Location.getSpeed()
 * and getBearing(), then floors both before UCarProto.Gps serialisation.
 * Android Location speed is m/s; bearing is clockwise degrees from north.
 * Keep the raw fields independently for diagnostics and never derive a
 * more precise value than the OEM integer transmitted.
 */
internal object IccoaGpsUnits {
    fun speedKmh(speedRaw: Long): Float? =
        speedRaw.takeIf { it in 0L..125L }?.let { it * 3.6f }

    fun headingDegrees(headingRaw: Long): Float? =
        headingRaw.takeIf { it in 0L..359L }?.toFloat()
}
