package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun gearFramesRetainLastKnownGpsButDoNotMakeItFresh() {
        val now = System.currentTimeMillis()
        val stalePosition = now - 10_000L
        VehicleTelemetry.publish(VehicleLocation(
            source = "ICCOA",
            receivedAtMs = stalePosition,
            positionAtMs = stalePosition,
            latitude = 55.7, longitude = 37.6,
            headingRaw = 120, headingDegrees = 120f,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        ))
        try {
            VehicleTelemetry.updateIccoaSpeed(6L, gearRaw = 2L)
            val result = VehicleTelemetry.current.value!!
            assertEquals(55.7, result.latitude!!, 0.000001)
            assertEquals(37.6, result.longitude!!, 0.000001)
            assertEquals(120f, result.headingDegrees!!, 0f)
            assertEquals(stalePosition, result.positionAtMs)
            assertEquals(6L, result.gearSpeedRaw)
            assertEquals(VehicleSpeedSample(6L, result.receivedAtMs), result.drivingSpeed)
            assertEquals(2L, result.gearRaw)
            assertTrue(result.fresh())
            assertFalse(result.positionFresh())
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

    @Test fun baiduGpsUpdateRetainsSeparateSpeedWithItsOriginalTimestamp() {
        try {
            VehicleTelemetry.updateBaiduSpeed(12)
            val speed = VehicleTelemetry.current.value!!.drivingSpeed!!
            val positionAt = speed.receivedAtMs + 2_000L
            VehicleTelemetry.publish(VehicleLocation(source = "BAIDU", receivedAtMs = positionAt, speedRaw = 98))

            val result = VehicleTelemetry.current.value!!
            assertEquals(speed, result.drivingSpeed)
            assertEquals(98L, result.speedRaw)
            assertFalse(result.drivingSpeed!!.fresh(speed.receivedAtMs + 3_001L))
            assertTrue(result.positionFresh(speed.receivedAtMs + 3_001L))
        } finally {
            VehicleTelemetry.clear("BAIDU")
        }
    }
}
