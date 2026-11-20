package app.ft.aa

import app.ft.core.CarProtocol
import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import app.ft.core.VehicleSpeedSample
import org.junit.Assert.assertEquals
import org.junit.Test

class AaDrivingStatusTest {
    @Test fun freshSpeedFromTheActiveProtocolGatesDrivingRestrictions() {
        val car = VehicleLocation(source = "BAIDU", receivedAtMs = 2_000L,
            drivingSpeed = VehicleSpeedSample(raw = 1, receivedAtMs = 1_000L))
        val status = AaDrivingStatus()
        val moving = status.update(CarProtocol.CARLIFE, car, 2_000L, restrictionsEnabled = true)
        val event = ProtoReader(AaMessages.sensorDrivingStatus(moving)).message(13)!!
        assertEquals(27, event.int(1))
        assertEquals(0, AaDrivingStatus().update(CarProtocol.ICCOA, car, 2_000L, restrictionsEnabled = true))

        val stopped = car.copy(source = "ICCOA", drivingSpeed = VehicleSpeedSample(0, 2_100L))
        assertEquals(0, status.update(CarProtocol.ICCOA, stopped, 2_100L, restrictionsEnabled = true))
    }

    @Test fun gpsUpdatesCannotSupplyOrRefreshDrivingSpeed() {
        val car = VehicleLocation(source = "ICCOA", receivedAtMs = 4_001L, speedRaw = 30L,
            drivingSpeed = VehicleSpeedSample(raw = 0, receivedAtMs = 1_000L))
        val status = AaDrivingStatus()
        assertEquals(0, status.update(CarProtocol.ICCOA, car.copy(drivingSpeed = null), 4_001L, restrictionsEnabled = true))
        val moving = car.copy(drivingSpeed = VehicleSpeedSample(1, 1_000L))
        assertEquals(27, status.update(CarProtocol.ICCOA, moving, 1_000L, restrictionsEnabled = true))
        assertEquals(27, status.update(CarProtocol.ICCOA, car, 4_001L, restrictionsEnabled = true))
    }

    @Test fun switchingRestrictionsOffClearsTheReportedFlagsAndStillTracksSpeed() {
        val car = VehicleLocation(source = "BAIDU", receivedAtMs = 1_000L,
            drivingSpeed = VehicleSpeedSample(10, 1_000L))
        val status = AaDrivingStatus()
        assertEquals(27, status.update(CarProtocol.CARLIFE, car, 1_000L, restrictionsEnabled = true))
        assertEquals(0, status.update(CarProtocol.CARLIFE, car, 1_100L, restrictionsEnabled = false))
        assertEquals(27, status.update(CarProtocol.CARLIFE, null, 4_001L, restrictionsEnabled = true))

        val stopped = car.copy(drivingSpeed = VehicleSpeedSample(0, 4_100L))
        assertEquals(0, status.update(CarProtocol.CARLIFE, stopped, 4_100L, restrictionsEnabled = false))
        assertEquals(0, status.update(CarProtocol.CARLIFE, null, 7_101L, restrictionsEnabled = true))
    }
}
