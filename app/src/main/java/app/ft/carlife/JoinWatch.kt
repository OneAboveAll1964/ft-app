package app.ft.carlife

object JoinWatch {
    const val STALL_MS = 5_000L
    const val GIVE_UP_MS = 15_000L
    const val INVITE_MS = 5_000L
    const val RENEW_EVERY = 3

    enum class Next { WAIT, RETRY }

    fun next(waitedMs: Long, carConnected: Boolean, groupInterfaceUp: Boolean): Next = when {
        waitedMs < STALL_MS -> Next.WAIT
        waitedMs >= GIVE_UP_MS -> Next.RETRY
        carConnected || groupInterfaceUp -> Next.WAIT
        else -> Next.RETRY
    }

    fun renewChannel(failures: Int): Boolean = failures > 0 && failures % RENEW_EVERY == 0

    fun retryDelayMs(failures: Int): Long = if (failures < RENEW_EVERY) 1_500L else STALL_MS
}
