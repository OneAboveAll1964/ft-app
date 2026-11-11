package app.ft.aa

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AaSessionLifecycle(
    private val interrupt: () -> Unit,
    private val cleanup: (String) -> Unit
) {
    private val started = AtomicBoolean(false)
    private val reason = AtomicReference<String?>(null)
    private val gate = Any()
    private var cleaned = false

    val running: Boolean get() = started.get() && reason.get() == null

    fun start(): Boolean = started.compareAndSet(false, true) && reason.get() == null

    fun dispatch(block: () -> Unit) {
        synchronized(gate) {
            if (!running) return
            try {
                block()
            } finally {
                finishLocked()
            }
        }
    }

    fun close(why: String) {
        if (reason.compareAndSet(null, why)) interrupt()
        if (!Thread.holdsLock(gate)) synchronized(gate) { finishLocked() }
    }

    private fun finishLocked() {
        val why = reason.get() ?: return
        if (cleaned) return
        cleaned = true
        cleanup(why)
    }
}
