package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleLogSamplerTest {
    @Test fun ordinaryDriveLogsOnlyEveryTenSeconds() {
        val sampler = VehicleLogSampler()
        val start = sampler.accept("ICCOA", 1_000, true)
        assertEquals("PERIODIC", start?.reason)
        assertFalse(start!!.fast)
        for (t in 1_100L until 11_000L step 100L)
            assertNull("unexpected sample at $t", sampler.accept("ICCOA", t, true))
        assertEquals("PERIODIC", sampler.accept("ICCOA", 11_000, true)?.reason)
    }

    @Test fun fixLossAndRecoveryTriggerOneHzBurst() {
        val sampler = VehicleLogSampler()
        sampler.accept("ICCOA", 1_000, true)
        var t = 1_100L
        while (t < 4_000L) { sampler.accept("ICCOA", t, true); t += 100 }
        val lost = sampler.accept("ICCOA", 4_000L, false)
        assertEquals("FIX_LOST", lost?.reason)
        assertTrue(lost!!.fast)
        assertNull(sampler.accept("ICCOA", 4_100L, false))
        assertEquals("PERIODIC", sampler.accept("ICCOA", 5_000L, false)?.reason)
        assertNull("events must not bypass 1Hz cap", sampler.accept("ICCOA", 5_100L, true))
        var last = 5_000L
        t = 5_200L
        while (t <= 25_100L) {
            val got = sampler.accept("ICCOA", t, true)
            if (got != null) {
                assertTrue("event sampler exceeded 1 Hz", t - last >= 1_000L)
                last = t
            }
            t += 100L
        }
    }

    @Test fun interruptedStreamTriggersBurstOnReturn() {
        val sampler = VehicleLogSampler()
        sampler.accept("BAIDU", 1_000L, true)
        assertEquals("STREAM_RESUMED", sampler.accept("BAIDU", 6_000L, true)?.reason)
        assertTrue(sampler.accept("BAIDU", 7_000L, true)!!.fast)
    }

    @Test fun rapidTransitionsNeverWriteMoreThanOncePerSecond() {
        val sampler = VehicleLogSampler()
        var records = 0
        var last = -1_000L
        for (tick in 0L..10_000L step 100L) {
            val source = if (tick % 200L == 0L) "ICCOA" else "BAIDU"
            val result = sampler.accept(source, tick, tick % 300L != 0L)
            if (result != null) {
                assertTrue("samples too frequent at $tick", tick - last >= 1_000L)
                last = tick
                records++
            }
        }
        assertTrue(records in 2..11)
    }

    @Test fun sourceChangeAndDisconnectReset() {
        val sampler = VehicleLogSampler()
        sampler.accept("BAIDU", 1_000L, true)
        assertNull(sampler.accept("ICCOA", 1_100L, true))
        assertEquals("SOURCE_SWITCH", sampler.accept("ICCOA", 2_000L, true)?.reason)
        sampler.reset()
        assertEquals("PERIODIC", sampler.accept("ICCOA", 1_200L, true)?.reason)
    }
}
