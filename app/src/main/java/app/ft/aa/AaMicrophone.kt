package app.ft.aa

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.ft.core.DiagLog
import java.util.concurrent.atomic.AtomicBoolean

class AaMicrophone(private val onPcm: (ByteArray) -> Unit) {
    private val tag = "AA"
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val active: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
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
            running.set(true)
            r.startRecording()
            thread = Thread {
                val buf = ByteArray(CHUNK)
                while (running.get()) {
                    val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (n <= 0) {
                        if (n < 0) break else continue
                    }
                    onPcm(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
            }.apply { isDaemon = true; start() }
            DiagLog.i(tag, "microphone open for Android Auto")
            true
        } catch (t: Throwable) {
            DiagLog.e(tag, "could not open the microphone for Android Auto", t)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
        DiagLog.i(tag, "microphone closed")
    }

    companion object {
        const val RATE = 16000
        private const val CHUNK = 2048
    }
}
