package app.ft.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread confined. Transport retries share the result until an explicit new attempt. */
internal class ConnectionPreparation(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> Unit
) {
    private var completed = false
    private var job: Job? = null
    private var continuation: (() -> Unit)? = null

    fun run(enabled: Boolean, connect: () -> Unit) {
        if (!enabled) {
            cancel(reset = false)
            connect()
            return
        }
        if (completed) {
            connect()
            return
        }
        // A USB attachment can supersede a pending wireless start without a second AA launch.
        continuation = connect
        if (job != null) return
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            prepare()
            completed = true
            val next = continuation
            continuation = null
            job = null
            next?.invoke()
        }
        job = pending
        pending.start()
    }

    fun cancel(reset: Boolean = true) {
        continuation = null
        job?.cancel()
        job = null
        if (reset) completed = false
    }
}
