package app.ft.aa

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AaReconnectTest {
    @Test fun eofWakesTheWorkerImmediatelyAndBurstNotificationsAreConflated() = runTest {
        val reconnect = AaReconnect()
        val attempts = mutableListOf<Long>()
        backgroundScope.launch { reconnect.run { attempts += testScheduler.currentTime; true } }
        runCurrent()
        advanceTimeBy(73)
        repeat(10) { reconnect.wake() }
        runCurrent()
        assertEquals(listOf(0L, 73L), attempts)
        advanceTimeBy(2999)
        runCurrent()
        assertEquals(2, attempts.size)
    }

    @Test fun failedAttemptsBackOffAndCancellationStopsRecovery() = runTest {
        val reconnect = AaReconnect()
        val attempts = mutableListOf<Long>()
        val job = backgroundScope.launch { reconnect.run { attempts += testScheduler.currentTime; false } }
        runCurrent()
        advanceTimeBy(8000)
        runCurrent()
        assertEquals(listOf(0L, 250L, 750L, 1750L, 4750L, 7750L), attempts)
        job.cancel()
        reconnect.wake()
        advanceTimeBy(10000)
        runCurrent()
        assertEquals(6, attempts.size)
    }
}
