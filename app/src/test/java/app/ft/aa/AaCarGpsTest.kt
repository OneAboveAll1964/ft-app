package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class AaCarGpsTest {
    private fun car(latitude: Double, longitude: Double, receivedAtMs: Long = 1_000L) =
        VehicleLocation(source = "ICCOA", receivedAtMs = receivedAtMs,
            latitude = latitude, longitude = longitude,
            coordinatesUnit = "ANDROID_LOCATION_WGS84")

    @Test fun iccoaToAndroidAutoUsesMicrodegreesAndRequiredFields() {
        val packet = AaCarGps.event(car(55.755826, 37.617299), 1_500L)!!
        val gps = ProtoReader(packet).message(1)!!
        assertEquals(55_755_826, gps.int(2))
        assertEquals(37_617_299, gps.int(3))
        assertFalse(gps.has(1))
        assertEquals(setOf(2, 3), gps.fields.keys)
    }

    @Test fun rejectMissingStaleAndUnverifiedCoordinateReference() {
        assertNull(AaCarGps.event(null, 1_500L))
        assertNull(AaCarGps.event(car(55.75, 37.62), 4_100L))
        assertNull(AaCarGps.event(car(91.0, 20.0), 1_500L))
        assertNull(AaCarGps.event(car(0.0, 0.0), 1_500L))
        assertNull(AaCarGps.event(car(Double.NaN, 20.0), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 180.1), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 20.0).copy(coordinatesUnit = "UNKNOWN"), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 20.0).copy(source = "BAIDU"), 1_500L))
    }

    @Test fun serviceDiscoveryAdvertisesCarLocationWithOtherSensors() {
        val response = AaMessages.serviceDiscoveryResponse(
            1280, 720, 30, 160, true, "RAV4", true, advertiseCarLocation = true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val types = config.messages(1).map { it.int(1) }.toSet()
        assertTrue(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_NIGHT_MODE in types)
        assertTrue(AaProtocol.SENSOR_DRIVING_STATUS in types)
    }

    @Test fun gateRequiresThreeDistinctConsistentFixes() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.7558, 37.6172, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        assertNull(gate.accept(car(55.75582, 37.61722, 1200), 1300))
        assertNotNull(gate.accept(car(55.75583, 37.61723, 1400), 1400))
    }

    @Test fun gateRejectsTeleportAndReacquires() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // A 20 km teleport in 100 ms is impossible for the car.
        assertNull(gate.accept(car(55.9300, 37.6173, 1300), 1300))
        assertNull(gate.accept(car(55.93001, 37.61731, 1400), 1400))
        // Three wrong fixes in the same distant location remain a teleport.
        assertNull(gate.accept(car(55.93002, 37.61732, 1500), 1500))
        // Once the source is genuinely stale, fall back and reacquire cautiously.
        assertNull(gate.accept(null, 5_000))
        assertNull(gate.accept(car(55.93003, 37.61733, 5_100), 5_100))
        assertNull(gate.accept(car(55.93004, 37.61734, 5_200), 5_200))
        assertNotNull(gate.accept(car(55.93005, 37.61735, 5_300), 5_300))
    }

    @Test fun trueSensorGapAllowsReacquisitionAfterThreeFreshFixes() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // No fix between 1200 and 6000 ms, e.g. tunnel or reconnection.
        assertNull(gate.accept(car(55.9300, 37.6173, 6000), 6000))
        assertNull(gate.accept(car(55.93001, 37.61731, 6100), 6100))
        assertNotNull(gate.accept(car(55.93002, 37.61732, 6200), 6200))
    }

    @Test fun returnsToTrustedPositionAfterLongQuarantinedStream() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // Sustained false cluster for >3 seconds: the trusted anchor must survive.
        for (t in 1300L..4800L step 100L)
            assertNull(gate.accept(car(55.9300, 37.6173, t), t))
        // Three fresh, mutually plausible fixes return near the trusted point.
        assertNull(gate.accept(car(55.75583, 37.61723, 4900), 4900))
        assertNull(gate.accept(car(55.75584, 37.61724, 5000), 5000))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 5100), 5100))
    }

    @Test fun prolongedFalseCoordinateClusterNeverPassesWithoutActualLoss() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // 2 km false offset is stable for multiple seconds but remains implausible.
        for (t in 1300L..3300L step 100L) {
            val sample = car(55.77382, 37.61722, t)
            assertNull("unsafe coordinate released at $t", gate.accept(sample, t))
        }
    }

    @Test fun baiduModeMustNotAdvertiseUnusableGps() {
        val response = AaMessages.serviceDiscoveryResponse(1280, 720, 30, 160, true, "RAV4", true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val types = config.messages(1).map { it.int(1) }.toSet()
        assertFalse(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_DRIVING_STATUS in types)
    }

    @Test fun gpsSensorErrorMatchesAndroidAutoWireSchema() {
        val p = ProtoReader(AaCarGps.sensorError(2))
        assertEquals(1, p.int(1))
        assertEquals(2, p.int(2))
    }
}
