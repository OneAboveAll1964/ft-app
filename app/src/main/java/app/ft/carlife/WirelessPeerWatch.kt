package app.ft.carlife

/** Bounded recovery for an announced car which never appears in peer discovery. */
internal class WirelessPeerWatch {
    enum class Action { ASK_NAME, RECONNECT_BLUETOOTH }

    private var target: Pair<Long, String>? = null
    private var since = 0L
    private var attempt = 0

    fun reset() {
        target = null
        attempt = 0
    }

    fun next(generation: Long, name: String, eligible: Boolean, now: Long): Action? {
        if (!eligible) {
            reset()
            return null
        }
        val key = generation to name
        if (target != key) {
            target = key
            since = now
            attempt = 0
        }
        if (attempt >= DELAYS.size || now - since < DELAYS[attempt]) return null
        since = now
        attempt++
        return if (attempt == DELAYS.size) Action.RECONNECT_BLUETOOTH else Action.ASK_NAME
    }

    private companion object {
        val DELAYS = longArrayOf(2_000, 5_000, 10_000, 10_000, 10_000)
    }
}
