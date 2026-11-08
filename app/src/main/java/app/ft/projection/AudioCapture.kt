package app.ft.projection

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import app.ft.core.DiagLog
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture internal constructor(
    private val createRecord: (MediaProjection) -> AudioRecord,
    private val onPcm: (ByteArray) -> Unit,
    private val pauseAfterEmptyRead: () -> Unit = { Thread.sleep(10) }
) {
    constructor(onPcm: (ByteArray) -> Unit) : this(::buildRecord, onPcm)

    private val tag = "Audio"
    private class Capture(val record: AudioRecord) {
        private val released = AtomicBoolean(false)
        fun close() {
            if (!released.compareAndSet(false, true)) return
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }
    @Volatile private var capture: Capture? = null
    val active: Boolean get() = capture != null
    @Volatile var startedAt = 0L; private set
    @Volatile var lastLoudAt = 0L; private set

    @Synchronized
    fun start(projection: MediaProjection): Boolean {
        stop()
        var owner: Capture? = null
        return try {
            val next = Capture(createRecord(projection))
            owner = next
            next.record.startRecording()
            startedAt = android.os.SystemClock.elapsedRealtime()
            lastLoudAt = startedAt
            capture = next
            Thread { read(next) }.apply { isDaemon = true; start() }
            DiagLog.i(tag, "audio capture started 48000/2/16")
            true
        } catch (t: Throwable) {
            if (capture === owner) capture = null
            owner?.close()
            DiagLog.e(tag, "audio capture failed", t)
            false
        }
    }

    private fun read(owner: Capture) {
        try {
            val buf = ByteArray(3840)
            var blocks = 0L
            var peak = 0
            while (capture === owner) {
                val n = owner.record.read(buf, 0, buf.size)
                if (n < 0) {
                    DiagLog.w(tag, "audio capture read failed: $n")
                    break
                }
                // A zero-byte native read can return immediately. Bound retries
                // without sending silence or holding the capture/stop monitor.
                if (n == 0) {
                    if (capture !== owner) break
                    pauseAfterEmptyRead()
                    continue
                }
                // Serialize publication with stop: a late native read must not send
                // PCM or update the new capture's activity timestamps.
                synchronized(this) {
                    if (capture !== owner) return
                    var loudest = 0
                    var i = 0
                    while (i + 1 < n) {
                        val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                        loudest = maxOf(loudest, kotlin.math.abs(sample))
                        i += 2
                    }
                    peak = maxOf(peak, loudest)
                    if (loudest > 300) lastLoudAt = android.os.SystemClock.elapsedRealtime()
                    blocks++
                    if (blocks % 250 == 0L) {
                        DiagLog.i(tag, "sound to the car: loudest sample $peak/32767${if (peak < 40) " (nothing playing)" else ""}")
                        peak = 0
                    }
                    onPcm(buf.copyOf(n))
                }
            }
        } catch (t: Exception) {
            DiagLog.e(tag, "audio capture read failed", t)
        } finally {
            synchronized(this) { if (capture === owner) capture = null }
            owner.close()
        }
    }

    @Synchronized
    fun stop() {
        val previous = capture ?: return
        capture = null
        previous.close()
        DiagLog.i(tag, "audio capture stopped")
    }

    companion object {
        /**
         * Android 10+ third-party playback capture permits only MEDIA, GAME and
         * UNKNOWN. NAVIGATION_GUIDANCE (12) is not capturable even when requested
         * through addMatchingUsage; app/track capture policy can further deny it.
         * Keep this explicit allowlist independent of AA's own speech channel.
         * AOSP android12-release AudioPlaybackCaptureConfiguration.java.
         */
        internal val capturableUsages: List<Int> = listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN
        )

        private fun buildRecord(projection: MediaProjection): AudioRecord {
            val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
            capturableUsages.forEach(builder::addMatchingUsage)
            val config = builder.build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, 16384))
                .build()
        }
    }
}
