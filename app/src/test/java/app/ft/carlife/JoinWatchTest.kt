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
    fun thisMorningsHangWouldHaveBeenRetriedAtFiveSeconds() {
        val firstTry = listOf(1_000L, 3_000L, 5_000L).map { JoinWatch.next(it, carConnected = false, groupInterfaceUp = false) }
        assertEquals(listOf(JoinWatch.Next.WAIT, JoinWatch.Next.WAIT, JoinWatch.Next.RETRY), firstTry)
    }
}
