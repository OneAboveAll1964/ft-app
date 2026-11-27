package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SurfaceEncoderCapabilityTest {
    @Test fun formatOnlyRejectionGetsOneUnchangedHardwareAttempt() = startup(listOf(Capabilities())) { result ->
        assertEquals(listOf("test.avc.0"), result.names)
        assertEquals(1, result.encoder.candidateCount)
        assertEquals(0, result.encoder.selectedCandidateIndex)
        verify(result.codecs.single()).start()
    }

    @Test fun otherTransportsDoNotProbeByDefault() = startup(listOf(Capabilities()), allowProbe = false, expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun unsupportedSizeIsNotProbed() = startup(listOf(Capabilities(size = false)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun unsupportedRateIsNotProbed() = startup(listOf(Capabilities(rate = false)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun unsupportedBitrateIsNotProbed() = startup(listOf(Capabilities(bitrate = false)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun unsupportedCbrDoesNotEnableVbrByDefault() = startup(
        listOf(Capabilities(cbr = false, vbr = true, vbrFormat = true)), expectFailure = true
    ) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun missingSurfaceIsNotProbed() = startup(listOf(Capabilities(surface = false)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun softwareEncoderIsNotProbed() = startup(listOf(Capabilities(hardware = false)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun capabilityExceptionIsNotTreatedAsFormatRejection() = startup(listOf(Capabilities(formatError = true)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun requiredCapabilityExceptionPreventsProbe() = startup(listOf(Capabilities(sizeError = true)), expectFailure = true) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun advertisedEncoderTakesPriorityOverProbe() = startup(listOf(Capabilities(), Capabilities(format = true))) {
        assertEquals(listOf("test.avc.1"), it.names)
        assertEquals(4, it.encoder.candidateCount) // Two profiles, each with/without the power hint.
    }

    @Test fun multipleFormatRejectionsStillGetOnlyOneAttempt() = startup(listOf(Capabilities(), Capabilities())) {
        assertEquals(listOf("test.avc.0"), it.names)
        assertEquals(1, it.encoder.candidateCount)
    }

    @Test fun rejectedProbeReleasesCodecAndStopsInsteadOfTryingOtherProfiles() = startup(
        listOf(Capabilities(), Capabilities()), rejectConfigure = true, expectFailure = true
    ) {
        assertEquals(listOf("test.avc.0"), it.names)
        verify(it.codecs.single()).release()
        verify(it.codecs.single(), never()).start()
        assertEquals(1, it.encoder.candidateCount)
    }

    @Test fun tecnoUsesHardwareVbrAtNegotiatedFormatWhenCbrIsUnavailable() {
        val hardware = Capabilities(cbr = false, vbr = true, vbrFormat = true)
        val software = Capabilities(hardware = false, size = false, rate = false, bitrate = false, vbr = true)
        startup(
            listOf(hardware, hardware.copy(canonicalIndex = 0), software, software.copy(canonicalIndex = 2)),
            allowVbr = true, expectedBitrateMode = BITRATE_MODE_VBR, alignedStreamLimit = 1920 to 1080
        ) {
            assertEquals(listOf("test.avc.0"), it.names)
            assertEquals(904, it.encoder.height)
            assertEquals(4, it.encoder.candidateCount)
            verify(it.codecs.single()).start()
        }
    }

    @Test fun xiaomiUsesAlignedJettaFormatWithHardwareVbr() = startup(
        listOf(Capabilities(cbr = false, vbr = true, vbrFormat = true, supportedHeight = 912, heightAlignment = 16)),
        allowVbr = true,
        expectedBitrateMode = BITRATE_MODE_VBR,
        alignedStreamLimit = 1920 to 1080,
        expectedHeight = 912
    ) {
        assertEquals(listOf("test.avc.0"), it.names)
        assertEquals(1920, it.encoder.width)
        assertEquals(912, it.encoder.height)
        verify(it.codecs.single()).start()
    }

    @Test fun fallbackUsesTheCodecsReportedRangesAndAlignmentOnBothAxes() = startup(
        listOf(Capabilities(
            format = true, supportedWidth = 1760, supportedHeight = 896,
            widthAlignment = 32, heightAlignment = 32, maxWidthAtHeight = 1776
        )),
        alignedStreamLimit = 1920 to 1080,
        expectedWidth = 1760,
        expectedHeight = 896
    ) {
        assertEquals(1760, it.encoder.width)
        assertEquals(896, it.encoder.height)
        verify(it.codecs.single()).start()
    }

    @Test fun vbrFallbackCanProbeOneOtherwiseSupportedHardwareFormat() = startup(
        listOf(Capabilities(cbr = false, vbr = true)), allowVbr = true, expectedBitrateMode = BITRATE_MODE_VBR
    ) {
        assertEquals(listOf("test.avc.0"), it.names)
        assertEquals(1, it.encoder.candidateCount)
    }

    @Test fun vbrFallbackRequiresHardwareAndNegotiatedSize() = startup(
        listOf(
            Capabilities(cbr = false, vbr = true, vbrFormat = true, size = false),
            Capabilities(cbr = false, vbr = true, vbrFormat = true, hardware = false)
        ), allowVbr = true, expectFailure = true
    ) {
        assertTrue(it.names.isEmpty())
    }

    @Test fun cbrProbeTakesPriorityOverVbrFallback() = startup(
        listOf(Capabilities(cbr = false, vbr = true, vbrFormat = true), Capabilities()), allowVbr = true
    ) {
        assertEquals(listOf("test.avc.1"), it.names)
        assertEquals(1, it.encoder.candidateCount)
    }

    private data class Capabilities(
        val size: Boolean = true,
        val rate: Boolean = true,
        val bitrate: Boolean = true,
        val cbr: Boolean = true,
        val surface: Boolean = true,
        val format: Boolean = false,
        val hardware: Boolean = true,
        val formatError: Boolean = false,
        val sizeError: Boolean = false,
        val vbr: Boolean = false,
        val vbrFormat: Boolean = false,
        val canonicalIndex: Int? = null,
        val supportedWidth: Int = 1920,
        val supportedHeight: Int = 904,
        val widthAlignment: Int = 2,
        val heightAlignment: Int = 2,
        val maxWidthAtHeight: Int = 1920
    )

    private data class Startup(val encoder: SurfaceEncoder, val names: List<String>, val codecs: List<MediaCodec>)

    private fun range(lower: Int, upper: Int): Range<Int> {
        @Suppress("UNCHECKED_CAST")
        val range = mock(Range::class.java) as Range<Int>
        `when`(range.lower).thenReturn(lower)
        `when`(range.upper).thenReturn(upper)
        return range
    }

    private fun startup(
        capabilities: List<Capabilities>,
        allowProbe: Boolean = true,
        rejectConfigure: Boolean = false,
        expectFailure: Boolean = false,
        allowVbr: Boolean = false,
        expectedBitrateMode: Int = BITRATE_MODE_CBR,
        alignedStreamLimit: Pair<Int, Int>? = null,
        expectedWidth: Int = 1920,
        expectedHeight: Int = 904,
        check: (Startup) -> Unit
    ) {
        val infos = capabilities.mapIndexed { index, values ->
            mock(MediaCodecInfo::class.java, RETURNS_DEEP_STUBS).also { info ->
                `when`(info.isEncoder).thenReturn(true)
                `when`(info.isHardwareAccelerated).thenReturn(values.hardware)
                `when`(info.name).thenReturn("test.avc.$index")
                `when`(info.canonicalName).thenReturn("test.avc.${values.canonicalIndex ?: index}")
                `when`(info.supportedTypes).thenReturn(arrayOf(MediaFormat.MIMETYPE_VIDEO_AVC))
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = requireNotNull(caps.videoCapabilities)
                val modes = requireNotNull(caps.encoderCapabilities)
                caps.colorFormats = if (values.surface) intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) else intArrayOf()
                `when`(video.isSizeSupported(values.supportedWidth, values.supportedHeight)).thenAnswer {
                    if (values.sizeError) throw IllegalArgumentException("size query failed")
                    values.size
                }
                `when`(video.areSizeAndRateSupported(values.supportedWidth, values.supportedHeight, 30.0)).thenReturn(values.rate)
                `when`(video.bitrateRange.contains(8_000_000)).thenReturn(values.bitrate)
                `when`(video.widthAlignment).thenReturn(values.widthAlignment)
                `when`(video.heightAlignment).thenReturn(values.heightAlignment)
                if (alignedStreamLimit != null) {
                    val widths = range(64, 1920)
                    val heights = range(64, 1080)
                    val widthsAtHeight = range(64, values.maxWidthAtHeight)
                    `when`(video.supportedWidths).thenReturn(widths)
                    `when`(video.supportedHeights).thenReturn(heights)
                    `when`(video.getSupportedWidthsFor(anyInt())).thenReturn(widthsAtHeight)
                    `when`(video.getSupportedHeightsFor(anyInt())).thenReturn(heights)
                }
                `when`(modes.isBitrateModeSupported(BITRATE_MODE_CBR)).thenReturn(values.cbr)
                `when`(modes.isBitrateModeSupported(BITRATE_MODE_VBR)).thenReturn(values.vbr)
                `when`(caps.isFormatSupported(any(MediaFormat::class.java))).thenAnswer {
                    if (values.formatError) throw IllegalArgumentException("format query failed")
                    val format = it.getArgument<MediaFormat>(0)
                    if (format.getInteger(MediaFormat.KEY_BITRATE_MODE) == BITRATE_MODE_VBR) values.vbrFormat else values.format
                }
            }
        }
        val names = mutableListOf<String>()
        val created = mutableListOf<MediaCodec>()
        val configuredFormats = mutableListOf<MediaFormat>()
        mockConstruction(HandlerThread::class.java).use { threads ->
            mockConstruction(Handler::class.java).use {
                mockConstruction(MediaCodecList::class.java) { list, _ ->
                    `when`(list.codecInfos).thenReturn(infos.toTypedArray())
                }.use {
                    mockStatic(MediaFormat::class.java).use { formats ->
                        formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(eq(MediaFormat.MIMETYPE_VIDEO_AVC), anyInt(), anyInt()) }
                            .thenAnswer { invocation ->
                                mock(MediaFormat::class.java).also { format ->
                                    `when`(format.getInteger(MediaFormat.KEY_WIDTH)).thenReturn(invocation.getArgument<Int>(1))
                                    `when`(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(invocation.getArgument<Int>(2))
                                    var bitrateMode = -1
                                    doAnswer {
                                        bitrateMode = it.getArgument<Int>(1)
                                        null
                                    }.`when`(format).setInteger(eq(MediaFormat.KEY_BITRATE_MODE), anyInt())
                                    `when`(format.getInteger(MediaFormat.KEY_BITRATE_MODE)).thenAnswer { bitrateMode }
                                }
                            }
                        mockStatic(MediaCodec::class.java).use { codecs ->
                            codecs.`when`<MediaCodec> { MediaCodec.createByCodecName(anyString()) }.thenAnswer { invocation ->
                                names += invocation.getArgument<String>(0)
                                mock(MediaCodec::class.java).also { codec ->
                                    created += codec
                                    `when`(codec.createInputSurface()).thenReturn(mock(Surface::class.java))
                                    doAnswer {
                                        configuredFormats += it.getArgument<MediaFormat>(0)
                                        if (rejectConfigure) throw IllegalArgumentException("configure rejected")
                                        null
                                    }.`when`(codec).configure(any(MediaFormat::class.java), isNull(), isNull(), eq(MediaCodec.CONFIGURE_FLAG_ENCODE))
                                }
                            }
                            val encoder = SurfaceEncoder(1920, 904, 30, 8_000_000, 0, {}, { _, _ -> },
                                constantBitrate = true, savePower = true, allowFormatCapabilityProbe = allowProbe,
                                allowVbrFallback = allowVbr, alignedStreamLimit = alignedStreamLimit)
                            try {
                                if (expectFailure) {
                                    assertThrows(IllegalStateException::class.java) { encoder.start() }
                                    verify(threads.constructed().single()).quitSafely()
                                } else encoder.start()
                                // Assert outside configure's catch block so test failures cannot
                                // be mistaken for a vendor rejecting the format.
                                for (format in configuredFormats) {
                                    assertEquals(expectedWidth, format.getInteger(MediaFormat.KEY_WIDTH))
                                    assertEquals(expectedHeight, format.getInteger(MediaFormat.KEY_HEIGHT))
                                    verify(format).setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                                    verify(format).setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                                    verify(format).setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                                    verify(format).setInteger(MediaFormat.KEY_BITRATE_MODE, expectedBitrateMode)
                                    verify(format, never()).setInteger(eq(MediaFormat.KEY_PROFILE), anyInt())
                                    verify(format, never()).setInteger(eq(MediaFormat.KEY_LEVEL), anyInt())
                                    if (encoder.candidateCount == 1) {
                                        verify(format, never()).setFloat(eq(MediaFormat.KEY_OPERATING_RATE), anyFloat())
                                    }
                                }
                                // All checks run even when an earlier capability is false or throws.
                                for (info in infos) {
                                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                    verify(requireNotNull(caps.videoCapabilities), atLeastOnce()).isSizeSupported(1920, 904)
                                    verify(requireNotNull(caps.videoCapabilities), atLeastOnce()).areSizeAndRateSupported(1920, 904, 30.0)
                                    verify(requireNotNull(caps.encoderCapabilities), atLeastOnce()).isBitrateModeSupported(BITRATE_MODE_CBR)
                                    verify(caps, atLeastOnce()).isFormatSupported(any(MediaFormat::class.java))
                                }
                                check(Startup(encoder, names, created))
                            } finally { encoder.stop() }
                        }
                    }
                }
            }
        }
    }
}
