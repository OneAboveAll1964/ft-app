package app.ft.iccoa

import app.ft.core.ProtoWriter
import app.ft.core.VehicleTelemetry
import org.junit.Assert.*
import org.junit.Test

class IccoaGearWireTest {
    @Test fun acceptsDirectGearInfo() {
        val bytes = ProtoWriter().int32(1, 2).uint32(2, 61).toByteArray()
        assertTrue(IccoaGearWire.accept(bytes))
        assertEquals("ICCOA", VehicleTelemetry.current.value?.source)
        assertEquals(61L, VehicleTelemetry.current.value?.gearSpeedRaw)
        assertEquals(2L, VehicleTelemetry.current.value?.gearRaw)
        assertNull(VehicleTelemetry.current.value?.speedRaw)
        assertEquals(0L, VehicleTelemetry.current.value?.positionAtMs)
        assertNull(VehicleTelemetry.current.value?.speedKmh)
        VehicleTelemetry.clear("ICCOA")
    }
    @Test fun completeGearInfoCanArriveOutOfOrder() {
        val bytes = ProtoWriter().uint32(2, 100_000).enum(1, 4).toByteArray()
        assertTrue(IccoaGearWire.accept(bytes))
        assertEquals(100_000L, VehicleTelemetry.current.value?.gearSpeedRaw)
        assertEquals(4L, VehicleTelemetry.current.value?.gearRaw)
        VehicleTelemetry.clear("ICCOA")
    }
    @Test fun truncatedOrDuplicateGearInfoDoesNotPolluteTelemetry() {
        VehicleTelemetry.clear("ICCOA")
        val good = ProtoWriter().enum(1, 2).uint32(2, 61).toByteArray()
        assertFalse(IccoaGearWire.accept(good + byteArrayOf(0x10)))
        assertFalse(IccoaGearWire.accept(good + ProtoWriter().uint32(2, 62).toByteArray()))
        assertFalse(IccoaGearWire.accept(good + byteArrayOf(0x12, 0x00)))
        assertFalse(IccoaGearWire.accept(ProtoWriter().uint32(2, 55).toByteArray() + byteArrayOf(0x08, 0x80.toByte())))
        assertFalse(IccoaGearWire.accept(ProtoWriter().enum(1, 2).toByteArray()))
        assertNull(VehicleTelemetry.current.value)
    }
    @Test fun latestGearKeepsSeparateGpsPositionAndSpeed() {
        val now = System.currentTimeMillis()
        VehicleTelemetry.publish(app.ft.core.VehicleLocation("ICCOA", now,
            latitude = 55.0, longitude = 37.0, speedRaw = 12))
        val gear = ProtoWriter().enum(1, 3).uint32(2, 61).toByteArray()
        assertTrue(IccoaGearWire.accept(gear))
        val combined = VehicleTelemetry.current.value!!
        assertEquals(12L, combined.speedRaw)
        assertEquals(61L, combined.gearSpeedRaw)
        assertEquals(3L, combined.gearRaw)
        assertEquals(55.0, combined.latitude!!, 0.0)
        VehicleTelemetry.publish(app.ft.core.VehicleLocation("ICCOA",
            System.currentTimeMillis(), latitude = 55.00001, longitude = 37.0, speedRaw = 13))
        assertEquals(3L, VehicleTelemetry.current.value?.gearRaw)
        assertEquals(61L, VehicleTelemetry.current.value?.gearSpeedRaw)
        VehicleTelemetry.clear("ICCOA")
    }

    @Test fun rejectsUnexpectedOrMalformedSensorPackets() {
        assertFalse(IccoaGearWire.accept(byteArrayOf()))
        assertFalse(IccoaGearWire.accept(ProtoWriter().int32(1, 5).uint32(2, 50).toByteArray()))
        assertFalse(IccoaGearWire.accept(ProtoWriter().uint32(2, 50).uint32(3, 1).toByteArray()))
        assertFalse(IccoaGearWire.accept(ProtoWriter().uint32(2, 100_001).toByteArray()))
    }
}
