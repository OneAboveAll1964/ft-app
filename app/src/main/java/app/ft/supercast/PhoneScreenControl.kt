package app.ft.supercast

/** Calls are serialized on the SuperCast queue. One owner covers both AA and native panes. */
internal class PhoneScreenControl(private val backend: Backend) {
    interface Backend {
        fun awake(on: Boolean): Boolean?
        fun manage(darken: Boolean): Boolean?
        fun restore(): Boolean?
    }

    enum class Result { IDLE, ACTIVE, UNAVAILABLE, REFUSED, RESTORE_FAILED }
    private var restorePending = false
    private var wakePending = false

    fun apply(darken: Boolean?, ready: Boolean): Result {
        if (!ready) {
            // The old helper restores the panel on Binder death; never call a new helper for it.
            restorePending = false
            wakePending = false
            return if (darken == null) Result.IDLE else Result.UNAVAILABLE
        }
        val released = release()
        if (darken == null || released == Result.RESTORE_FAILED) return released
        wakePending = true
        val awake = backend.awake(true)
        if (awake != true) {
            if (release() == Result.RESTORE_FAILED) return Result.RESTORE_FAILED
            return if (awake == null) Result.UNAVAILABLE else Result.REFUSED
        }
        restorePending = true
        val managed = backend.manage(darken)
        if (managed == true) return Result.ACTIVE
        if (release() == Result.RESTORE_FAILED) return Result.RESTORE_FAILED
        return if (managed == null) Result.UNAVAILABLE else Result.REFUSED
    }

    private fun release(): Result {
        if (restorePending && backend.restore() == true) restorePending = false
        if (wakePending && backend.awake(false) == true) wakePending = false
        return if (restorePending || wakePending) Result.RESTORE_FAILED else Result.IDLE
    }
}
