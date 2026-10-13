package app.ft.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object AaVideoSink {
    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface
    private val _frame = MutableStateFlow<Pair<Int, Int>?>(null)
    val frame: StateFlow<Pair<Int, Int>?> = _frame
    var width = 0
    var height = 0

    fun attach(surface: Surface, width: Int, height: Int) {
        this.width = width
        this.height = height
        _surface.value = surface
    }

    fun detach(surface: Surface?) {
        if (surface != null) _surface.compareAndSet(surface, null)
    }

    fun frameSize(width: Int, height: Int) {
        _frame.value = width to height
    }
}

class AaVideoDecoder(private val width: Int, private val height: Int) {
    private val tag = "AaDecoder"
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var drain: Thread? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var generation = 0L
    @Volatile private var missed = 0
    @Volatile var askForAFreshPicture: (() -> Unit)? = null

    init {
        if (mediaTek) {
            DiagLog.i(tag, "this phone has a MediaTek chip (${Build.HARDWARE}), so FT makes a fresh decoder whenever the car screen changes")
        }
    }

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            if (s === surface) return
            val had = surface != null
            surface = s
            if (s != null) DiagLog.i(tag, "Android Auto's picture is drawn on the car screen")
            else if (had) DiagLog.i(tag, "Android Auto's picture has nowhere to be drawn")
            if (s == null) {
                stopLocked()
                return
            }
            if (mediaTek && !s.isValid) {
                DiagLog.w(tag, "the new car screen is already gone, waiting for the next one")
                surface = null
                stopLocked()
                return
            }
            val c = codec
            if (running.get() && c != null) {
                if (!mediaTek) {
                    val moved = runCatching { c.setOutputSurface(s) }
                    if (moved.isSuccess) {
                        DiagLog.i(tag, "decoder moved onto the new car screen surface")
                        return
                    }
                    DiagLog.w(tag, "the decoder would not move to the new car screen (${moved.exceptionOrNull()?.javaClass?.simpleName}), making a fresh one")
                }
                stopLocked()
                missed++
            }
            if (!running.get() && config != null) startLocked()
        }
    }

    fun onConfig(csd: ByteArray) {
        val copy = csd.copyOf()
        synchronized(lock) {
            config = copy
            if (!running.get()) {
                if (surface != null) startLocked()
                return
            }
            feedLocked(copy, true)
        }
    }

    fun onFrame(data: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            if (!running.get()) {
                if (surface != null && config != null) startLocked()
                if (!running.get()) {
                    missed++
                    return
                }
            }
            if (mediaTek && surface?.isValid != true) {
                DiagLog.w(tag, "the car screen went away under the decoder, making a fresh one next frame")
                stopLocked()
                return
            }
            feedLocked(data, false, ptsUs)
        }
    }

    private fun startLocked() {
        val s = surface ?: return
        val csd = config ?: return
        if (mediaTek && !s.isValid) {
            DiagLog.w(tag, "not starting the decoder, the car screen is not ready")
            return
        }
        if (!running.compareAndSet(false, true)) return
        val mine = generation + 1
        generation = mine
        var made: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            made = c
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            made = null
            AaVideoSink.frameSize(width, height)
            drain = Thread({ drainLoop(c, mine) }, "FT-AaDrain-$mine").also { it.start() }
            DiagLog.i(tag, "decoder started ${width}x$height on ${runCatching { c.name }.getOrNull()}")
            if (missed > 0) {
                DiagLog.i(tag, "the car screen was not ready for $missed of Android Auto's pictures, asking it for a fresh one")
                missed = 0
                runCatching { askForAFreshPicture?.invoke() }
            }
        } catch (t: Throwable) {
            running.set(false)
            codec = null
            runCatching { made?.release() }
            DiagLog.e(tag, "decoder start failed", t)
        }
    }

    private fun feedLocked(data: ByteArray, isConfig: Boolean, ptsUs: Long = 0L) {
        val c = codec ?: return
        var slot = -1
        var given = false
        try {
            slot = c.dequeueInputBuffer(20_000)
            if (slot < 0) return
            val buf = c.getInputBuffer(slot)
            if (buf == null || data.size > buf.remaining()) {
                if (buf != null) DiagLog.w(tag, "a ${data.size} byte picture did not fit the decoder, skipping it")
                c.queueInputBuffer(slot, 0, 0, ptsUs, 0)
                given = true
                return
            }
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(slot, 0, data.size, ptsUs, if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            given = true
        } catch (t: Throwable) {
            DiagLog.e(tag, "feed failed", t)
            if (slot >= 0 && !given) runCatching { c.queueInputBuffer(slot, 0, 0, ptsUs, 0) }
        }
    }

    private fun drainLoop(c: MediaCodec, mine: Long) {
        val info = MediaCodec.BufferInfo()
        while (running.get() && generation == mine) {
            try {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                    _frames.value = _frames.value + 1
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    shown(c.outputFormat)
                }
            } catch (t: Throwable) {
                if (!running.get() || generation != mine) return
                DiagLog.e(tag, "drain failed", t)
                gaveUp(c, mine)
                return
            }
        }
    }

    private fun gaveUp(dead: MediaCodec, mine: Long) {
        synchronized(lock) {
            if (codec !== dead || generation != mine) return
            running.set(false)
            codec = null
            if (drain === Thread.currentThread()) drain = null
            runCatching { dead.stop() }
            runCatching { dead.release() }
            DiagLog.w(tag, "the decoder stopped, FT will start a fresh one with the next picture")
        }
    }

    private fun shown(f: MediaFormat) {
        val w = if (f.containsKey("crop-right") && f.containsKey("crop-left")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
        val h = if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
        if (w <= 0 || h <= 0) return
        DiagLog.i(tag, "Android Auto draws ${w}x$h" + if (w != width || h != height) ", not the ${width}x$height FT asked for" else "")
        AaVideoSink.frameSize(w, h)
    }

    private fun stopLocked() {
        val old = codec
        generation++
        if (!running.getAndSet(false) && old == null) return
        codec = null
        drain = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    companion object {
        val mediaTek: Boolean by lazy {
            val hardware = Build.HARDWARE.orEmpty().lowercase()
            val board = Build.BOARD.orEmpty().lowercase()
            val maker = Build.MANUFACTURER.orEmpty().lowercase()
            hardware.startsWith("mt") || hardware.contains("mtk") || hardware.contains("mediatek") ||
                board.startsWith("mt") || maker.contains("mediatek")
        }
    }
}
