package app.ft.carlife

import app.ft.core.ProtoWriter
import app.ft.core.VehicleTelemetry
import org.junit.Assert.*
import org.junit.Test

class CarLifeCarSensorsTest {
    @Test fun baiduGpsUsesItsOwnIntegerProtoFields() {
        val frame = ProtoWriter()
            .uint32(1, 1).uint32(2, 3)
            .int32(3, 5_553_400).int32(4, 3_753_950)
            .int32(5, 172).uint32(6, 42).uint32(7, 250)
            .uint32(18, 12).uint32(19, 33).uint64(25, 1700000000L)
            .toByteArray()
        assertTrue(CarLifeCarSensors.onGps(frame))
        val sample = VehicleTelemetry.current.value!!
        assertEquals("BAIDU", sample.source)
        assertEquals(5_553_400L, sample.rawLatitude)
        assertEquals(3_753_950L, sample.rawLongitude)
        assertNull(sample.latitude)
        assertNull(sample.longitude)
        assertEquals("UNKNOWN", sample.coordinatesUnit)
        assertEquals(42L, sample.speedRaw)
        assertEquals(250L, sample.headingRaw)
        VehicleTelemetry.clear("BAIDU")
    }

    @Test fun velocityFrameUpdatesSameSharedStream() {
        assertTrue(CarLifeCarSensors.onSpeed(ProtoWriter().int32(1, 51).uint64(2, 1234567).toByteArray()))
        assertEquals(51L, VehicleTelemetry.current.value?.speedRaw)
        assertEquals("BAIDU", VehicleTelemetry.current.value?.source)
        VehicleTelemetry.clear("BAIDU")
    }

    @Test fun wrongOrMissingProtoIsIgnored() {
        assertFalse(CarLifeCarSensors.onGps(byteArrayOf()))
        assertFalse(CarLifeCarSensors.onGps(ProtoWriter().int32(3, 23).toByteArray()))
        assertFalse(CarLifeCarSensors.onSpeed(ProtoWriter().int32(1, -4).toByteArray()))
        assertFalse(CarLifeCarSensors.onGps(ByteArray(513)))
    }
}
