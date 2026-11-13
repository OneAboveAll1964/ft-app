package app.ft.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionPreparationTest {
    @Test fun disabledOptionDoesNotDelayConnectionOrPrepare() = runTest {
        var prepared = 0
        var connected = 0
        val gate = ConnectionPreparation(this) { prepared++ }
        gate.run(false) { connected++ }
        assertEquals(1, connected)
        runCurrent()
        assertEquals(0, prepared)
    }

    @Test fun waitsForPreparationAndSharesResultWithTransportRetries() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gate = ConnectionPreparation(this) { events += "prepare"; ready.await() }
        gate.run(true) { events += "first" }
        runCurrent()
        assertEquals(listOf("prepare"), events)
        gate.run(true) { events += "usb" }
        ready.complete(Unit)
        runCurrent()
        gate.run(true) { events += "retry" }
        assertEquals(listOf("prepare", "usb", "retry"), events)
    }

    @Test fun unsuccessfulPreparationStillContinuesAndDoesNotRepeat() = runTest {
        val events = mutableListOf<String>()
        val gate = ConnectionPreparation(this) { events += "no helper" }
        gate.run(true) { events += "connect" }
        runCurrent()
        gate.run(true) { events += "retry" }
        assertEquals(listOf("no helper", "connect", "retry"), events)
    }

    @Test fun cancellingInterruptsPreparationAndDiscardsContinuation() = runTest {
        var cancelled = false
        var connected = false
        val gate = ConnectionPreparation(this) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        gate.run(true) { connected = true }
        runCurrent()
        gate.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(connected)
    }

    @Test fun explicitNewAttemptPreparesAgainButTransportTeardownDoesNot() = runTest {
        var prepared = 0
        var connected = 0
        val gate = ConnectionPreparation(this) { prepared++ }
        gate.run(true) { connected++ }
        runCurrent()
        gate.cancel(reset = false)
        gate.run(true) { connected++ }
        assertEquals(1, prepared)
        gate.cancel()
        gate.run(true) { connected++ }
        runCurrent()
        assertEquals(2, prepared)
        assertEquals(3, connected)
    }

    @Test fun disablingDuringPreparationCancelsPendingStart() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gate = ConnectionPreparation(this) { ready.await() }
        gate.run(true) { events += "old" }
        runCurrent()
        gate.run(false) { events += "new" }
        ready.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), events)
    }
}
