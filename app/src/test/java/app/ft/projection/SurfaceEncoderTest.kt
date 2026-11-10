package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class SurfaceEncoderTest {
    @Test fun powerHintIsAppliedAtInitialConfigure() = startup(savePower = true, rejectHint = false) { attempts ->
        assertEquals(listOf(true), attempts.map { it.second })
        verify(attempts.single().first).start()
    }

    @Test fun rejectedHintRetriesSameCodecAndProfileWithoutHint() = startup(savePower = true, rejectHint = true) { attempts ->
        assertEquals(listOf(true, false), attempts.map { it.second })
        verify(attempts[0].first).release()
        verify(attempts[0].first, never()).start()
        verify(attempts[1].first).start()
    }

    @Test fun disabledSettingKeepsPortableStartup() = startup(savePower = false, rejectHint = true) { attempts ->
        assertEquals(listOf(false), attempts.map { it.second })
        verify(attempts.single().first).start()
    }

    @Test fun asyncRetryOfHintedCandidateStartsSameCodecWithoutHint() = startup(savePower = true, rejectHint = false, startCandidateIndex = 1) { attempts ->
        assertEquals(listOf(false), attempts.map { it.second })
        verify(attempts.single().first).start()
    }

    @Test fun callbacksFromRejectedAttemptCannotFailTheFallback() = startup(savePower = true, rejectHint = true) { attempts ->
        val rejected = attempts.first().first
        val callback = ArgumentCaptor.forClass(MediaCodec.Callback::class.java)
        verify(rejected).setCallback(callback.capture(), any(Handler::class.java))
        callback.value.onError(rejected, mock(MediaCodec.CodecException::class.java))
        callback.value.onOutputFormatChanged(rejected, mock(MediaFormat::class.java))
        callback.value.onOutputBufferAvailable(rejected, 0, MediaCodec.BufferInfo())
        verify(rejected, never()).getOutputBuffer(anyInt())
    }

    @Test fun portableFallbackDoesNotReintroduceDynamicHint() = startup(savePower = true, rejectHint = true, checkPortableRate = true) { attempts ->
        assertEquals(listOf(true, false), attempts.map { it.second })
    }

    private fun startup(savePower: Boolean, rejectHint: Boolean, startCandidateIndex: Int = 0, checkPortableRate: Boolean = false, check: (List<Pair<MediaCodec, Boolean>>) -> Unit) {
        val info = mock(MediaCodecInfo::class.java, RETURNS_DEEP_STUBS)
        `when`(info.isEncoder).thenReturn(true)
        `when`(info.isHardwareAccelerated).thenReturn(true)
        `when`(info.name).thenReturn("test.avc.hardware")
        `when`(info.supportedTypes).thenReturn(arrayOf(MediaFormat.MIMETYPE_VIDEO_AVC))
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        caps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        `when`(caps.videoCapabilities.areSizeAndRateSupported(800, 480, 30.0)).thenReturn(true)
        `when`(caps.videoCapabilities.bitrateRange.contains(3_000_000)).thenReturn(true)
        `when`(caps.isFormatSupported(any(MediaFormat::class.java))).thenReturn(true)
        val hints = HashMap<MediaFormat, Boolean>()
        val attempts = ArrayList<Pair<MediaCodec, Boolean>>()
        val failures = ArrayList<Throwable>()
        mockConstruction(HandlerThread::class.java).use {
            mockConstruction(Handler::class.java).use { handlers ->
                mockConstruction(MediaCodecList::class.java) { list, _ ->
                    `when`(list.codecInfos).thenReturn(arrayOf(info))
                }.use {
                    mockStatic(MediaFormat::class.java).use { formats ->
                        formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 800, 480) }
                            .thenAnswer {
                                mock(MediaFormat::class.java).also { format ->
                                    doAnswer {
                                        assertEquals(30f, it.getArgument<Float>(1), 0f)
                                        hints[format] = true
                                        null
                                    }.`when`(format).setFloat(eq(MediaFormat.KEY_OPERATING_RATE), anyFloat())
                                }
                            }
                        mockStatic(MediaCodec::class.java).use { codecs ->
                            codecs.`when`<MediaCodec> { MediaCodec.createByCodecName("test.avc.hardware") }.thenAnswer {
                                mock(MediaCodec::class.java).also { codec ->
                                    `when`(codec.createInputSurface()).thenReturn(mock(Surface::class.java))
                                    doAnswer {
                                        val format = it.getArgument<MediaFormat>(0)
                                        // A rejected hint must not silently change the AVC profile.
                                        verify(format, never()).setInteger(eq(MediaFormat.KEY_PROFILE), anyInt())
                                        val hinted = hints[format] == true
                                        attempts.add(codec to hinted)
                                        if (hinted && rejectHint) throw IllegalArgumentException("unsupported operating rate")
                                        null
                                    }.`when`(codec).configure(any(MediaFormat::class.java), isNull(), isNull(), eq(MediaCodec.CONFIGURE_FLAG_ENCODE))
                                }
                            }
                            val encoder = SurfaceEncoder(800, 480, 30, 3_000_000, 0, {}, { _, _ -> }, savePower = savePower, onFailure = { failures.add(it) }, startCandidateIndex = startCandidateIndex)
                            try {
                                encoder.start()
                                assertEquals(startCandidateIndex + attempts.size - 1, encoder.selectedCandidateIndex)
                                assertEquals(if (savePower) 4 else 2, encoder.candidateCount)
                                if (checkPortableRate) {
                                    encoder.setOperatingRate(20)
                                    verify(handlers.constructed().single(), never()).post(any(Runnable::class.java))
                                }
                                check(attempts)
                                assertTrue("Rejected codecs must not fail the active encoder", failures.isEmpty())
                            } finally { encoder.stop() }
                        }
                    }
                }
            }
        }
    }
}
