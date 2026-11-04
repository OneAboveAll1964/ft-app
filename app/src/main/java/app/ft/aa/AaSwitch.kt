package app.ft.aa

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.ft.FTApp
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AaSwitch {
    enum class Result { ON, LOCKED, NO_HELPER, NOT_INSTALLED, FAILED }

    fun isStarterServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AaStarterAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun canTap(): Boolean = AaStarterAccessibilityService.instance != null && isStarterServiceEnabled(FTApp.instance)

    fun openAccessibilitySettings(context: Context) {
        val component = ComponentName(context, AaStarterAccessibilityService::class.java)
        // This Settings action exists in AOSP but its constant is hidden from the public SDK.
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(detail) }.isSuccess) return
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Blocking entry point for existing IO callers. The service owns every UI action on main. */
    fun start(context: Context): Result {
        if (Thread.currentThread().isInterrupted) return Result.FAILED
        if (Looper.myLooper() == Looper.getMainLooper()) return Result.FAILED
        val port = FTApp.instance.prefs.aaSelfPort
        if (AaInstaller.serverRunning(port)) return Result.ON
        val service = AaStarterAccessibilityService.instance ?: return Result.NO_HELPER
        if (!isStarterServiceEnabled(context)) return Result.NO_HELPER
        if (AaInstaller.settingsPackage(context) != AaInstaller.GEARHEAD || AaInstaller.stub(context)) return Result.NOT_INSTALLED
        if (locked(context)) return Result.LOCKED
        val answer = CompletableFuture<Result>()
        val token = Any()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (answer.isDone) return@post
            try {
                if (AaStarterAccessibilityService.instance !== service || !isStarterServiceEnabled(context)) {
                    answer.complete(Result.NO_HELPER)
                } else if (locked(context)) {
                    answer.complete(Result.LOCKED)
                } else if (!service.armForLaunch(token, port) { answer.complete(it) }) {
                    answer.complete(Result.FAILED)
                } else if (!AaInstaller.openStarterSettings(context)) {
                    service.cancel(token)
                }
            } catch (e: Exception) {
                DiagLog.w("AA", "opening Android Auto starter failed (${e.javaClass.simpleName})")
                service.cancel(token)
                answer.complete(Result.FAILED)
            }
        }
        return try {
            answer.get(AaStarterAccessibilityService.TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.FAILED
        } catch (e: Exception) {
            DiagLog.w("AA", "waiting for Android Auto starter failed (${e.javaClass.simpleName})")
            Result.FAILED
        } finally {
            answer.cancel(false)
            handler.post { service.cancel(token) }
        }
    }

    internal fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
            context.getSystemService(PowerManager::class.java)?.isInteractive == false
}
object AaKeep {
    private const val TAG = "AA"
    private const val GAP_MS = 20_000L
    private const val REST_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastTry = 0L
    @Volatile private var restUntil = 0L
    @Volatile private var waiting: BroadcastReceiver? = null
    @Volatile private var stubSaid = false

    fun poke(context: Context) {
        if (CarLifeService.state.value.session !is CarLifeSession.State.Projecting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry < GAP_MS || now < restUntil) return
        lastTry = now
        scope.launch { switchOn(context) }
    }

    private val one = Any()

    @Volatile private var failedPreparationProfile: String? = null

    /** A new explicit preflight may retry immediately, regardless of an earlier auto-start cooldown. */
    internal fun preparing() {
        failedPreparationProfile = null
        restUntil = 0L
    }

    internal fun prepared(profileId: String, result: AaSwitch.Result) {
        // LOCKED and NO_HELPER may recover when the user unlocks or enables the helper.
        // Only a completed, unsuccessful UI attempt is suppressed for this vehicle attempt.
        failedPreparationProfile = profileId.takeIf { result == AaSwitch.Result.FAILED }
    }

    internal fun preparationFailed(profileId: String, enabled: Boolean): Boolean =
        enabled && failedPreparationProfile == profileId

    fun switchOn(context: Context): Boolean = synchronized(one) {
        // Cancellation can happen while a caller is waiting to enter this monitor.
        if (Thread.currentThread().isInterrupted) false else switchOnNow(context)
    }

    private fun switchOnNow(context: Context): Boolean {
        val app = context.applicationContext
        if (AaInstaller.serverRunning(FTApp.instance.prefs.aaSelfPort)) return true
        val prefs = FTApp.instance.prefs
        if (preparationFailed(prefs.profileId, prefs.aaPrepareBeforeConnect)) return false
        if (!AaInstaller.installed(app)) return false
        val target = AaInstaller.settingsPackage(app) ?: return false
        if (AaInstaller.stub(app, target)) {
            if (!stubSaid) DiagLog.i(TAG, "Android Auto on this phone is only Google's placeholder, so FT does not try to switch its head unit server on")
            stubSaid = true
            return false
        }
        if (SystemClock.elapsedRealtime() < restUntil) return false
        val on = when {
            Root.granted -> Root.startAaServer(app)
            !AaSwitch.canTap() -> return false
            else -> when (AaSwitch.start(app)) {
                AaSwitch.Result.ON -> true
                AaSwitch.Result.LOCKED -> {
                    whenUnlocked(app)
                    return false
                }
                else -> false
            }
        }
        restUntil = if (on) 0L else SystemClock.elapsedRealtime() + REST_MS
        return on
    }

    private fun whenUnlocked(context: Context) {
        if (waiting != null) return
        DiagLog.i(TAG, "the phone is locked, so FT switches Android Auto's head unit server on as soon as it is unlocked")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                waiting = null
                if (FTApp.instance.prefs.autoConnect && AaHeadUnitService.isBusy) scope.launch { switchOn(context) }
            }
        }
        waiting = receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }.onFailure { waiting = null }
    }
}
