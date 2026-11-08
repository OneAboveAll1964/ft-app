package app.ft.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AaVideoDecoderTest {
    @Test fun reattachingSurfaceRequestsFreshPictureEvenWithoutFramesWhileHidden() {
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())).thenAnswer {
            Thread.sleep(5)
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 720) }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.thenReturn(codec)
                val decoder = AaVideoDecoder(1920, 720)
                var redraws = 0
                decoder.askForAFreshPicture = { redraws++ }
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertEquals(0, redraws)
                    decoder.setSurface(null)
                    verify(codec).stop()
                    // The phone can stop video immediately on NATIVE focus: no onFrame in between.
                    decoder.setSurface(mock(Surface::class.java))
                    assertEquals(1, redraws)
                    verify(codec, times(2)).start()
                } finally {
                    decoder.reset()
                }
            }
        }
    }

    @Test fun lateFormatFromClosedCodecCannotOverwriteNewSessionSizeOrRestartFromOldConfig() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val drainThread = AtomicReference<Thread>()
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())).thenAnswer {
            drainThread.set(Thread.currentThread())
            entered.countDown()
            check(leave.await(5, TimeUnit.SECONDS))
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        }
        `when`(codec.outputFormat).thenReturn(format)
        `when`(format.getInteger(MediaFormat.KEY_WIDTH)).thenReturn(800)
        `when`(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(480)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 720) }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.thenReturn(codec)
                val decoder = AaVideoDecoder(1920, 720)
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    decoder.reset()
                    AaVideoSink.frameSize(1920, 720)
                    leave.countDown()
                    drainThread.get().join(5000)
                    assertFalse(drainThread.get().isAlive)
                    assertEquals(1920 to 720, AaVideoSink.frame.value)
                    verify(codec, never()).getOutputFormat()
                    decoder.setSurface(mock(Surface::class.java))
                    codecs.verify({ MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }, times(1))
                    assertNull(decoder.askForAFreshPicture)
                } finally {
                    leave.countDown()
                    drainThread.get()?.join(5000)
                    decoder.reset()
                }
            }
        }
    }

    @Test fun recycledInputBufferWithNonzeroPositionStillAcceptsFrameWithinCapacity() {
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        val input = java.nio.ByteBuffer.allocate(16)
        // The frame fits the physical slot but not its inherited remaining().
        input.position(14)
        input.limit(15)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
        `when`(codec.getInputBuffer(0)).thenReturn(input)
        `when`(codec.dequeueOutputBuffer(
            any(MediaCodec.BufferInfo::class.java), anyLong()
        )).thenAnswer {
            Thread.sleep(2)
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(0, 0, 1, 103))
                    val frame = byteArrayOf(0, 0, 1, 65, 7)
                    decoder.onFrame(frame, 1234L)
                    verify(codec).queueInputBuffer(0, 0, frame.size, 1234L, 0)
                    assertArrayEquals(frame, input.array().copyOfRange(0, frame.size))
                } finally {
                    decoder.reset()
                }
            }
        }
    }


    @Test fun stopMustNotReleaseCodecWhileOutputBufferIsBeingRendered() {
        val releaseEntered = CountDownLatch(1)
        val resetEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        val releaseCount = java.util.concurrent.atomic.AtomicInteger(0)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())).thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            releaseEntered.countDown()
            check(allowRelease.await(5, TimeUnit.SECONDS))
            releaseCount.incrementAndGet()
            null
        }.`when`(codec).releaseOutputBuffer(0, true)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                var stopper: Thread? = null
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("drain did not reach releaseOutputBuffer", releaseEntered.await(5, TimeUnit.SECONDS))
                    stopper = Thread {
                        resetEntered.countDown()
                        decoder.reset()
                    }
                    stopper.start()
                    assertTrue("reset thread did not start", resetEntered.await(5, TimeUnit.SECONDS))
                    // Test the lifecycle contract rather than Thread.State.BLOCKED:
                    // an implementation may safely await in WAITING/TIMED_WAITING.
                    stopper.join(50)
                    assertTrue("reset returned before output rendering completed", stopper.isAlive)
                    // The codec must not be stopped/released while a render operation owns its buffer.
                    verify(codec, never()).stop()
                    verify(codec, never()).release()
                } finally {
                    allowRelease.countDown()
                    stopper?.join(5000)
                    decoder.reset()
                }
                assertFalse("reset remained blocked", stopper?.isAlive ?: true)
                assertEquals(1, releaseCount.get())
                verify(codec, times(1)).stop()
                verify(codec, times(1)).release()
            }
        }
    }
    @Test fun onFrameMustNotWaitForSlowOutputRendering() {
        val rendering = CountDownLatch(1)
        val finishRender = CountDownLatch(1)
        val inputFinished = CountDownLatch(1)
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(-1)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            rendering.countDown()
            check(finishRender.await(5, TimeUnit.SECONDS))
            null
        }.`when`(codec).releaseOutputBuffer(0, true)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                val input = Thread {
                    try {
                        decoder.onFrame(byteArrayOf(0, 0, 1, 65), 42L)
                    } finally {
                        inputFinished.countDown()
                    }
                }
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("output render never started", rendering.await(5, TimeUnit.SECONDS))
                    input.start()
                    // Render remains blocked by the fixture; onFrame must finish
                    // independently, without waiting for releaseOutputBuffer.
                    assertTrue("AA input dispatch blocked behind output render",
                        inputFinished.await(1, TimeUnit.SECONDS))
                    verify(codec).dequeueInputBuffer(20_000L)
                } finally {
                    finishRender.countDown()
                    input.join(5000)
                    decoder.reset()
                }
            }
        }
    }
}
