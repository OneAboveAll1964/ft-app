package app.ft.aa

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** One worker for both periodic discovery and immediate recovery after a real close. */
internal class AaReconnect {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun wake() { wakeups.trySend(Unit) }

    suspend fun run(attempt: suspend () -> Boolean) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val connected = attempt()
            val wait = if (connected) {
                failures = 0
                3000L
            } else {
                RETRY_MS[failures].also { failures = minOf(failures + 1, RETRY_MS.lastIndex) }
            }
            withTimeoutOrNull(wait) {
                wakeups.receive()
                // A waiting receiver and the conflated slot can both hold a wakeup.
                while (wakeups.tryReceive().isSuccess) { }
            }
        }
    }

    private companion object {
        val RETRY_MS = longArrayOf(250, 500, 1000, 3000)
    }
}
