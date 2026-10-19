package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
    private val savePower: Boolean = false
) {
    private val tag = "Encoder"
    private val fps = fps.coerceIn(1, 60)
    private val bitrate = bitrate.coerceIn(300_000, 40_000_000)
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var lateNoted = 0L
    var surface: Surface? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = HandlerThread("FT-Encoder").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val tuning = VideoPlans.encoderKeys(
            Build.HARDWARE,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            Build.VERSION.SDK_INT,
            Build.BRAND,
            Build.BOARD,
            qpFloor
        )
        val setups = listOf(
            "full" to tuned(tuning),
            "without vendor tuning" to tuned(emptyMap()),
            "plain" to plain(MediaCodecInfo.CodecProfileLevel.AVCLevel4),
            "plain at level 3.1" to plain(MediaCodecInfo.CodecProfileLevel.AVCLevel31),
            "minimal" to minimal()
        )
        for (name in encoders()) {
            for ((label, format) in setups) {
                val c = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: break
                try {
                    c.setCallback(callback(), h)
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = c.createInputSurface()
                    c.start()
                    codec = c
                    DiagLog.i(tag, "encoder started ${width}x${height}@$fps at ${bitrate / 1000} kbps, a key picture every second, ${if (savePower) "saving power, " else ""}${c.name}${if (label == "full") "" else " ($label)"}${if (label != "full" || tuning.isEmpty()) "" else ", " + tuning.entries.joinToString { "${it.key}=${it.value}" }}")
                    return
                } catch (t: Throwable) {
                    DiagLog.w(tag, "encoder $name refused the $label setup for ${width}x$height: ${t.javaClass.simpleName} ${t.message ?: ""}")
                    runCatching { surface?.release() }
                    surface = null
                    runCatching { c.release() }
                }
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
            runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(width, height) }.getOrDefault(true)
        }.ifEmpty { all }
        val ordered = fits.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }.map { it.name }
        val default = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).let { c -> c.name.also { c.release() } } }.getOrNull()
        return (listOfNotNull(default) + ordered).distinct()
    }

    private fun base(): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }

    private fun tuned(tuning: Map<String, Int>): MediaFormat = base().apply {
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        setLong(MediaFormat.KEY_DURATION, 100_000L)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (!gated) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, this@SurfaceEncoder.fps.toFloat())
        runCatching {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (savePower) setFloat(MediaFormat.KEY_OPERATING_RATE, this@SurfaceEncoder.fps.toFloat())
            else setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        tuning.forEach { (k, v) -> setInteger(k, v) }
    }

    private fun plain(level: Int): MediaFormat = base().apply {
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        setInteger(MediaFormat.KEY_LEVEL, level)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (!gated) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, this@SurfaceEncoder.fps.toFloat())
    }

    private fun minimal(): MediaFormat = base().apply {
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(frame)
                        return
                    }
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (key && frame.size >= BIG_FRAME) DiagLog.w(tag, "key picture of ${frame.size / 1024} KB")
                    val drawnUs = info.presentationTimeUs
                    val late = (System.nanoTime() / 1000 - drawnUs) / 1000
                    if (drawnUs > 0 && late > 500 && late <= 60_000 && SystemClock.elapsedRealtime() - lateNoted > 5000) {
                        lateNoted = SystemClock.elapsedRealtime()
                        DiagLog.w(tag, "a picture came out of the encoder $late ms after it was drawn")
                    }
                    if (running.get()) onFrame(frame, key)
                } catch (t: Throwable) {
                    DiagLog.e(tag, "output error", t)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                DiagLog.e(tag, "codec error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val out = ByteArrayOutputStream()
                listOf("csd-0", "csd-1").forEach { k ->
                    format.getByteBuffer(k)?.let { b ->
                        val d = b.duplicate()
                        val bytes = ByteArray(d.remaining())
                        d.get(bytes)
                        out.write(bytes)
                    }
                }
                if (out.size() > 0) onConfig(out.toByteArray())
                DiagLog.i(tag, "format ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
            }
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

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
    }

    companion object {
        private const val BIG_FRAME = 256 * 1024
    }
}
