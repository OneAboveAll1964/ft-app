package app.ft.iccoa

import app.ft.core.ProtoWriter
import java.io.Closeable

/** Starts on control accept, independently of authentication and UI/encoder startup. */
internal class IccoaHeartbeat(
    private val send: (ByteArray) -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit
) : Closeable {
    private var started = false
    private var closed = false

    @Synchronized fun start() {
        if (started || closed) return
        started = true
        tick()
    }

    @Synchronized private fun tick() {
        if (closed) return
        // The reference writes once before its first 2-second sleep.
        if (!send(ProtoWriter().int64(1, System.currentTimeMillis()).toByteArray())) {
            closed = true
            return
        }
        schedule(2_000, ::tick)
    }

    @Synchronized override fun close() { closed = true }
}
