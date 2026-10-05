package app.ft.carlife

class VideoFeed(
    private val sendFrame: (ByteArray) -> Unit,
    private val sendEmpty: (heartbeat: Boolean) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    enum class Mode { OFF, HELD, STARTING, LIVE, PAUSED, RESUMING }

    private class Out(val frame: ByteArray?, val heartbeat: Boolean)

    private val lock = Any()
    @Volatile var mode = Mode.OFF
        private set
    private var config = ByteArray(0)
    private var configSent = false
    private val held = ArrayList<ByteArray>()
    private var heldBytes = 0L
    private var dropped = 0
    @Volatile var lastSentAt = 0L
        private set
    @Volatile var resumedAfter = 0
        private set

    fun open() = synchronized(lock) {
        mode = Mode.HELD
        config = ByteArray(0)
        configSent = false
        dropped = 0
        clearHeld()
    }

    fun close() = synchronized(lock) {
        mode = Mode.OFF
        config = ByteArray(0)
        configSent = false
        clearHeld()
    }

    fun config(bytes: ByteArray) = synchronized(lock) {
        if (!bytes.contentEquals(config)) {
            config = bytes
            configSent = false
        }
    }

    fun start(): Mode = synchronized(lock) {
        when (mode) {
            Mode.HELD -> {
                mode = Mode.STARTING
                dropped = 0
            }
            Mode.PAUSED -> {
                mode = Mode.RESUMING
                dropped = 0
            }
            else -> Unit
        }
        mode
    }

    fun pause(): Mode = synchronized(lock) {
        if (mode == Mode.STARTING || mode == Mode.LIVE || mode == Mode.RESUMING) mode = Mode.PAUSED
        mode
    }

    fun frame(frame: ByteArray, key: Boolean) {
        val out = synchronized(lock) { decide(frame, key) }
        out.forEach(::emit)
    }

    fun idle(gapMs: Long): Boolean {
        val m = mode
        if (m == Mode.OFF || m == Mode.HELD) return false
        if (clock() - lastSentAt < gapMs) return false
        emit(Out(null, heartbeat = m != Mode.STARTING))
        return true
    }

    private fun decide(frame: ByteArray, key: Boolean): List<Out> = when (mode) {
        Mode.OFF -> emptyList()
        Mode.HELD -> {
            hold(frame, key)
            emptyList()
        }
        Mode.STARTING -> {
            hold(frame, key)
            if (held.isEmpty()) {
                listOf(Out(null, heartbeat = false))
            } else {
                val out = ArrayList<Out>(held.size)
                held.forEachIndexed { i, f -> out += Out(payload(f, i == 0), false) }
                clearHeld()
                mode = Mode.LIVE
                out
            }
        }
        Mode.LIVE -> listOf(Out(payload(frame, key), false))
        Mode.PAUSED -> listOf(Out(null, heartbeat = true))
        Mode.RESUMING -> {
            if (key && dropped > RESUME_DROPS) {
                resumedAfter = dropped
                mode = Mode.LIVE
                listOf(Out(payload(frame, true), false))
            } else {
                dropped++
                if (dropped % 3 == 0) listOf(Out(null, heartbeat = true)) else emptyList()
            }
        }
    }

    private fun payload(frame: ByteArray, key: Boolean): ByteArray {
        if (!key || configSent || config.isEmpty()) return frame
        configSent = true
        return config + frame
    }

    private fun hold(frame: ByteArray, key: Boolean) {
        if (key) clearHeld()
        if (held.isEmpty() && !key) return
        held += frame
        heldBytes += frame.size
        if (held.size > MAX_HELD || heldBytes > MAX_HELD_BYTES) clearHeld()
    }

    private fun clearHeld() {
        held.clear()
        heldBytes = 0
    }

    private fun emit(o: Out) {
        lastSentAt = clock()
        if (o.frame != null) sendFrame(o.frame) else sendEmpty(o.heartbeat)
    }

    companion object {
        const val RESUME_DROPS = 10
        private const val MAX_HELD = 90
        private const val MAX_HELD_BYTES = 6L * 1024 * 1024
    }
}
