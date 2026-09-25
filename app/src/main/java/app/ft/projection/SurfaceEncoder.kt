package app.ft.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.ft.core.DiagLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class SurfaceEncoder(
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val maxBitrate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (frame: ByteArray, keyFrame: Boolean) -> Unit
) {
    private val tag = "Encoder"
    private val fps = fps.coerceIn(1, 60)
    private val startBitrate = (width.toLong() * height * this.fps / 12)
        .coerceIn(600_000L, maxBitrate.toLong().coerceAtLeast(600_000L)).toInt()
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    @Volatile private var config = ByteArray(0)
    var surface: Surface? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = HandlerThread("FT-Encoder").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, startBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, this@SurfaceEncoder.fps.toFloat())
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            runCatching {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index) ?: return
                    if (info.size <= 0) return
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    var frame = ByteArray(info.size)
                    buf.get(frame)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        config = frame
                        onConfig(frame)
                        return
                    }
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (key && config.isNotEmpty()) frame = config + frame
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
                if (out.size() > 0) {
                    config = out.toByteArray()
                    onConfig(config)
                }
                DiagLog.i(tag, "format ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
            }
        }, h)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = c.createInputSurface()
        c.start()
        codec = c
        DiagLog.i(tag, "encoder started ${width}x${height}@$fps at ${startBitrate / 1000} kbps (cap ${maxBitrate / 1000})")
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bps: Int) {
        val c = codec ?: return
        val target = bps.coerceIn(400_000, maxBitrate.coerceAtLeast(400_000))
        runCatching {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) })
            DiagLog.i(tag, "bitrate now ${target / 1000} kbps")
        }
    }

    fun currentConfig(): ByteArray = config

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
}
