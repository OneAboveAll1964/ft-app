package app.ft.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object AaVideoSink {
    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface
    var width = 0
    var height = 0

    fun attach(surface: Surface, width: Int, height: Int) {
        this.width = width
        this.height = height
        _surface.value = surface
    }

    fun detach() { _surface.value = null }
}

class AaVideoDecoder(private val width: Int, private val height: Int) {
    private val tag = "AaDecoder"
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    private var drain: Thread? = null
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames
    @Volatile private var surface: Surface? = null

    fun setSurface(s: Surface?) {
        surface = s
        if (s != null && !running.get() && config != null) start()
        if (s == null) stop()
    }

    fun onConfig(csd: ByteArray) {
        config = csd
        if (!running.get() && surface != null) start() else feed(csd, true)
    }

    private fun start() {
        val s = surface ?: return
        val csd = config ?: return
        if (!running.compareAndSet(false, true)) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            drain = Thread({ drainLoop(c) }, "FT-AaDrain").also { it.start() }
            DiagLog.i(tag, "decoder started ${width}x$height")
        } catch (t: Throwable) {
            running.set(false)
            DiagLog.e(tag, "decoder start failed", t)
        }
    }

    fun onFrame(data: ByteArray, ptsUs: Long) {
        if (!running.get()) {
            if (surface != null && config != null) start()
            if (!running.get()) return
        }
        feed(data, false, ptsUs)
    }

    private fun feed(data: ByteArray, isConfig: Boolean, ptsUs: Long = 0L) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        } catch (t: Throwable) {
            DiagLog.e(tag, "feed failed", t)
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                    _frames.value = _frames.value + 1
                }
            } catch (t: Throwable) {
                if (running.get()) DiagLog.e(tag, "drain failed", t)
                break
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        drain = null
    }
}
