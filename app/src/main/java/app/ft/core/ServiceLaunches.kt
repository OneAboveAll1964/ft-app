package app.ft.core

import android.content.Intent
import java.util.concurrent.atomic.AtomicInteger

/** Keeps profile selection locked between dispatch and Android's service callbacks. */
internal class ServiceLaunches(private val onChanged: () -> Unit = { CarConnection.lifecycleChanged() }) {
    private val pending = AtomicInteger()
    val hasPending: Boolean get() = pending.get() > 0

    fun dispatch(intent: Intent) {
        pending.incrementAndGet()
        intent.putExtra(EXTRA, true)
        onChanged()
    }

    fun received(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA, false) == true) finished()
    }

    fun finished() {
        pending.updateAndGet { (it - 1).coerceAtLeast(0) }
        onChanged()
    }

    private companion object { const val EXTRA = "app.ft.PROFILE_SERVICE_LAUNCH" }
}
