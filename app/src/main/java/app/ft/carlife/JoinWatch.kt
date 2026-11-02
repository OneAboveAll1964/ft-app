package app.ft.carlife

object JoinWatch {
    const val STALL_MS = 5_000L
    const val DEFAULT_TIMEOUT_SECONDS = 5
    const val MIN_TIMEOUT_SECONDS = 1
    const val MAX_TIMEOUT_SECONDS = 600
    val TIMEOUT_SECONDS = listOf(5, 15, 30, 60)
    const val GIVE_UP_MS = 15_000L
    const val GROUP_DETAILS_MS = 5_000L
    const val RENEW_EVERY = 3
    const val DISCOVER_EVERY_MS = 5_000L
    const val ASK_AGAIN_MS = 30_000L

    enum class Next { WAIT, RETRY }

    fun timeoutSeconds(value: Int): Int = value.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS } ?: DEFAULT_TIMEOUT_SECONDS

    fun next(waitedMs: Long, carConnected: Boolean, groupInterfaceUp: Boolean, timeoutMs: Long = STALL_MS): Next = when {
        waitedMs < timeoutMs -> Next.WAIT
        waitedMs >= maxOf(GIVE_UP_MS, timeoutMs) -> Next.RETRY
        carConnected || groupInterfaceUp -> Next.WAIT
        else -> Next.RETRY
    }

    fun renewChannel(failures: Int): Boolean = failures > 0 && failures % RENEW_EVERY == 0

    fun retryDelayMs(failures: Int): Long = when {
        failures < RENEW_EVERY -> 2_500L
        else -> STALL_MS
    }

    fun actOnCarReady(attempting: Boolean): Boolean = !attempting

    fun askCarAgain(unseenMs: Long, carInSight: Boolean): Boolean = !carInSight && unseenMs >= ASK_AGAIN_MS
}
