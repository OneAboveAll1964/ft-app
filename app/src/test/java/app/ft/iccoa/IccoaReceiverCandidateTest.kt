package app.ft.iccoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaReceiverCandidateTest {
    private val marker = ByteArray(16) { if (it < 10) (0x20 + it).toByte() else 0 }
    private val token = IccoaEvidence.receiverToken(marker)

    private fun peer(address: String, seen: Long, tokenValue: String = token, hint: Boolean = true,
                     connected: Boolean = true) =
        IccoaPeer(address, "", -50, connected, hint, tokenValue, seen)

    @Test fun correctStableTokenIsReadOnlyFromExpectedFieldShape() {
        assertEquals(32, token.length)
        assertEquals("", IccoaEvidence.receiverToken(null))
        assertEquals("", IccoaEvidence.receiverToken(ByteArray(16)))
        assertEquals("", IccoaEvidence.receiverToken(ByteArray(12)))
    }

    @Test fun randomizedBleAddressDoesNotPreventCarSelection() {
        val peers = listOf(peer("AA:00:00:00:00:01", 11_000L),
            peer("BB:00:00:00:00:02", 14_000L),
            peer("CC:00:00:00:00:03", 16_000L))
        assertEquals("CC:00:00:00:00:03", IccoaReceiverCandidate.select(peers, token, 16_100L)?.address)
    }

    @Test fun neverSelectsUnknownOrUnhintedReceiver() {
        assertNull(IccoaReceiverCandidate.select(listOf(peer("01", 12_000L, "other")), token, 12_100L))
        assertNull(IccoaReceiverCandidate.select(listOf(peer("01", 12_000L, hint = false)), token, 12_100L))
        assertNull(IccoaReceiverCandidate.select(listOf(peer("01", 12_000L, connected = false)), token, 12_100L))
    }

    @Test fun staleOrFutureAdvertisementIsNotAutoSelected() {
        assertNull(IccoaReceiverCandidate.select(listOf(peer("01", 5_000L)), token, 10_001L))
        assertNull(IccoaReceiverCandidate.select(listOf(peer("01", 10_100L)), token, 10_000L))
    }

    @Test fun markerCannotReplaceCryptographicIdentity() {
        // A forged marker is only a GATT candidate: IccoaAuth verifies the
        // signed ECU server agreement against the stored OEM EC public key.
        val attacker = peer("66:66:66:66:66:66", 20_000L)
        assertTrue(IccoaReceiverCandidate.select(listOf(attacker), token, 20_100L) === attacker)
    }

    @Test fun freshTokenWinsOverRememberedAddressAfterRotation() {
        val old = peer("OLD", 18_000L)
        val current = peer("CURRENT", 20_000L)
        assertEquals(current, IccoaReceiverCandidate.selectRemembered(
            listOf(old, current), "OLD", "Toyota", token, false, 20_100L))
    }

    @Test fun equallyRecentTokenCandidatesAreAmbiguous() {
        val peers = listOf(peer("OLD", 20_000L), peer("CURRENT", 20_000L))
        assertNull(IccoaReceiverCandidate.select(peers, token, 20_100L))
        assertNull(IccoaReceiverCandidate.selectRemembered(peers, "OLD", "", token, true, 20_100L))
    }

    @Test fun knownTokenCannotFallBackToDifferentTokenAtSavedAddress() {
        assertNull(IccoaReceiverCandidate.selectRemembered(
            listOf(peer("OLD", 20_000L, "other")), "OLD", "", token, true, 20_100L))
    }

    @Test fun legacyAddressMustHaveFreshAdvertisement() {
        assertNull(IccoaReceiverCandidate.selectRemembered(
            listOf(peer("OLD", 15_000L, "")), "OLD", "", "", true, 20_100L))
        val fresh = peer("OLD", 20_000L, "")
        assertEquals(fresh, IccoaReceiverCandidate.selectRemembered(
            listOf(fresh), "OLD", "", "", false, 20_100L))
    }

    @Test fun legacyNameRequiresCompletedScanAndUniqueFreshCandidate() {
        val current = peer("CURRENT", 20_000L, "").copy(name = "Toyota")
        assertNull(IccoaReceiverCandidate.selectRemembered(
            listOf(current), "OLD", "Toyota", "", false, 20_100L))
        assertEquals(current, IccoaReceiverCandidate.selectRemembered(
            listOf(current), "OLD", " Toyota ", "", true, 20_100L))
        assertNull(IccoaReceiverCandidate.selectRemembered(
            listOf(current, current.copy(address = "OTHER")), "OLD", "Toyota", "", true, 20_100L))
    }
}
