package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import app.ft.core.DiagLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class SurfaceEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    private val qpFloor: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (frame: ByteArray, keyFrame: Boolean) -> Unit,
    private val gated: Boolean = false,
    private val onTimedFrame: ((ByteArray, Boolean, Long) -> Unit)? = null,
    private val savePower: Boolean = false,
    private val constantBitrate: Boolean = false,
    private val onFailure: ((Throwable) -> Unit)? = null,
    private val startCandidateIndex: Int = 0,
    private val allowFormatCapabilityProbe: Boolean = false,
    private val allowVbrFallback: Boolean = false,
    private val alignedStreamLimit: Pair<Int, Int>? = null
) {
    private val tag = "Encoder"
    private val requestedSize = width to height
    var width = width
        private set
    var height = height
        private set
    private val fps = fps.coerceIn(1, 60)
    private val bitrate = bitrate.coerceIn(300_000, 40_000_000)
    private val running = AtomicBoolean(false)
    private val asyncFailed = AtomicBoolean(false)
    private val firstKeyFrameLogged = AtomicBoolean(false)
    private val firstOutputLogged = AtomicBoolean(false)
    private val sawSps = AtomicBoolean(false)
    private val sawPps = AtomicBoolean(false)
    @Volatile var selectedCandidateIndex: Int = -1
        private set
    @Volatile var candidateCount: Int = 0
        private set
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    @Volatile private var operatingRateHintEnabled = false
    private var lateNoted = 0L
    var surface: Surface? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        asyncFailed.set(false)
        firstKeyFrameLogged.set(false)
        firstOutputLogged.set(false)
        sawSps.set(false)
        sawPps.set(false)
        selectedCandidateIndex = -1
        operatingRateHintEnabled = false
        val t = HandlerThread("FT-Encoder").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        width = requestedSize.first
        height = requestedSize.second
        val sizeCapabilities = alignedStreamLimit?.let { mutableMapOf<String, MediaCodecInfo.VideoCapabilities>() }
        var (bitrateMode, choices) = encoderChoices(sizeCapabilities)
        if (choices.isEmpty() && alignedStreamLimit != null) {
            val sizes = sizeCapabilities.orEmpty().values.flatMap { nearbySizes(it, alignedStreamLimit) }
                .distinct().filterNot { it == requestedSize }
                .sortedBy { (w, h) -> abs(w - requestedSize.first) + abs(h - requestedSize.second) }
            for ((fallbackWidth, fallbackHeight) in sizes) {
                width = fallbackWidth
                height = fallbackHeight
                val (mode, options) = encoderChoices()
                if (options.isEmpty()) continue
                bitrateMode = mode
                choices = options
                DiagLog.i(tag, "codec SIZE_FALLBACK requested=${requestedSize.first}x${requestedSize.second} selected=${width}x${height}@$fps")
                break
            }
        }
        if (bitrateMode == BITRATE_MODE_VBR && choices.isNotEmpty()) {
            DiagLog.i(tag, "codec VBR_FALLBACK format=${width}x${height}@$fps bitrate=$bitrate")
        }
        val modeLabel = bitrateModeLabel(bitrateMode)
        // Optional resource-planning hint; retry the same portable AVC candidate
        // without it if the vendor rejects it. Never force a guessed AVC level.
        // Keep the selected stream geometry across bounded codec startup attempts.
        val candidates = choices.flatMap { choice ->
            (if (choice.probe) listOf("minimal") else listOf("minimal", "baseline")).map { label ->
                Candidate(choice.name, label, probe = choice.probe)
            }
        }.take(MAX_CANDIDATES).flatMap { candidate ->
            // Keep the portable retry as a numbered candidate, including when
            // the hint is rejected asynchronously after configure/start return.
            (if (savePower && !candidate.probe) listOf(true, false) else listOf(false)).map { rateHint ->
                candidate.copy(rateHint = rateHint)
            }
        }
        candidateCount = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            if (index < startCandidateIndex) continue
            val (name, label, rateHint, probe) = candidate
            val format = minimal(bitrateMode, baseline = label == "baseline").apply {
                if (rateHint) setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
            }
            val c = runCatching { MediaCodec.createByCodecName(name) }.onFailure {
                DiagLog.w(tag, "codec CREATE_REJECTED name=$name error=${it.javaClass.simpleName}")
            }.getOrNull() ?: continue
            val attemptActive = AtomicBoolean(true)
            try {
                if (probe) DiagLog.w(tag, "codec FORMAT_CAPABILITY_PROBE name=$name format=${width}x${height}@$fps bitrate=$bitrate bitrateMode=$modeLabel; only isFormatSupported=false, trying unchanged minimal format once")
                c.setCallback(callback(attemptActive), h)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = c.createInputSurface()
                c.start()
                codec = c
                operatingRateHintEnabled = rateHint
                selectedCandidateIndex = index
                DiagLog.i(tag, "codec STARTED_PENDING_OUTPUT name=${c.name} candidate=${index + 1}/${candidateCount} format=${width}x${height}@$fps bitrate=$bitrate bitrateMode=$modeLabel profile=$label rateHint=$rateHint capabilityProbe=$probe; SPS/PPS/IDR not yet proven")
                return
            } catch (t: Throwable) {
                attemptActive.set(false)
                DiagLog.w(tag, "codec CONFIGURE_REJECTED name=$name candidate=${index + 1}/${candidateCount} bitrateMode=$modeLabel profile=$label rateHint=$rateHint capabilityProbe=$probe error=${t.javaClass.simpleName}")
                runCatching { surface?.release() }
                surface = null
                runCatching { c.release() }
            }
        }
        running.set(false)
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        val reason = if (candidates.isEmpty()) "no eligible advertised capabilities" else "startup candidates exhausted"
        val size = if (candidates.isEmpty()) requestedSize else width to height
        throw IllegalStateException("no H.264 encoder on this phone accepted ${size.first}x${size.second}: $reason")
    }

    private data class EncoderChoice(val name: String, val probe: Boolean = false)
    private data class Candidate(val name: String, val label: String, val rateHint: Boolean = false, val probe: Boolean = false)

    private fun bitrateModeLabel(mode: Int?): String = when (mode) {
        BITRATE_MODE_CBR -> "CBR"
        BITRATE_MODE_VBR -> "VBR"
        else -> "default"
    }

    private fun encoderChoices(
        sizeCapabilities: MutableMap<String, MediaCodecInfo.VideoCapabilities>? = null
    ): Pair<Int?, List<EncoderChoice>> {
        val mode = if (constantBitrate) BITRATE_MODE_CBR else null
        val choices = encoders(mode, sizeCapabilities)
        return if (choices.isEmpty() && constantBitrate && allowVbrFallback) {
            BITRATE_MODE_VBR to encoders(BITRATE_MODE_VBR, sizeCapabilities)
        } else mode to choices
    }

    private fun encoders(
        bitrateMode: Int?, sizeCapabilities: MutableMap<String, MediaCodecInfo.VideoCapabilities>?
    ): List<EncoderChoice> {
        val all = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        }.getOrDefault(emptyList())
        val fits = mutableListOf<EncoderChoice>()
        val probes = mutableListOf<EncoderChoice>()
        val vbrFallback = bitrateMode == BITRATE_MODE_VBR
        val modeLabel = bitrateModeLabel(bitrateMode)
        val infos = all.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .distinctBy { if (vbrFallback) it.canonicalName else it.name }
        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.onFailure {
                DiagLog.w(tag, "codec CAPABILITIES name=${info.name} error=${it.javaClass.simpleName}")
            }.getOrNull() ?: continue
            val errors = mutableListOf<String>()
            fun <T> read(label: String, block: () -> T): T? = runCatching(block).onFailure {
                errors += "$label:${it.javaClass.simpleName}"
            }.getOrNull()
            // Evaluate independently: a size rejection must not hide CBR/format results.
            val surface = read("surface") { caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            val video = read("video") { caps.videoCapabilities }
            val size = read("size") { video?.isSizeSupported(width, height) }
            val rate = read("sizeRate") { video?.areSizeAndRateSupported(width, height, fps.toDouble()) }
            val bitrateOk = read("bitrate") { video?.bitrateRange?.contains(bitrate) }
            val cbr = read("CBR") { caps.encoderCapabilities?.isBitrateModeSupported(BITRATE_MODE_CBR) }
            val vbr = read("VBR") { caps.encoderCapabilities?.isBitrateModeSupported(BITRATE_MODE_VBR) }
            val format = read("format") { caps.isFormatSupported(minimal(bitrateMode)) }
            val widthAlignment = read("widthAlignment") { video?.widthAlignment }
            val heightAlignment = read("heightAlignment") { video?.heightAlignment }
            val modeSupported = when (bitrateMode) {
                BITRATE_MODE_CBR -> cbr == true
                BITRATE_MODE_VBR -> info.isHardwareAccelerated && vbr == true
                else -> true
            }
            val suitable = surface == true && bitrateOk == true && modeSupported
            val eligible = suitable && rate == true && (!vbrFallback || size == true)
            val probe = allowFormatCapabilityProbe && info.isHardwareAccelerated && eligible && size == true && format == false
            DiagLog.i(tag, "codec CAPABILITIES name=${info.name} hw=${info.isHardwareAccelerated} target=${width}x${height}@$fps bitrate=$bitrate bitrateMode=$modeLabel alignment=${widthAlignment}x$heightAlignment surface=$surface size=$size sizeRate=$rate bitrateSupported=$bitrateOk CBR=$cbr VBR=$vbr format=$format probeEligible=$probe errors=${errors.joinToString(",").ifEmpty { "none" }}")
            if (eligible && format == true) fits += EncoderChoice(info.name)
            else if (probe) probes += EncoderChoice(info.name, probe = true)
            if (sizeCapabilities != null && suitable && info.isHardwareAccelerated && video != null) {
                sizeCapabilities.putIfAbsent(info.canonicalName, video)
            }
        }
        if (fits.isEmpty()) DiagLog.w(tag, "no advertised AVC Surface encoder for ${width}x${height}@$fps ${bitrate / 1000} kbps bitrateMode=$modeLabel")
        // A probe relaxes only the composite format check for one hardware codec.
        return fits.ifEmpty { probes.take(1) }
    }

    private fun nearbySizes(video: MediaCodecInfo.VideoCapabilities, limit: Pair<Int, Int>): List<Pair<Int, Int>> {
        fun nearby(size: Int, step: Int, range: Range<Int>, limit: Int): List<Int> {
            val min = maxOf(1, range.lower)
            val max = minOf(limit, range.upper)
            if (min > max || step !in 1..max) return emptyList()
            val target = size.coerceIn(min, max)
            val down = target / step * step
            if (down == target) return listOf(target)
            return listOf(down + step, down).filter { it in min..max }
        }
        return runCatching {
            val (requestedWidth, requestedHeight) = requestedSize
            val (maxWidth, maxHeight) = limit
            val stepX = video.widthAlignment
            val stepY = video.heightAlignment
            val widths = nearby(requestedWidth, stepX, video.supportedWidths, maxWidth)
            val heights = nearby(requestedHeight, stepY, video.supportedHeights, maxHeight)
            // The legal height can depend on the width and vice versa.
            val widthFirst = widths.flatMap { w ->
                val range = runCatching { video.getSupportedHeightsFor(w) }.getOrNull() ?: return@flatMap emptyList()
                nearby(requestedHeight, stepY, range, maxHeight).map { h -> w to h }
            }
            val heightFirst = heights.flatMap { h ->
                val range = runCatching { video.getSupportedWidthsFor(h) }.getOrNull() ?: return@flatMap emptyList()
                nearby(requestedWidth, stepX, range, maxWidth).map { w -> w to h }
            }
            widthFirst + heightFirst
        }.onFailure {
            DiagLog.w(tag, "codec SIZE_RANGES error=${it.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    private fun minimal(bitrateMode: Int?, baseline: Boolean = false): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (bitrateMode != null) setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            if (baseline) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

    private fun callback(attemptActive: AtomicBoolean) = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!attemptActive.get()) return
                try {
                    val buf = codec.getOutputBuffer(index) ?: return
                    if (info.size <= 0) return
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frame = ByteArray(info.size)
                    buf.get(frame)
                    recordNals(frame)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        DiagLog.i(tag, "codec CSD_BUFFER bytes=${frame.size} sps=${sawSps.get()} pps=${sawPps.get()}")
                        if (running.get() && !asyncFailed.get()) onConfig(frame)
                        return
                    }
                    if (firstOutputLogged.compareAndSet(false, true))
                        DiagLog.i(tag, "codec FIRST_OUTPUT bytes=${frame.size} sps=${sawSps.get()} pps=${sawPps.get()}")
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (key && frame.size >= BIG_FRAME) DiagLog.w(tag, "key picture of ${frame.size / 1024} KB")
                    val drawnUs = info.presentationTimeUs
                    val late = (System.nanoTime() / 1000 - drawnUs) / 1000
                    if (drawnUs > 0 && late > 500 && late <= 60_000 && SystemClock.elapsedRealtime() - lateNoted > 5000) {
                        lateNoted = SystemClock.elapsedRealtime()
                        DiagLog.w(tag, "a picture came out of the encoder $late ms after it was drawn")
                    }
                    if (running.get() && !asyncFailed.get()) {
                        onTimedFrame?.invoke(frame, key, info.presentationTimeUs) ?: onFrame(frame, key)
                        if (key && firstKeyFrameLogged.compareAndSet(false, true)) {
                            val actualIdr = 5 in AvcNalScan.types(frame)
                            DiagLog.i(tag, "codec FIRST_KEY_OUTPUT idr=$actualIdr sps=${sawSps.get()} pps=${sawPps.get()} passedToTransport=true bytes=${frame.size} ptsUs=${info.presentationTimeUs}")
                        }
                    }
                } catch (t: Throwable) {
                    if (attemptActive.get() && running.get() && asyncFailed.compareAndSet(false, true)) {
                        DiagLog.e(tag, "codec OUTPUT_CALLBACK_FAILURE candidate=${selectedCandidateIndex + 1}/$candidateCount sps=${sawSps.get()} pps=${sawPps.get()}", t)
                        onFailure?.invoke(t)
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (!attemptActive.get()) return
                DiagLog.e(tag, "codec ASYNC_FAILURE name=${codec.name} errorCode=${e.errorCode} recoverable=${e.isRecoverable} transient=${e.isTransient} candidate=${selectedCandidateIndex + 1}/$candidateCount; retry candidate in same projection if available", e)
                if (running.get() && asyncFailed.compareAndSet(false, true)) onFailure?.invoke(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (!attemptActive.get()) return
                val out = ByteArrayOutputStream()
                listOf("csd-0", "csd-1").forEach { k ->
                    format.getByteBuffer(k)?.let { b ->
                        val d = b.duplicate()
                        val bytes = ByteArray(d.remaining())
                        d.get(bytes)
                        recordNals(bytes)
                        out.write(bytes)
                    }
                }
                if (out.size() > 0) {
                    DiagLog.i(tag, "codec CSD_FORMAT bytes=${out.size()} sps=${sawSps.get()} pps=${sawPps.get()}")
                    if (running.get() && !asyncFailed.get()) onConfig(out.toByteArray())
                }
                DiagLog.i(tag, "format ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
            }
        }

    private fun recordNals(data: ByteArray) {
        val kinds = AvcNalScan.types(data)
        if (7 in kinds) sawSps.set(true)
        if (8 in kinds) sawPps.set(true)
    }

    fun requestKeyframe(): Boolean {
        val c = codec ?: return false
        if (!running.get()) return false
        return runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            true
        }.getOrElse {
            DiagLog.w(tag, "could not ask the encoder for a fresh key picture: ${it.message}")
            false
        }
    }

    fun setOperatingRate(fps: Int) {
        if (!operatingRateHintEnabled) return
        val rate = fps.coerceIn(1, 60)
        val activeCodec = codec ?: return
        handler?.post {
            if (!running.get() || codec !== activeCodec || !operatingRateHintEnabled) return@post
            runCatching {
                activeCodec.setParameters(Bundle().apply {
                    putFloat(MediaFormat.KEY_OPERATING_RATE, rate.toFloat())
                })
            }.onSuccess {
                DiagLog.i(tag, "requested operating rate $rate fps")
            }.onFailure {
                DiagLog.w(tag, "operating rate $rate fps not applied: ${it.javaClass.simpleName} ${it.message ?: ""}")
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        val ownedCodec = codec
        val ownedSurface = surface
        codec = null
        operatingRateHintEnabled = false
        surface = null
        handler = null
        runCatching { ownedCodec?.stop() }
        runCatching { ownedCodec?.release() }
        runCatching { ownedSurface?.release() }
        runCatching { thread?.quitSafely() }
        thread = null
        DiagLog.i(tag, "codec STOP candidate=${selectedCandidateIndex + 1}/$candidateCount sps=${sawSps.get()} pps=${sawPps.get()} firstKey=${firstKeyFrameLogged.get()} asyncFailure=${asyncFailed.get()}")
    }

    companion object {
        private const val BIG_FRAME = 256 * 1024
        private const val MAX_CANDIDATES = 8
    }
}

/** Small pure decoder for AVC NAL headers; does not copy or retain screen data. */
internal object AvcNalScan {
    fun types(data: ByteArray): Set<Int> {
        val found = mutableSetOf<Int>()
        var i = 0
        var annexB = false
        while (i + 3 < data.size) {
            val a = data[i].toInt() and 255
            val b = data[i + 1].toInt() and 255
            val c = data[i + 2].toInt() and 255
            val four = i + 3 < data.size && c == 0 && (data[i + 3].toInt() and 255) == 1
            if (a == 0 && b == 0 && (c == 1 || four)) {
                val pos = i + if (four) 4 else 3
                if (pos < data.size) found += data[pos].toInt() and 31
                annexB = true
                i = pos
            } else i++
        }
        if (annexB) return found
        // Some AVC encoders return length-prefixed access units instead.
        i = 0
        while (i + 4 < data.size) {
            val size = ((data[i].toInt() and 255) shl 24) or
                ((data[i + 1].toInt() and 255) shl 16) or
                ((data[i + 2].toInt() and 255) shl 8) or (data[i + 3].toInt() and 255)
            if (size <= 0 || size > data.size - i - 4) break
            found += data[i + 4].toInt() and 31
            i += 4 + size
        }
        if (found.isEmpty() && data.isNotEmpty() && (data[0].toInt() and 31) in 1..12)
            found += data[0].toInt() and 31
        return found
    }
}
