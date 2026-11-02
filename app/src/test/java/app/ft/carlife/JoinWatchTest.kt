package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinWatchTest {
    @Test
    fun aJoinIsLeftAloneForItsFirstFiveSeconds() {
        assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(4_999, carConnected = false, groupInterfaceUp = false))
    }

    @Test
    fun aJoinStillOnlyInvitedAfterFiveSecondsIsRetried() {
        assertEquals(JoinWatch.Next.RETRY, JoinWatch.next(5_000, carConnected = false, groupInterfaceUp = false))
    }

    @Test
    fun aJoinThatReachedTheCarGetsTimeForItsAddress() {
        assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(9_000, carConnected = true, groupInterfaceUp = false))
        assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(9_000, carConnected = false, groupInterfaceUp = true))
    }

    @Test
    fun nothingWaitsPastFifteenSeconds() {
        assertEquals(JoinWatch.Next.RETRY, JoinWatch.next(15_000, carConnected = true, groupInterfaceUp = true))
    }

    @Test
    fun theCarSayingItIsReadyNeverInterruptsAJoinAlreadyInFlight() {
        assertTrue(JoinWatch.actOnCarReady(attempting = false))
        assertFalse(JoinWatch.actOnCarReady(attempting = true))
    }

    @Test
    fun theWiFiDirectChannelIsRenewedEveryThirdFailure() {
        assertFalse(JoinWatch.renewChannel(0))
        assertFalse(JoinWatch.renewChannel(2))
        assertTrue(JoinWatch.renewChannel(3))
        assertFalse(JoinWatch.renewChannel(4))
        assertTrue(JoinWatch.renewChannel(6))
    }

    @Test
    fun theFirstRetriesAreQuickThenEveryFiveSeconds() {
        assertEquals(2_500L, JoinWatch.retryDelayMs(1))
        assertEquals(2_500L, JoinWatch.retryDelayMs(2))
        assertEquals(5_000L, JoinWatch.retryDelayMs(3))
        assertEquals(5_000L, JoinWatch.retryDelayMs(10))
    }

    @Test
    fun theCarIsAskedAgainOnlyAfterThirtySecondsOutOfSight() {
        assertFalse(JoinWatch.askCarAgain(29_999, carInSight = false))
        assertTrue(JoinWatch.askCarAgain(30_000, carInSight = false))
        assertFalse(JoinWatch.askCarAgain(90_000, carInSight = true))
    }

    @Test
    fun configuredTimeoutsAllowTheFullWaitThenRetry() {
        for (seconds in JoinWatch.TIMEOUT_SECONDS + listOf(1, 6, 17, 90, 120, 600)) {
            val timeout = seconds * 1_000L
            assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(timeout - 1, false, false, timeoutMs = timeout))
            assertEquals(JoinWatch.Next.RETRY, JoinWatch.next(timeout, false, false, timeoutMs = timeout))
            assertEquals(JoinWatch.Next.RETRY, JoinWatch.next(maxOf(timeout, JoinWatch.GIVE_UP_MS), true, true, timeoutMs = timeout))
        }
    }

    @Test
    fun aLongTimeoutIsNotCutShortByTheOldFifteenSecondLimit() {
        assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(15_000, true, true, timeoutMs = 30_000))
        assertEquals(JoinWatch.Next.WAIT, JoinWatch.next(29_999, false, false, timeoutMs = 30_000))
    }

    @Test
    fun invalidSavedTimeoutsFallBackToTheOriginalDefault() {
        for (seconds in listOf(Int.MIN_VALUE, -1, 0, 601, Int.MAX_VALUE)) {
            assertEquals(5, JoinWatch.timeoutSeconds(seconds))
        }
        for (seconds in JoinWatch.TIMEOUT_SECONDS) assertEquals(seconds, JoinWatch.timeoutSeconds(seconds))
    }

    @Test
    fun customTimeoutsAreKeptInsteadOfBeingReplacedWithFiveSeconds() {
        for (seconds in 1..600) assertEquals(seconds, JoinWatch.timeoutSeconds(seconds))
    }

    @Test
    fun thisMorningsHangWouldHaveBeenRetriedAtFiveSeconds() {
        val firstTry = listOf(1_000L, 3_000L, 5_000L).map { JoinWatch.next(it, carConnected = false, groupInterfaceUp = false) }
        assertEquals(listOf(JoinWatch.Next.WAIT, JoinWatch.Next.WAIT, JoinWatch.Next.RETRY), firstTry)
    }
}
