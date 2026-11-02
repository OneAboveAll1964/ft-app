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
}
