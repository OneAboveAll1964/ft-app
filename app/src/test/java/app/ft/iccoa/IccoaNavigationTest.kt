package app.ft.iccoa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class IccoaNavigationTest {
    @Test fun activeNavigationTellsCarLinkSensorManagerToStart() {
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(
            active = true, road = "Tverskaya", distanceMeters = 850)))
        assertTrue(p.bool(1))
        assertEquals("850", p.string(3))
        assertEquals("m", p.string(4))
        assertEquals("Tverskaya", p.string(6))
        assertEquals(11, IccoaNavigation.CONTROL_METHOD)
    }
    @Test fun aaProjectionAsksForSensorWithoutClaimingNavigationRoute() {
        val inactive = NavigationInfo()
        val request = IccoaNavigation.sensorActivation(inactive, aaConnected = true)
        assertTrue(ProtoReader(IccoaNavigation.encode(request)).bool(1))
        assertEquals("", request.road)
        assertNull(request.distanceMeters)
        assertFalse(IccoaNavigation.sensorActivation(inactive, aaConnected = false).active)
    }

    @Test fun actualGuidedRouteRemainsActiveEvenWhenAaVisibilityChanges() {
        val guided = NavigationInfo(active = true, road = "Route", distanceMeters = 850)
        assertEquals(guided, IccoaNavigation.sensorActivation(guided, aaConnected = true))
        assertEquals(guided, IccoaNavigation.sensorActivation(guided, aaConnected = false))
    }

    @Test fun inactiveNavigationTellsCarLinkSensorManagerToStop() {
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(active = false)))
        assertFalse(p.bool(1))
    }
}
