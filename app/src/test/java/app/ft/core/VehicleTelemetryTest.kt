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
            assertEquals(2L, result.gearRaw)
            assertTrue(result.fresh())
            assertFalse(result.positionFresh())
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }
}
