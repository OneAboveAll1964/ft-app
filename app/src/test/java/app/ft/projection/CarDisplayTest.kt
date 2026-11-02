package app.ft.projection

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.view.Display
import android.view.Surface
import app.ft.FTApp
import app.ft.core.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CarDisplayTest {
    @Test fun stopRacingWithShowCannotLoseThePresentationOwner() {
        var car: CarDisplay? = null
        val attempted = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        var stopper: Thread? = null
        fixture(onShow = {
            stopper = Thread {
                attempted.countDown()
                car!!.stop()
                stopped.countDown()
            }.apply { start() }
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("stop must wait until show publishes its owner", stopped.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        }) { f ->
            car = f.car
            f.start()
            // Execute show only; the stopper will enqueue cleanup after show releases the monitor.
            f.queue.removeFirst().run()
            assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            stopper!!.join(5000)
            f.runMain()
            assertNull("show must complete without a swallowed assertion", f.failure)
            verify(f.presentations().single()).destroy()
            verify(f.vd).release()
            assertFalse(f.car.active.value)
        }
    }

    @Test fun stopBeforeQueuedShowNeverCreatesPresentationAndReleasesDisplayOnMain() = fixture { f ->
        f.start()
        f.car.stop()
        verify(f.vd, never()).release()
        f.runMain()
        assertTrue(f.presentations().isEmpty())
        verify(f.vd).release()
        assertFalse(f.car.active.value)
    }

    @Test fun shownPresentationIsDestroyedBeforeItsDisplayIsReleased() = fixture { f ->
        f.start()
        f.runMain()
        val p = f.presentations().single()
        assertTrue(f.car.active.value)
        f.car.stop()
        verify(p, never()).destroy()
        verify(f.vd, never()).release()
        f.runMain()
        inOrder(p, f.vd).apply {
            verify(p).show()
            verify(p).destroy()
            verify(f.vd).release()
        }
        assertFalse(f.car.active.value)
    }

    @Test fun failedShowDestroysUnpublishedPresentationAndReleasesDisplay() = fixture(failShow = true) { f ->
        f.start()
        f.runMain()
        val p = f.presentations().single()
        inOrder(p, f.vd).apply {
            verify(p).show()
            verify(p).destroy()
            verify(f.vd).release()
        }
        assertNotNull(f.failure)
        assertFalse(f.car.active.value)
        f.car.stop()
        f.runMain()
        verify(p, times(1)).destroy()
        verify(f.vd, times(1)).release()
    }

    private class Fixture(val car: CarDisplay, val vd: VirtualDisplay,
                          val queue: ArrayDeque<Runnable>, val presentations: () -> List<CarPresentation>) {
        var failure: Throwable? = null
        fun start() = car.start(VideoPlan(800, 480, 800, 480, 30, 3_000_000), {}, { _, _ -> },
            onFailure = { failure = it }, content = {})
        fun runMain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun fixture(failShow: Boolean = false, onShow: (() -> Unit)? = null, test: (Fixture) -> Unit) {
        val appField = FTApp::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousApp = appField.get(null)
        val app = mock(FTApp::class.java)
        `when`(app.prefs).thenReturn(mock(Prefs::class.java))
        appField.set(null, app)
        val context = mock(Context::class.java)
        val manager = mock(DisplayManager::class.java)
        val vd = mock(VirtualDisplay::class.java)
        val surface = mock(Surface::class.java)
        `when`(vd.display).thenReturn(mock(Display::class.java))
        `when`(context.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(manager)
        `when`(manager.createVirtualDisplay(anyString(), anyInt(), anyInt(), anyInt(), any(Surface::class.java), anyInt())).thenReturn(vd)
        val queue = ArrayDeque<Runnable>()
        try {
            mockConstruction(Handler::class.java) { handler, _ ->
                `when`(handler.post(any(Runnable::class.java))).thenAnswer {
                    queue.addLast(it.getArgument(0)); true
                }
            }.use {
                mockConstruction(DrawWatch::class.java).use {
                    mockConstruction(SurfaceEncoder::class.java) { encoder, _ ->
                        `when`(encoder.surface).thenReturn(surface)
                    }.use {
                        mockConstruction(FrameGate::class.java) { gate, _ ->
                            `when`(gate.start(anyInt())).thenReturn(true)
                            `when`(gate.input).thenReturn(surface)
                        }.use {
                            mockConstruction(CarPresentation::class.java) { p, _ ->
                                if (failShow) doThrow(IllegalStateException("display vanished")).`when`(p).show()
                                else if (onShow != null) doAnswer { onShow(); null }.`when`(p).show()
                            }.use { presentations ->
                                val f = Fixture(CarDisplay(context), vd, queue) { presentations.constructed() }
                                try { test(f) } finally { f.car.stop(); f.runMain() }
                            }
                        }
                    }
                }
            }
        } finally { appField.set(null, previousApp) }
    }
}
