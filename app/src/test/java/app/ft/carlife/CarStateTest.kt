package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarStateTest {
    private val waiting = "Waiting for the car to call over bluetooth"

    @Test fun wifiNameFromThisAttemptKeepsTheCarFoundAfterSppCloses() {
        val announced = CarState(
            running = true,
            direct = true,
            btCar = "RAV4",
            carWifi = "Android_2e61",
            step = "Joining the car WiFi Direct"
        )

        val afterSppClosed = announced.withBluetoothCar(null)
        assertTrue(afterSppClosed.carFound)
        assertFalse(afterSppClosed.wifiDirect)
        assertSame(afterSppClosed, afterSppClosed.withBluetoothStep(waiting))
    }

    @Test fun aFreshAttemptDoesNotClaimTheCarIsFound() {
        val fresh = CarState(running = true, direct = true)
        assertFalse(fresh.carFound)
        assertEquals(waiting, fresh.withBluetoothStep(waiting).step)
        assertFalse(fresh.copy(carWifi = " ").carFound)
    }

    @Test fun bluetoothListenerCannotRegressAnEstablishedConnectionStage() {
        val stages = listOf(
            CarState(btCar = "RAV4"),
            CarState(carWifi = "Android_2e61"),
            CarState(wifiDirect = true),
            CarState(session = CarLifeSession.State.Linked("WiFi")),
            CarState(session = CarLifeSession.State.Negotiated("WiFi", 1920, 720, 30)),
            CarState(session = CarLifeSession.State.Projecting("WiFi", 1920, 720, 30))
        )
        for (stage in stages) {
            val current = stage.copy(step = "Current connection stage")
            assertTrue(current.carFound)
            assertSame(current, current.withBluetoothStep(waiting))
            assertSame(current, current.withBluetoothStep("Turn on bluetooth"))
        }
    }

    @Test fun sessionProgressReplacesTheOldBluetoothDiagnostic() {
        val waitingState = CarState(step = waiting, refused = 1, carWifiRecoveryNeeded = true)
        val linked = waitingState.withSessionState(CarLifeSession.State.Linked("WiFi"), "WiFi")
        assertEquals("CarLife connected via WiFi", linked.step)
        assertEquals("WiFi", linked.link)
        assertEquals(0, linked.refused)
        assertFalse(linked.carWifiRecoveryNeeded)
        assertTrue(linked.carFound)

        val negotiated = linked.withSessionState(
            CarLifeSession.State.Negotiated("WiFi", 1920, 720, 30), "WiFi"
        )
        assertEquals("CarLife video configured via WiFi", negotiated.step)
        val projecting = negotiated.withSessionState(
            CarLifeSession.State.Projecting("WiFi", 1920, 720, 30), "WiFi"
        )
        assertEquals("CarLife projecting via WiFi", projecting.step)
        assertTrue(projecting.session is CarLifeSession.State.Projecting)
        assertSame(projecting, projecting.withBluetoothStep(waiting))
        assertEquals(projecting.step, projecting.withBluetoothCar("RAV4").step)
        assertEquals(projecting.session, projecting.withBluetoothCar("RAV4").session)
    }

    @Test fun clearingTheAttemptAllowsBluetoothWaitingAgain() {
        val previous = CarState(carWifi = "Android_2e61", step = "Joining the car WiFi Direct")
        val restarted = previous.copy(btCar = null, carWifi = null, wifiDirect = false)
        assertFalse(restarted.carFound)
        assertEquals(waiting, restarted.withBluetoothStep(waiting).step)
    }
    @Test fun aNewBluetoothCallerMustSupplyItsOwnWifiAnnouncement() {
        val previous = CarState(carWifi = "OldCar", btCar = "RAV4", step = "Joining OldCar")
        val next = previous.withBluetoothCar("Other car")
        assertEquals("Other car", next.btCar)
        assertEquals(null, next.carWifi)
        assertEquals("'Other car' called over bluetooth", next.step)
    }

    @Test fun aFailedAttemptExpiresTheAnnouncementAfterSppHasClosed() {
        val announced = CarState(carWifi = "Android_2e61", step = "Joining the car WiFi Direct")
        val failed = announced.withWirelessAttemptEnded()
        assertFalse(failed.carFound)
        assertEquals(null, failed.carWifi)
        assertEquals(waiting, failed.step)
        assertEquals(waiting, failed.withBluetoothStep(waiting).step)
    }

    @Test fun endingAnAttemptPreservesEvidenceOfALiveBluetoothCallerOrWifiGroup() {
        val bluetooth = CarState(btCar = "RAV4", carWifi = "Android_2e61")
        val ended = bluetooth.withWirelessAttemptEnded()
        assertTrue(ended.carFound)
        assertEquals("RAV4", ended.btCar)
        assertEquals(null, ended.carWifi)
        val group = CarState(wifiDirect = true, carWifi = "Android_2e61")
            .withWirelessAttemptEnded()
        assertTrue(group.carFound)
        assertTrue(group.wifiDirect)
        assertEquals(null, group.carWifi)
    }

    @Test fun sessionCloseReturnsToTheRemainingConnectionStage() {
        val projecting = CarState(session = CarLifeSession.State.Projecting("WiFi", 1920, 720, 30),
            carWifi = "Android_2e61", step = "CarLife projecting via WiFi")
        val closed = projecting.withWirelessAttemptEnded()
            .withSessionState(CarLifeSession.State.Idle, "WiFi")
        assertFalse(closed.carFound)
        assertEquals(waiting, closed.step)
        val groupKept = projecting.copy(wifiDirect = true).withWirelessAttemptEnded()
            .withSessionState(CarLifeSession.State.Idle, "WiFi")
        assertTrue(groupKept.carFound)
        assertEquals("WiFi Direct connected", groupKept.step)
    }

    @Test fun sppClosingAfterAFailedJoinCannotReviveTheExpiredAnnouncement() {
        val announced = CarState(btCar = "RAV4", carWifi = "Android_2e61")
        val failed = announced.withWirelessAttemptEnded()
        assertTrue(failed.carFound)
        val sppClosed = failed.withBluetoothCar(null)
        assertFalse(sppClosed.carFound)
        assertEquals(null, sppClosed.carWifi)
    }

}
