package app.ft.carlife

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import app.ft.core.DiagLog
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(private val onPcm: (ByteArray) -> Unit) {
    private val tag = "Audio"
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val active: Boolean get() = running.get()
    @Volatile var startedAt = 0L; private set
    @Volatile var lastLoudAt = 0L; private set

    fun start(projection: MediaProjection): Boolean {
        stop()
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, 16384))
                .build()
            record = r
            startedAt = android.os.SystemClock.elapsedRealtime()
            lastLoudAt = startedAt
            running.set(true)
            r.startRecording()
            thread = Thread {
                val buf = ByteArray(3840)
                var blocks = 0L
                var peak = 0
                while (running.get()) {
                    val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (n <= 0) { if (n < 0) break else continue }
                    var i = 0
                    var loudest = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                        val a = if (s < 0) -s else s
                        if (a > loudest) loudest = a
                        i += 2
                    }
                    if (loudest > peak) peak = loudest
                    if (loudest > SoundCheck.LOUD) lastLoudAt = android.os.SystemClock.elapsedRealtime()
                    blocks++
                    if (blocks % 250 == 0L) {
                        DiagLog.i(tag, "sound to the car: loudest sample $peak/32767${if (peak < 40) " (nothing playing)" else ""}")
                        peak = 0
                    }
                    onPcm(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
            }.also { it.isDaemon = true; it.start() }
            DiagLog.i(tag, "audio capture started 48000/2/16")
            true
        } catch (t: Throwable) {
            DiagLog.e(tag, "audio capture failed", t)
            running.set(false)
            record = null
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
        DiagLog.i(tag, "audio capture stopped")
    }
}
