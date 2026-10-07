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
}

class AaVideoDecoder(private val width: Int, private val height: Int) {
    private val tag = "AaDecoder"
    private val codecLock = Any()
    private val running = AtomicBoolean(false)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var drain: Thread? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var generation = 0L

    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames

    private val isMediaTek: Boolean by lazy {
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        hardware.contains("mtk") ||
            hardware.contains("mediatek") ||
            manufacturer.contains("mediatek") ||
            brand.contains("mediatek")
    }

    init {
        DiagLog.i(tag, "decoder created ${width}x$height hardware=${Build.HARDWARE} manufacturer=${Build.MANUFACTURER} mediaTek=$isMediaTek")
    }

    fun setSurface(s: Surface?) {
        synchronized(codecLock) {
            if (s === surface) {
                if (s != null && !s.isValid) {
                    DiagLog.w(tag, "same Surface object is no longer valid")
                    surface = null
                    stopLocked()
                }
                return
            }

            val hadSurface = surface != null
            surface = s

            if (s == null) {
                if (hadSurface) DiagLog.i(tag, "Android Auto's picture has nowhere to be drawn")
                stopLocked()
                return
            }

            if (!s.isValid) {
                DiagLog.w(tag, "new Surface is invalid; decoder will not be configured")
                surface = null
                stopLocked()
                return
            }

            DiagLog.i(tag, "Android Auto's picture is drawn on the car screen")

            val currentCodec = codec
            if (!running.get() || currentCodec == null) {
                if (config != null) startLocked()
                return
            }

            if (isMediaTek) {
                DiagLog.i(tag, "MediaTek: recreating decoder for new Surface")
                stopLocked()
                if (surface?.isValid == true && config != null) startLocked()
                return
            }

            val moved = runCatching { currentCodec.setOutputSurface(s) }
            if (moved.isSuccess) {
                DiagLog.i(tag, "decoder moved onto the new car screen Surface")
                return
            }

            DiagLog.w(tag, "setOutputSurface() failed; recreating decoder: ${moved.exceptionOrNull()?.javaClass?.simpleName}")
            stopLocked()
            if (surface?.isValid == true && config != null) startLocked()
        }
    }

    fun onConfig(csd: ByteArray) {
        val copied = csd.copyOf()
        synchronized(codecLock) {
            config = copied
            if (!running.get()) {
                if (surface?.isValid == true) startLocked()
                else DiagLog.i(tag, "received decoder config but Surface is unavailable")
                return
            }
            feedLocked(copied, true)
        }
    }

    fun onFrame(data: ByteArray, ptsUs: Long) {
        synchronized(codecLock) {
            if (!running.get()) {
                if (surface?.isValid == true && config != null) startLocked()
                else return
            }

            val s = surface
            if (s == null || !s.isValid) {
                DiagLog.w(tag, "Surface became invalid while receiving video frame")
                stopLocked()
                return
            }

            feedLocked(data, false, ptsUs)
        }
    }

    private fun startLocked() {
        if (running.get()) return

        val s = surface
        if (s == null || !s.isValid) {
            DiagLog.w(tag, "decoder start aborted: Surface is null/invalid")
            return
        }

        val csd = config
        if (csd == null || csd.isEmpty()) {
            DiagLog.w(tag, "decoder start aborted: H.264 config is unavailable")
            return
        }

        val myGeneration = generation + 1L
        generation = myGeneration
        running.set(true)

        var newCodec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

            newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            if (!s.isValid) throw IllegalStateException("Surface became invalid before configure()")

            newCodec.configure(format, s, null, 0)
            newCodec.start()
            codec = newCodec

            val codecName = runCatching { newCodec.name }.getOrNull()
            DiagLog.i(tag, "decoder started ${width}x$height codec=$codecName generation=$myGeneration mediaTek=$isMediaTek")

            val decoder = newCodec
            drain = Thread({ drainLoop(decoder, myGeneration) }, "FT-AaDrain-$myGeneration").also { it.start() }
            newCodec = null
        } catch (t: Throwable) {
            DiagLog.e(tag, "decoder start failed generation=$myGeneration", t)
            running.set(false)
            codec = null
            runCatching { newCodec?.stop() }
            runCatching { newCodec?.release() }
        }
    }

    private fun feedLocked(data: ByteArray, isConfig: Boolean, ptsUs: Long = 0L) {
        val c = codec ?: return
        if (!running.get()) return

        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (data.size > buf.remaining()) {
                DiagLog.w(tag, "decoder input buffer too small: data=${data.size} remaining=${buf.remaining()}")
                return
            }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        } catch (t: Throwable) {
            DiagLog.e(tag, "feed failed", t)
        }
    }

    private fun drainLoop(c: MediaCodec, myGeneration: Long) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            if (codec !== c || generation != myGeneration) return

            try {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    val s = surface
                    if (s == null || !s.isValid) {
                        DiagLog.w(tag, "decoder output available but Surface is invalid")
                        handleDrainFailure(c, myGeneration, null)
                        return
                    }
                    c.releaseOutputBuffer(idx, true)
                    _frames.value = _frames.value + 1
                }
            } catch (t: Throwable) {
                if (codec !== c || generation != myGeneration || !running.get()) return
                DiagLog.e(tag, "drain failed generation=$myGeneration", t)
                handleDrainFailure(c, myGeneration, t)
                return
            }
        }
    }

    private fun handleDrainFailure(failedCodec: MediaCodec, failedGeneration: Long, error: Throwable?) {
        synchronized(codecLock) {
            if (codec !== failedCodec || generation != failedGeneration) return
            running.set(false)
            codec = null
            if (drain === Thread.currentThread()) drain = null
            runCatching { failedCodec.stop() }
            runCatching { failedCodec.release() }
            DiagLog.w(tag, "decoder disabled after drain failure; will recover on next frame/Surface event")
            if (error != null) DiagLog.e(tag, "decoder failure details", error)
        }
    }

    private fun stopLocked() {
        val oldCodec = codec
        generation++
        running.set(false)
        codec = null
        drain = null

        if (oldCodec == null) return
        DiagLog.i(tag, "stopping decoder")
        runCatching { oldCodec.stop() }.onFailure { DiagLog.w(tag, "decoder stop failed: ${it.message}") }
        runCatching { oldCodec.release() }.onFailure { DiagLog.w(tag, "decoder release failed: ${it.message}") }
    }

    fun stop() {
        synchronized(codecLock) {
            stopLocked()
        }
    }
}
