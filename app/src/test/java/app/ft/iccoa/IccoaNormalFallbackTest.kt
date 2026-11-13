package app.ft.iccoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaNormalFallbackTest {
    private val previous = IccoaPeer("OLD", "Toyota", -50, true, true, "receiver", 1_000L)

    @Test fun rejectionRequiresKnownSelectedPeer() {
        val fallback = IccoaNormalFallback()
        assertFalse(fallback.begin(null, 2_000L))
        assertFalse(fallback.active)
        assertFalse(fallback.begin(previous.copy(address = ""), 2_000L))
    }

    @Test fun quickAdvertisementCannotBeReusedEvenIfStillWithinFreshnessWindow() {
        val fallback = IccoaNormalFallback()
        assertTrue(fallback.begin(previous, 2_000L))
        assertNull(fallback.select(listOf(previous), false, 2_100L))
        assertTrue(fallback.active)
    }

    @Test fun currentTokenAddressIsSelectedOnceAfterNewScan() {
        val fallback = IccoaNormalFallback()
        fallback.begin(previous, 2_000L)
        val current = previous.copy(address = "CURRENT", lastSeenElapsedMs = 2_100L)
        assertEquals(current, fallback.select(listOf(previous, current), false, 2_200L))
        assertFalse(fallback.active)
        assertNull(fallback.select(listOf(current), true, 2_300L))
    }

    @Test fun manualCancellationPreventsLateScanFromStartingNormal() {
        val fallback = IccoaNormalFallback()
        fallback.begin(previous, 2_000L)
        fallback.cancel()
        assertNull(fallback.select(listOf(previous.copy(lastSeenElapsedMs = 2_100L)), true, 2_200L))
        assertFalse(fallback.active)
    }

    @Test fun freshAddressWithDifferentTokenCannotReuseOldMac() {
        val fallback = IccoaNormalFallback()
        fallback.begin(previous, 2_000L)
        assertNull(fallback.select(listOf(previous.copy(receiverToken = "other",
            lastSeenElapsedMs = 2_100L)), true, 2_200L))
    }

    @Test fun freshScanStillRejectsStaleAndAmbiguousAdvertisements() {
        val fallback = IccoaNormalFallback()
        fallback.begin(previous, 2_000L)
        val current = previous.copy(address = "CURRENT", lastSeenElapsedMs = 2_100L)
        assertNull(fallback.select(listOf(current), true, 7_000L))
        assertNull(fallback.select(listOf(current, current.copy(address = "OTHER")), true, 2_200L))
    }

    @Test fun sameLegacyAddressRequiresNewAdvertisement() {
        val fallback = IccoaNormalFallback()
        val legacy = previous.copy(receiverToken = "")
        fallback.begin(legacy, 2_000L)
        assertNull(fallback.select(listOf(legacy), true, 2_100L))
        val refreshed = legacy.copy(lastSeenElapsedMs = 2_200L)
        assertEquals(refreshed, fallback.select(listOf(refreshed), false, 2_300L))
    }
    @Test fun manualQuickRejectionMayContinueToNormalWithAutoConnectOff() {
        assertTrue(IccoaNormalFallbackAdmission.allows(false, true, true, true))
        val fallback = IccoaNormalFallback()
        assertTrue(fallback.begin(previous, 2_000L))
        val fresh = previous.copy(address = "ROTATED", lastSeenElapsedMs = 2_100L)
        assertEquals(fresh, fallback.select(listOf(fresh), false, 2_200L))
        assertFalse(fallback.active)
    }

    @Test fun normalFallbackAdmissionRequiresOwnerStartedEpisodeOrAutoConnect() {
        assertFalse(IccoaNormalFallbackAdmission.allows(false, false, true, true))
        assertTrue(IccoaNormalFallbackAdmission.allows(true, false, true, true))
        assertFalse(IccoaNormalFallbackAdmission.allows(true, true, false, true))
        assertFalse(IccoaNormalFallbackAdmission.allows(true, true, true, false))
        assertFalse(IccoaNormalFallbackAdmission.allows(false, true, false, true))
    }
}
