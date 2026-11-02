package app.ft.iccoa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoWriter

/**
 * FAW RAV4 OEM Carlink.apk ucar.proto ControlIndex.notify_navigation_info = 11.
 *
 * CarLinkAdapter's onNavigationInfoReceived toggles CarLinkSensorMgr on/off
 * based on isNavigating. That SDK path produces GPS through UCarAdapter.
 * Explicitly model the difference between a guided route and an AA projection
 * sensor activation request; this control message is an OEM P1 hypothesis,
 * not proof that the HU will actually provide GPS.
 */
internal object IccoaNavigation {
    const val CONTROL_METHOD = 11

    /** Enable the OEM GPS producer only during a live AA projection or guided route.
     * This is not asserted to be a user-started turn-by-turn navigation session.
     */
    fun sensorActivation(info: NavigationInfo, aaConnected: Boolean): NavigationInfo =
        if (aaConnected && !info.active) info.copy(active = true) else info

    fun encode(info: NavigationInfo): ByteArray = ProtoWriter()
        .bool(1, info.active)
        .string(3, info.distanceMeters?.coerceAtLeast(0)?.toString() ?: "")
        .string(4, "m")
        .string(6, info.road)
        .toByteArray()
}
