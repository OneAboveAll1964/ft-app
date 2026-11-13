package app.ft.projection

import android.graphics.SurfaceTexture
import android.os.Handler
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class FrameGateTest {
    @Test fun idleSleepsBetweenKeepalivesAndFreshFramePreemptsTheWait() {
        val f = Scheduler()
        repeat(8) { f.due() }
        assertEquals(8, f.count("repeats"))
        assertEquals(1, f.delayed.size)
        assertEquals(20_000L, f.delayed.single().second)
        f.due()
        assertEquals("keepalive is still sent", 9, f.count("repeats"))
        assertEquals(20_000L, f.delayed.single().second)
        // A new texture arriving just after a keepalive must wait only one frame.
        f.set("lastNs", System.nanoTime())
        f.invoke("onFrame")
        assertEquals(1, f.delayed.size)
        assertTrue(f.delayed.single().second in 1L..1000L)
        assertEquals(0, f.count("empty"))
        f.due()
        assertEquals(1, f.count("newFrames"))
        assertEquals(9, f.count("repeats"))
    }

    @Test fun touchAndRateChangeReplaceIdleDeadlineWithoutMultiplyingTicks() {
        val f = Scheduler()
        repeat(8) { f.due() }
        f.gate.wake(2500)
        f.flush()
        assertEquals(1, f.delayed.size)
        assertTrue(f.delayed.single().second in 1L..1000L)
        f.due()
        assertEquals(1000L, f.delayed.single().second)
        f.set("awakeUntilNs", 0L)
        repeat(8) { f.due() }
        assertEquals(20_000L, f.delayed.single().second)
        f.gate.setRate(2)
        f.flush()
        assertEquals(1, f.delayed.size)
        assertTrue(f.delayed.single().second in 1L..500L)
    }

    private class Scheduler {
        val gate = mock(FrameGate::class.java, CALLS_REAL_METHODS)
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayList<Pair<Runnable, Long>>()
        init {
            val handler = mock(Handler::class.java)
            doAnswer { immediate.add(it.getArgument(0)); true }.`when`(handler).post(any(Runnable::class.java))
            doAnswer { delayed.add(it.getArgument<Runnable>(0) to it.getArgument<Long>(1)); true }
                .`when`(handler).postDelayed(any(Runnable::class.java), anyLong())
            doAnswer { call -> delayed.removeAll { it.first === call.getArgument<Runnable>(0) }; null }
                .`when`(handler).removeCallbacks(any(Runnable::class.java))
            set("tick", Runnable { invoke("onTick") })
            set("tag", "GateTest")
            set("matrix", FloatArray(16))
            set("reportedNs", System.nanoTime())
            set("handler", handler)
            set("texture", mock(SurfaceTexture::class.java))
            set("ready", true)
            gate.setRate(1)
            set("lastNs", System.nanoTime())
            flush()
        }
        fun set(name: String, value: Any) = FrameGate::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(gate, value)
        fun count(name: String) = FrameGate::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.getInt(gate)
        fun invoke(name: String) = FrameGate::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(gate)
        fun flush() { while (immediate.isNotEmpty()) immediate.removeFirst().run() }
        fun due() {
            set("lastNs", System.nanoTime() - 2_000_000_000L)
            delayed.removeAt(0).first.run()
        }
    }

    @Test fun wakeAndRedrawDoNotBypassFrameRateOrMultiplyTicks() {
        // Exercise the real scheduler without constructing Android's EGL sentinels.
        val gate = mock(FrameGate::class.java, CALLS_REAL_METHODS)
        val handler = mock(Handler::class.java)
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayList<Runnable>()
        doAnswer { immediate.add(it.getArgument(0)); true }.`when`(handler).post(any(Runnable::class.java))
        doAnswer { delayed.add(it.getArgument(0)); true }.`when`(handler).postDelayed(any(Runnable::class.java), anyLong())
        doAnswer { delayed.removeAll { r -> r === it.getArgument<Runnable>(0) }; null }.`when`(handler).removeCallbacks(any(Runnable::class.java))
        doAnswer { delayed.contains(it.getArgument(0)) }.`when`(handler).hasCallbacks(any(Runnable::class.java))
        fun set(name: String, value: Any) = FrameGate::class.java.getDeclaredField(name).apply { isAccessible = true }.set(gate, value)
        fun count() = FrameGate::class.java.getDeclaredField("repeats").apply { isAccessible = true }.getInt(gate)
        val onTick = FrameGate::class.java.getDeclaredMethod("onTick").apply { isAccessible = true }
        set("tick", Runnable { onTick.invoke(gate) })
        set("tag", "GateTest")
        set("matrix", FloatArray(16))
        set("reportedNs", System.nanoTime())
        set("handler", handler)
        set("texture", mock(SurfaceTexture::class.java))
        set("ready", true)
        // A long frame interval keeps the test independent of scheduler speed.
        gate.setRate(1)
        set("lastNs", System.nanoTime())
        repeat(20) { gate.wake(2000); gate.redraw() }
        while (immediate.isNotEmpty()) immediate.removeFirst().run()
        assertEquals("wakeups must wait for the next frame", 0, count())
        assertEquals("only one tick may be pending", 1, delayed.size)
        // An early tick (e.g. after lowering FPS) must also respect the interval.
        delayed.removeAt(0).run()
        assertEquals(0, count())
        assertEquals(1, delayed.size)
        set("lastNs", System.nanoTime() - 2_000_000_000L)
        delayed.removeAt(0).run()
        assertEquals(1, count())
        assertEquals(1, delayed.size)
    }
}
