package app.ft.carlife

/** Bounded recovery for an announced car which never appears in peer discovery. */
internal class WirelessPeerWatch {
    enum class Action { ASK_NAME, REARM_WIFI, RECONNECT_BLUETOOTH }

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
        val (delay, action) = STEPS.getOrNull(attempt) ?: return null
        if (now - since < delay) return null
        since = now
        attempt++
        return action
    }

    private companion object {
        // Give full-band and channel-constrained P2P discovery enough time (~62s total)
        // before tearing down an active Bluetooth SPP session; premature BT disconnects
        // can leave some head units (e.g. DesaySV) silent until BT is toggled on the car.
        val STEPS = listOf(
            2_000L to Action.ASK_NAME,
            5_000L to Action.ASK_NAME,
            10_000L to Action.REARM_WIFI,
            15_000L to Action.ASK_NAME,
            30_000L to Action.RECONNECT_BLUETOOTH
        )
    }
}
