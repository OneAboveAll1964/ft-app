package app.ft.aa

import android.media.AudioRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AaMicrophoneTest {
    @Test
    fun carAudioDoesNotWaitForCleanupAndRestartWaitsForRelease() {
        val oldReadStarted = CountDownLatch(1)
        val newReadStarted = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val releaseNewRead = CountDownLatch(1)
        val oldStopStarted = CountDownLatch(1)
        val releaseOldStop = CountDownLatch(1)
        val oldReleased = CountDownLatch(1)
        val newReleased = CountDownLatch(1)
        val carReturned = CountDownLatch(1)
        val restartRequested = CountDownLatch(1)
        val newConstructionStarted = CountDownLatch(1)
        val oldThread = AtomicReference<Thread>()
        val carFailure = AtomicReference<Throwable>()
        val restartFailure = AtomicReference<Throwable>()
        val phoneDelivered = AtomicInteger()
        val carDelivered = AtomicInteger()
        mockConstruction(AudioRecord::class.java) { recorder, construction ->
            val old = construction.count == 1
            if (!old) {
                newConstructionStarted.countDown()
                check(oldReleased.count == 0L) { "old recorder must release before another is constructed" }
            }
            `when`(recorder.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            `when`(recorder.read(org.mockito.ArgumentMatchers.any(ByteArray::class.java), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer {
                if (old) {
                    oldThread.set(Thread.currentThread())
                    oldReadStarted.countDown()
                    check(releaseOldRead.await(5, TimeUnit.SECONDS))
                    2
                } else {
                    newReadStarted.countDown()
                    check(releaseNewRead.await(5, TimeUnit.SECONDS))
                    -1
                }
            }
            doAnswer {
                if (old) {
                    oldStopStarted.countDown()
                    check(releaseOldStop.await(5, TimeUnit.SECONDS))
                }
                null
            }.`when`(recorder).stop()
            doAnswer {
                if (old) oldReleased.countDown() else newReleased.countDown()
                null
            }.`when`(recorder).release()
        }.use { records ->
            val microphone = AaMicrophone { phoneDelivered.incrementAndGet() }
            val input = AaMicrophoneInput({ carDelivered.incrementAndGet() }, { microphone.stopAsync() }, {})
            var carThread: Thread? = null
            var releaseThread: Thread? = null
            try {
                input.open()
                assertTrue(microphone.start())
                assertTrue(oldReadStarted.await(5, TimeUnit.SECONDS))
                carThread = Thread {
                    try {
                        input.car(byteArrayOf(1, 0))
                    } catch (t: Throwable) {
                        carFailure.set(t)
                    } finally {
                        carReturned.countDown()
                    }
                }.apply { start() }
                assertTrue(oldStopStarted.await(5, TimeUnit.SECONDS))
                assertTrue("car PCM must pass while the old AudioRecord.stop is blocked", carReturned.await(1, TimeUnit.SECONDS))
                assertEquals(null, carFailure.get())
                assertEquals(1, carDelivered.get())
                assertFalse(microphone.active)

                releaseThread = Thread {
                    try {
                        check(restartRequested.await(5, TimeUnit.SECONDS))
                        assertFalse("restart must wait for the old device", newConstructionStarted.await(200, TimeUnit.MILLISECONDS))
                    } catch (t: Throwable) {
                        restartFailure.set(t)
                    } finally {
                        releaseOldStop.countDown()
                    }
                }.apply { start() }
                restartRequested.countDown()
                assertTrue(microphone.start())
                assertTrue(newReadStarted.await(5, TimeUnit.SECONDS))
                releaseThread.join(5000)
                assertEquals(null, restartFailure.get())
                assertTrue(oldReleased.await(5, TimeUnit.SECONDS))
                releaseOldRead.countDown()
                oldThread.get().join(5000)
                assertFalse(oldThread.get().isAlive)
                assertEquals(0, phoneDelivered.get())
                assertTrue(microphone.active)
                verify(records.constructed()[1], never()).stop()
                verify(records.constructed()[1], never()).release()
            } finally {
                releaseOldStop.countDown()
                releaseOldRead.countDown()
                releaseNewRead.countDown()
                restartRequested.countDown()
                carThread?.join(5000)
                releaseThread?.join(5000)
                microphone.stop()
                if (records.constructed().size > 1) assertTrue(newReleased.await(5, TimeUnit.SECONDS))
                assertTrue(oldReleased.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun restartingDoesNotReviveAnOldRecorderOrDeliverItsPendingRead() {
        val oldReadStarted = CountDownLatch(1)
        val newReadStarted = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val releaseNewRead = CountDownLatch(1)
        val oldThread = AtomicReference<Thread>()
        val oldReads = AtomicInteger()
        val delivered = AtomicInteger()
        mockConstruction(AudioRecord::class.java) { recorder, construction ->
            val old = construction.count == 1
            `when`(recorder.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            `when`(recorder.read(org.mockito.ArgumentMatchers.any(ByteArray::class.java), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer {
                if (old) {
                    oldThread.set(Thread.currentThread())
                    oldReads.incrementAndGet()
                    oldReadStarted.countDown()
                    check(releaseOldRead.await(5, TimeUnit.SECONDS))
                    2
                } else {
                    newReadStarted.countDown()
                    check(releaseNewRead.await(5, TimeUnit.SECONDS))
                    -1
                }
            }
        }.use {
            val microphone = AaMicrophone { delivered.incrementAndGet() }
            try {
                assertTrue(microphone.start())
                assertTrue(oldReadStarted.await(5, TimeUnit.SECONDS))
                microphone.stop()
                assertTrue(microphone.start())
                assertTrue(newReadStarted.await(5, TimeUnit.SECONDS))
                releaseOldRead.countDown()
                oldThread.get().join(5000)
                assertFalse(oldThread.get().isAlive)
                assertEquals(1, oldReads.get())
                assertEquals(0, delivered.get())
            } finally {
                microphone.stop()
                releaseOldRead.countDown()
                releaseNewRead.countDown()
            }
        }
    }

    @Test
    fun aRevokedPermissionReleasesTheRecorderAndLeavesTheMicrophoneInactive() {
        mockConstruction(AudioRecord::class.java) { recorder, _ ->
            `when`(recorder.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            doThrow(SecurityException("permission revoked")).`when`(recorder).startRecording()
        }.use { records ->
            val microphone = AaMicrophone { error("no audio should be delivered") }
            assertFalse(microphone.start())
            assertFalse(microphone.active)
            verify(records.constructed().single()).release()
        }
    }
}
