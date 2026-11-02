package app.ft.projection

import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioCaptureTest {
    private val projection = mock(MediaProjection::class.java)

    private fun await(latch: CountDownLatch) {
        assertTrue("capture thread did not finish in time", latch.await(5, TimeUnit.SECONDS))
    }

    private fun failure(throws: Boolean) {
        val record = mock(AudioRecord::class.java)
        val released = CountDownLatch(1)
        doAnswer { released.countDown(); null }.`when`(record).release()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            if (throws) throw IllegalStateException("dead recorder")
            AudioRecord.ERROR_DEAD_OBJECT
        }
        val capture = AudioCapture({ record }, { fail("failed reads must not deliver PCM") })
        assertTrue(capture.start(projection))
        await(released)
        assertFalse(capture.active)
        capture.stop()
        verify(record, times(1)).release()
    }

    @Test fun negativeReadClearsActiveAndReleasesRecord() = failure(false)
    @Test fun throwingReadClearsActiveAndReleasesRecord() = failure(true)

    @Test fun failedCaptureCanRestart() {
        val first = mock(AudioRecord::class.java)
        val second = mock(AudioRecord::class.java)
        val released = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        doAnswer { released.countDown(); null }.`when`(first).release()
        `when`(first.read(any(ByteArray::class.java), anyInt(), anyInt())).thenReturn(-1)
        `when`(second.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            reading.countDown(); await(finish); -1
        }
        val records = ArrayDeque(listOf(first, second))
        val capture = AudioCapture({ records.removeFirst() }, {})
        try {
            assertTrue(capture.start(projection))
            await(released)
            assertTrue(capture.start(projection))
            await(reading)
            assertTrue(capture.active)
        } finally {
            capture.stop()
            finish.countDown()
        }
        verify(first, times(1)).release()
        verify(second, times(1)).release()
    }

    @Test fun oldReadAfterStopCannotPublishOrDisableReplacement() {
        val first = mock(AudioRecord::class.java)
        val second = mock(AudioRecord::class.java)
        val oldReading = CountDownLatch(1)
        val newReading = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val finishNew = CountDownLatch(1)
        var oldThread: Thread? = null
        val delivered = AtomicInteger()
        `when`(first.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            oldThread = Thread.currentThread()
            oldReading.countDown(); await(finishOld); 4
        }
        `when`(second.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            newReading.countDown(); await(finishNew); -1
        }
        val records = ArrayDeque(listOf(first, second))
        val capture = AudioCapture({ records.removeFirst() }, { delivered.incrementAndGet() })
        try {
            assertTrue(capture.start(projection))
            await(oldReading)
            capture.stop()
            assertTrue(capture.start(projection))
            await(newReading)
            finishOld.countDown()
            oldThread!!.join(5000)
            assertFalse(oldThread!!.isAlive)
            assertTrue(capture.active)
            assertEquals(0, delivered.get())
            verify(first, times(1)).release()
        } finally {
            capture.stop()
            finishOld.countDown()
            finishNew.countDown()
        }
        verify(second, times(1)).release()
    }
}
