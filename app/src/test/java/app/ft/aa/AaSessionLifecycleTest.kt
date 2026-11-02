package app.ft.aa

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AaSessionLifecycleTest {
    @Test fun closeBeforeStartIsTerminalAndCleansUpOnce() {
        val reasons = mutableListOf<String>()
        var interrupts = 0
        val lifecycle = AaSessionLifecycle({ interrupts++ }, { reasons += it })
        lifecycle.close("stopped")
        assertFalse(lifecycle.start())
        lifecycle.close("late EOF")
        lifecycle.dispatch { fail("a frame arrived after close") }
        assertEquals(listOf("stopped"), reasons)
        assertEquals(1, interrupts)
    }

    @Test fun writeFailureInsideDispatchDefersMediaCleanupUntilHandlerReturns() {
        val events = mutableListOf<String>()
        val lifecycle = AaSessionLifecycle({ events += "interrupt" }, { events += "cleanup:$it" })
        assertTrue(lifecycle.start())
        lifecycle.dispatch {
            events += "audio ACK"
            lifecycle.close("write failed")
            events += "remaining audio handler"
            assertFalse(lifecycle.running)
        }
        lifecycle.dispatch { fail("late video would revive the old decoder") }
        lifecycle.close("reader finally")
        assertEquals(listOf("audio ACK", "interrupt", "remaining audio handler", "cleanup:write failed"), events)
        assertFalse(lifecycle.start())
    }

    @Test fun externalCloseInterruptsIoButWaitsForInFlightDispatchBeforeReleasingOwner() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val lifecycle = AaSessionLifecycle({ interrupted.countDown() }, { cleaned.countDown() })
        lifecycle.start()
        try {
            val reader = executor.submit {
                lifecycle.dispatch {
                    entered.countDown()
                    check(leave.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, cleaned.count)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closer = executor.submit { lifecycle.close("EOF") }
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertFalse(lifecycle.running)
            assertEquals(1L, cleaned.count)
            leave.countDown()
            reader.get(5, TimeUnit.SECONDS)
            closer.get(5, TimeUnit.SECONDS)
            assertEquals(0L, cleaned.count)
            lifecycle.dispatch { fail("buffered frame was dispatched after cleanup") }
        } finally {
            leave.countDown()
            executor.shutdownNow()
        }
    }
}
