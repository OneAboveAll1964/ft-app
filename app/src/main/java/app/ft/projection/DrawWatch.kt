package app.ft.projection

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import app.ft.core.DiagLog
import app.ft.core.ScreenWake

class DrawWatch(private val context: Context) {
    private val tag = "Display"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var asked = 0L
    @Volatile private var answered = 0L
    private var stalledAt = 0L
    private var woke = 0L
    private var thread: Thread? = null

    fun start() {
        stop()
        asked = 0L
        answered = 0L
        stalledAt = 0L
        woke = 0L
        thread = Thread({ loop() }, "FT-DrawWatch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val now = SystemClock.uptimeMillis()
            val pending = asked != 0L && answered < asked
            if (pending && now - asked > STALL_MS) {
                if (stalledAt == 0L) {
                    stalledAt = asked
                    onStall(now - asked)
                } else if (woke != 0L && now - woke > RETRY_MS) {
                    woke = 0L
                    onStall(now - stalledAt)
                }
            } else if (!pending) {
                if (stalledAt != 0L) {
                    DiagLog.i(tag, "the car screen draws again after ${answered - stalledAt} ms")
                    stalledAt = 0L
                    woke = 0L
                }
                asked = now
                main.post { Choreographer.getInstance().postFrameCallback { answered = SystemClock.uptimeMillis() } }
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun onStall(ms: Long) {
        val on = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        DiagLog.w(tag, "the car screen has not drawn for $ms ms, the phone screen is ${if (on) "on" else "off"}${if (locked) " and locked" else ""}")
        if (!on) {
            woke = SystemClock.uptimeMillis()
            ScreenWake.wake(context, "the phone stops drawing the car screen while its own screen is off")
        } else {
            val stack = Looper.getMainLooper().thread.stackTrace.take(12).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            DiagLog.w(tag, "main thread is at $stack")
        }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val STALL_MS = 1500L
        private const val RETRY_MS = 6000L
    }
}
