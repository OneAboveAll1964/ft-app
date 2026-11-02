package app.ft.aa

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.ft.core.DiagLog
import java.util.concurrent.atomic.AtomicBoolean

class AaMicrophone(private val onPcm: (ByteArray) -> Unit) {
    private val tag = "AA"
    @Volatile private var running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var cleanupThread: Thread? = null

    val active: Boolean get() = running.get()

    @Synchronized fun start(deliver: (ByteArray) -> Unit = onPcm): Boolean {
        if (running.get()) return true
        return try {
            awaitCleanup()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK * 4)
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                DiagLog.w(tag, "the microphone would not open for Android Auto")
                runCatching { r.release() }
                return false
            }
            record = r
            // Each recorder owns its flag: restarting must never revive the previous reader.
            val captureRunning = AtomicBoolean(true)
            running = captureRunning
            r.startRecording()
            thread = Thread {
                val buf = ByteArray(CHUNK)
                while (captureRunning.get()) {
                    val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (!captureRunning.get()) break
                    if (n <= 0) {
                        if (n < 0) break else continue
                    }
                    deliver(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
            }.apply { isDaemon = true; start() }
            DiagLog.i(tag, "microphone open for Android Auto")
            true
        } catch (t: SecurityException) {
            DiagLog.e(tag, "microphone permission was denied or revoked", t)
            running.set(false)
            runCatching { record?.release() }
            record = null
            false
        } catch (t: Throwable) {
            DiagLog.e(tag, "could not open the microphone for Android Auto", t)
            running.set(false)
            runCatching { record?.release() }
            record = null
            false
        }
    }

    @Synchronized fun stop() {
        closeRecord(detachRecord())
        awaitCleanup()
    }

    @Synchronized fun stopAsync() {
        val stoppedRecord = detachRecord() ?: return
        // AudioRecord.stop/release can block. The car PCM reader only detaches this
        // capture; a later phone start waits for cleanup before acquiring the device.
        cleanupThread = Thread({ closeRecord(stoppedRecord) }, "aa-microphone-stop")
            .apply { isDaemon = true; start() }
    }

    private fun detachRecord(): AudioRecord? {
        running.set(false)
        val stoppedRecord = record
        record = null
        thread = null
        return stoppedRecord
    }

    private fun closeRecord(stoppedRecord: AudioRecord?) {
        if (stoppedRecord == null) return
        runCatching { stoppedRecord.stop() }
        runCatching { stoppedRecord.release() }
        DiagLog.i(tag, "microphone closed")
    }

    private fun awaitCleanup() {
        val cleanup = cleanupThread ?: return
        // Cleanup never acquires this object's monitor or touches the current recorder.
        // Preserve interruption, but do not open another recorder before release finishes.
        var interrupted = false
        while (cleanup.isAlive) {
            try {
                cleanup.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        cleanupThread = null
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        const val RATE = 16000
        private const val CHUNK = 2048
    }
}
