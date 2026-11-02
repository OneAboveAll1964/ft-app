package app.ft.projection

import android.graphics.SurfaceTexture
import android.os.Handler
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class FrameGateTest {
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
