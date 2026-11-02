package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import app.ft.core.DiagLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class SurfaceEncoder(
    private val width: Int,
    private val height: Int,
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
    private val startCandidateIndex: Int = 0
) {
    private val tag = "Encoder"
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
        val t = HandlerThread("FT-Encoder").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // Start from portable AVC Surface input. Never force a guessed AVC level
        // or an absurd operating rate before the first encoded frame.
        // Bounded attempts. Each fallback keeps the exact RTSP-agreed stream geometry.
        val candidates = encoders().flatMap { codecName ->
            listOf("minimal", "baseline").map { label -> codecName to label }
        }.take(MAX_CANDIDATES)
        candidateCount = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            if (index < startCandidateIndex) continue
            val (name, label) = candidate
            val format = minimal(baseline = label == "baseline")
            val c = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                c.setCallback(callback(), h)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = c.createInputSurface()
                c.start()
                codec = c
                selectedCandidateIndex = index
                DiagLog.i(tag, "codec STARTED_PENDING_OUTPUT name=${c.name} candidate=${index + 1}/${candidateCount} format=${width}x${height}@$fps bitrate=$bitrate CBR=$constantBitrate profile=$label; SPS/PPS/IDR not yet proven")
                return
            } catch (t: Throwable) {
                DiagLog.w(tag, "codec CONFIGURE_REJECTED name=$name candidate=${index + 1}/${candidateCount} mode=$label error=${t.javaClass.simpleName}")
                runCatching { surface?.release() }
                surface = null
                runCatching { c.release() }
            }
        }
        running.set(false)
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        throw IllegalStateException("no H.264 encoder on this phone accepted ${width}x$height")
    }

    private fun encoders(): List<String> {
        val all = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        }.getOrDefault(emptyList())
        val fits = all.filter { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = caps.videoCapabilities
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    video.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                    video.bitrateRange.contains(bitrate) &&
                    (!constantBitrate || caps.encoderCapabilities.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )) &&
                    caps.isFormatSupported(minimal())
            }.getOrDefault(false)
        }
        if (fits.isEmpty()) DiagLog.w(tag, "no advertised AVC Surface encoder for ${width}x${height}@$fps ${bitrate / 1000} kbps")
        return fits.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }.map { it.name }.distinct()
    }

    private fun base(): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        if (constantBitrate) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    }

    private fun minimal(baseline: Boolean = false): MediaFormat = base().apply {
        if (baseline) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
    }

    private fun callback() = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
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
                    if (running.get() && asyncFailed.compareAndSet(false, true)) {
                        DiagLog.e(tag, "codec OUTPUT_CALLBACK_FAILURE candidate=${selectedCandidateIndex + 1}/$candidateCount sps=${sawSps.get()} pps=${sawPps.get()}", t)
                        onFailure?.invoke(t)
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                DiagLog.e(tag, "codec ASYNC_FAILURE name=${codec.name} errorCode=${e.errorCode} recoverable=${e.isRecoverable} transient=${e.isTransient} candidate=${selectedCandidateIndex + 1}/$candidateCount; retry candidate in same projection if available", e)
                if (running.get() && asyncFailed.compareAndSet(false, true)) onFailure?.invoke(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
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
        if (!savePower) return
        val rate = fps.coerceIn(1, 60)
        val activeCodec = codec ?: return
        handler?.post {
            if (!running.get() || codec !== activeCodec) return@post
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
