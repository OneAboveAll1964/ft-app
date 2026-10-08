package app.ft.carlife

object JoinWatch {
    const val STALL_MS = 5_000L
    const val GIVE_UP_MS = 15_000L
    const val INVITE_MS = 5_000L
    const val RENEW_EVERY = 3
    const val DECLINE_SETTLE_MS = 1_000L
    const val QUICK_RETRY_MS = 1_000L
    const val FRESH_MS = 2_000L
    const val DISCOVER_EVERY_MS = 5_000L
    const val ASK_AGAIN_MS = 30_000L

    enum class Next { WAIT, RETRY, DECLINED }

    fun next(waitedMs: Long, carConnected: Boolean, groupInterfaceUp: Boolean, carDeclined: Boolean = false): Next = when {
        carDeclined && !carConnected && !groupInterfaceUp && waitedMs >= DECLINE_SETTLE_MS -> Next.DECLINED
        waitedMs < STALL_MS -> Next.WAIT
        waitedMs >= GIVE_UP_MS -> Next.RETRY
        carConnected || groupInterfaceUp -> Next.WAIT
        else -> Next.RETRY
    }

    fun renewChannel(failures: Int): Boolean = failures > 0 && failures % RENEW_EVERY == 0

    fun retryDelayMs(failures: Int, declined: Boolean = false): Long = when {
        declined -> QUICK_RETRY_MS
        failures < RENEW_EVERY -> 2_500L
        else -> STALL_MS
    }

    fun interruptForReady(attemptAgeMs: Long, attempting: Boolean): Boolean = !attempting || attemptAgeMs >= FRESH_MS

    fun askCarAgain(unseenMs: Long, carInSight: Boolean): Boolean = !carInSight && unseenMs >= ASK_AGAIN_MS
}
