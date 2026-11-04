package app.ft.carlife

/** Startup deadlines only. Negotiated video and user permission waits are excluded. */
internal class CarLifeStartupWatch {
    enum class Stage(val timeoutMs: Long) {
        BLUETOOTH(8_000), ADDRESS(20_000), COMMAND(30_000), NEGOTIATION(60_000)
    }

    data class Point(val stage: Stage, val generation: Long, val exchange: Long = 0)

    private var point: Point? = null
    private var since = 0L
    private var retries = 0

    fun reset() {
        point = null
        retries = 0
    }

    /** Returns a bounded retry number; polling and repeated broadcasts don't extend a deadline. */
    fun next(current: Point?, now: Long): Int? {
        if (current == null) {
            reset()
            return null
        }
        if (point != current) {
            point = current
            since = now
            retries = 0
            return null
        }
        val wait = (current.stage.timeoutMs * (1L shl retries)).coerceAtMost(60_000)
        if (retries >= MAX_RETRIES || now - since < wait) return null
        since = now
        return ++retries
    }

    companion object { const val MAX_RETRIES = 3 }
}
