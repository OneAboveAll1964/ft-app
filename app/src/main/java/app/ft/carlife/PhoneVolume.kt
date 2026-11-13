package app.ft.carlife

import android.content.SharedPreferences

/** A durable recovery checkpoint, kept outside user settings and their backups. */
internal class PhoneVolume(
    private val checkpoint: SharedPreferences,
    private val current: () -> Int,
    private val set: (Int) -> Unit,
    private val maximum: () -> Int,
    private val onError: (Exception) -> Unit = {}
) {
    @Volatile var saved: Int = checkpoint.getInt(KEY, -1)
        private set

    @Synchronized fun mute(): Boolean = attempt {
        if (saved >= 0) return@attempt false
        val level = current()
        if (level <= 0) return@attempt false
        // Never change the system volume until recovery is durable.
        persist(level)
        set(0)
        true
    }

    /** Used both after process recreation and when projection stops. */
    @Synchronized fun restore(): Boolean = attempt {
        if (saved < 0) return@attempt false
        // A nonzero volume is a user's (or another app's) subsequent choice.
        if (current() == 0) set(saved.coerceIn(0, maximum()))
        if (!checkpoint.edit().remove(KEY).commit()) {
            // SharedPreferences updates its in-process map even when disk commit fails.
            // Put the checkpoint back so another Service instance can still retry.
            checkpoint.edit().putInt(KEY, saved).commit()
            throw java.io.IOException("could not clear phone volume checkpoint")
        }
        saved = -1
        true
    }

    @Synchronized fun adjust(up: Boolean): Boolean = attempt {
        if (saved < 0) return@attempt false
        persist((saved + if (up) 1 else -1).coerceIn(0, maximum()))
        true
    }

    private fun persist(level: Int) {
        if (!checkpoint.edit().putInt(KEY, level).commit()) throw java.io.IOException("could not save phone volume checkpoint")
        saved = level
    }

    private inline fun attempt(action: () -> Boolean): Boolean = try { action() } catch (e: Exception) {
        onError(e)
        false
    }

    companion object {
        const val STORE = "carlife_phone_volume_recovery"
        private const val KEY = "restore_level"
    }
}
