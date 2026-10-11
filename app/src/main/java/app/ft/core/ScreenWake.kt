package app.ft.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import app.ft.WakeActivity

object ScreenWake {
    private const val TAG = "Screen"
    private const val HOLD_MS = 10_000L
    private const val CHECK_MS = 700L

    fun wake(context: Context, why: String) {
        val app = context.applicationContext
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        DiagLog.i(TAG, "turning the phone screen on: $why")
        runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "FT:car-screen")
                .apply { setReferenceCounted(false) }
                .acquire(HOLD_MS)
        }.onFailure { DiagLog.w(TAG, "no screen wake lock: ${it.message}") }
        Handler(Looper.getMainLooper()).postDelayed({
            if (pm.isInteractive) {
                DiagLog.i(TAG, "phone screen is on")
                return@postDelayed
            }
            DiagLog.i(TAG, "the wake lock left the screen off, asking a blank screen to turn it on")
            runCatching {
                app.startActivity(
                    Intent(app, WakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            }.onFailure { DiagLog.w(TAG, "could not turn the phone screen on: ${it.message}") }
        }, CHECK_MS)
    }
}
