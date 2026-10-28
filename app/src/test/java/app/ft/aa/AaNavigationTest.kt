package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class AaNavigationTest {
    private fun state(type: Int, road: String = "Lenina") = ProtoWriter()
        .message(1, ProtoWriter().message(1, ProtoWriter().enum(1, type))
            .message(2, ProtoWriter().string(1, road))).toByteArray()

    private fun position(meters: Int) = ProtoWriter().message(1,
        ProtoWriter().message(1, ProtoWriter().int32(1, meters)).int64(2, 25)).toByteArray()

    @Test fun discoveryAdvertisesEnumNavigationWithOneSecondInterval() {
        val discovery = ProtoReader(AaMessages.serviceDiscoveryResponse(800, 480, 30, 160, false, "FT"))
        val service = discovery.messages(1).single { it.int(1) == AaProtocol.CH_NAVIGATION }
        assertEquals(1000, service.message(8)?.int(1))
        assertEquals(2, service.message(8)?.int(2))
    }

    @Test fun modernWireFixtureCombinesManeuverWithSeparateDistance() {
        val nav = AaNavigation()
        val turn = byteArrayOf(10, 14, 10, 2, 8, 8, 18, 8, 10, 6, 76, 101, 110, 105, 110, 97)
        assertNull(nav.accept(AaProtocol.NAV_STATE, turn)?.distanceMeters)
        val info = nav.accept(AaProtocol.NAV_CURRENT_POSITION, byteArrayOf(10, 7, 10, 3, 8, -84, 2, 16, 25))!!
        assertTrue(info.active)
        assertEquals(8, info.maneuver)
        assertEquals("Lenina", info.road)
        assertEquals(300, info.distanceMeters)
        assertEquals(25, info.timeSeconds)
    }

    @Test fun legacyMessagesAreSupportedUntilModernFormatIsSeen() {
        val nav = AaNavigation()
        val legacy = ProtoWriter().string(1, "Old road").enum(2, 1).enum(3, 4).toByteArray()
        assertEquals(7, nav.accept(AaProtocol.NAV_NEXT_TURN, legacy)?.maneuver)
        assertEquals(100, nav.accept(AaProtocol.NAV_DISTANCE, byteArrayOf(8, 100))?.distanceMeters)
        nav.accept(AaProtocol.NAV_STATE, state(8))
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN, legacy))
        assertNull(nav.accept(AaProtocol.NAV_DISTANCE, byteArrayOf(8, 50)))
    }

    @Test fun changedManeuverDoesNotInheritPreviousDistance() {
        val nav = AaNavigation()
        nav.accept(AaProtocol.NAV_STATE, state(8))
        nav.accept(AaProtocol.NAV_CURRENT_POSITION, position(300))
        assertEquals(300, nav.accept(AaProtocol.NAV_STATE, state(8))?.distanceMeters)
        val next = nav.accept(AaProtocol.NAV_STATE, state(7, "Next road"))!!
        assertNull(next.distanceMeters)
        assertEquals(0, next.timeSeconds)
    }

    @Test fun endingRejectsLateUpdatesAndActiveStatusAllowsANewRoute() {
        val nav = AaNavigation()
        nav.accept(AaProtocol.NAV_STATE, state(8))
        assertFalse(nav.accept(AaProtocol.NAV_STATUS, byteArrayOf(8, 2))!!.active)
        assertNull(nav.accept(AaProtocol.NAV_CURRENT_POSITION, position(300)))
        assertNull(nav.accept(AaProtocol.NAV_STATE, state(8)))
        nav.accept(AaProtocol.NAV_STATUS, byteArrayOf(8, 1))
        assertTrue(nav.accept(AaProtocol.NAV_STATE, state(7))!!.active)
    }

    @Test fun reroutingAndDisconnectClearTheOldTurn() {
        val nav = AaNavigation()
        nav.accept(AaProtocol.NAV_STATE, state(8))
        val rerouting = nav.accept(AaProtocol.NAV_STATUS, byteArrayOf(8, 3))!!
        assertTrue(rerouting.active)
        assertEquals(0, rerouting.maneuver)
        assertNull(rerouting.distanceMeters)
        assertFalse(nav.reset().active)
        assertNotNull(nav.accept(AaProtocol.NAV_NEXT_TURN,
            ProtoWriter().string(1, "Road").enum(3, 14).toByteArray()))
    }

    @Test fun incompleteOrNegativeDistanceDoesNotCreateAPhantomRoute() {
        val nav = AaNavigation()
        assertNull(nav.accept(AaProtocol.NAV_STATUS, byteArrayOf()))
        assertNull(nav.accept(AaProtocol.NAV_STATE, byteArrayOf(10, 20, 10)))
        assertNull(nav.accept(AaProtocol.NAV_CURRENT_POSITION, position(-1)))
        assertNull(nav.accept(AaProtocol.NAV_NEXT_TURN, byteArrayOf()))
    }
}
