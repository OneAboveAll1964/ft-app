package app.ft.iccoa

import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IccoaGpsWireTest {
    private fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n and -128L != 0L) {
            out.write(((n and 127L) or 128L).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }
    private fun data(latitude: Double, longitude: Double): ByteArray {
        val out = ByteArrayOutputStream()
        fun coordinate(id: Int, value: Double) {
            out.write((id shl 3) or 1)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        }
        coordinate(1, 112.5)
        coordinate(2, latitude)
        coordinate(3, longitude)
        out.write(8 shl 3)
        out.write(varint(12))
        out.write(9 shl 3)
        out.write(varint(16))
        out.write(10 shl 3)
        out.write(varint(1700000000000L))
        return out.toByteArray()
    }
    @Test fun validVendorGpsPayload() {
        val fix = IccoaGpsWire.decode(data(55.7522, 37.6156))!!
        assertEquals(55.7522, fix.latitude, 0.000000001)
        assertEquals(37.6156, fix.longitude, 0.000000001)
        assertEquals(112.5, fix.altitude, 0.001)
        assertEquals(12L, fix.satellitesUsed)
        assertEquals(16L, fix.satellitesVisible)
        assertEquals(1700000000000L, fix.timestampRaw)
    }
    @Test fun gpsInsideStrictOemSensorIndexIsDecoded() {
        // OEM ucar.proto: SensorIndex.gps=1 and SensorIndex.gear_info=6.
        val nested = ProtoWriter().bytes(1, data(55.7522, 37.6156))
            .bytes(6, ProtoWriter().int32(2, 24).toByteArray()).toByteArray()
        val fix = IccoaGpsWire.decode(nested)!!
        assertEquals(55.7522, fix.latitude, 0.000000001)
        assertEquals(37.6156, fix.longitude, 0.000000001)
        assertEquals(12L, fix.satellitesUsed)
    }
    @Test fun strictOemSensorIndexRejectsMalformedEnvelope() {
        val raw = data(55.7522, 37.6156)
        val wrapper = ProtoWriter().bytes(1, raw).toByteArray()
        assertNull(IccoaGpsWire.decode(wrapper.copyOf(wrapper.size - 1)))
        assertNull(IccoaGpsWire.decode(
            ProtoWriter().bytes(1, raw).bytes(1, raw).toByteArray()))
        assertNull(IccoaGpsWire.decode(ProtoWriter().bytes(6, raw).toByteArray()))
        assertNull(IccoaGpsWire.decode(ProtoWriter().int32(1, 7).toByteArray()))
    }
    @Test fun oemAndroidLocationFlooringGivesKilometersPerHourAndHeading() {
        // OEM: floor(Location.getSpeed() m/s) and floor(getBearing() degrees).
        assertEquals(7.2f, IccoaGpsUnits.speedKmh(2L)!!, 0.0001f)
        assertEquals(0f, IccoaGpsUnits.speedKmh(0L)!!, 0f)
        assertEquals(359f, IccoaGpsUnits.headingDegrees(359L)!!, 0f)
        assertEquals(0f, IccoaGpsUnits.headingDegrees(0L)!!, 0f)
        assertNull(IccoaGpsUnits.speedKmh(-1))
        assertNull(IccoaGpsUnits.speedKmh(10000L))
        assertNull(IccoaGpsUnits.headingDegrees(360L))
    }

    @Test fun rejectsOutOfRangeCoordinates() {
        assertNull(IccoaGpsWire.decode(data(91.0, 37.0)))
        assertNull(IccoaGpsWire.decode(data(55.0, 190.0)))
        assertNull(IccoaGpsWire.decode(data(Double.NaN, 2.0)))
    }
    @Test fun rejectsTruncatedAndOversizedPayloads() {
        val valid = data(55.0, 37.0)
        assertNull(IccoaGpsWire.decode(valid.copyOfRange(0, valid.size - 1)))
        assertNull(IccoaGpsWire.decode(ByteArray(513)))
        assertNull(IccoaGpsWire.decode(byteArrayOf(0x11, 0x00)))
    }
}
