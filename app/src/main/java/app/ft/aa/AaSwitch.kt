package app.ft.aa

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AaSwitch {
    private const val TAG = "AA"
    private const val START = "Start head unit server"
    private const val STOP = "Stop head unit server"
    private const val STEP_MS = 40L

    enum class Result { ON, NO_DEVELOPER_MENU, LOCKED, NO_HELPER, NOT_INSTALLED, FAILED }

    @Volatile private var busy = false

    fun canTap(): Boolean = FTTouchService.instance != null

    fun start(context: Context): Result {
        val port = FTApp.instance.prefs.aaSelfPort
        if (AaInstaller.serverRunning(port)) return Result.ON
        val service = FTTouchService.instance ?: return Result.NO_HELPER
        val target = AaInstaller.settingsPackage(context) ?: return Result.NOT_INSTALLED
        if (AaInstaller.stub(context, target)) return Result.NOT_INSTALLED
        if (locked(context)) return Result.LOCKED
        synchronized(this) {
            if (busy) return Result.FAILED
            busy = true
        }
        val began = SystemClock.elapsedRealtime()
        try {
            if (!AaInstaller.openSettings(context, fallback = false)) return Result.FAILED
            val labels = overflowLabels(context, target)
            val more = waitFor(4000) { find(service, target) { overflow(it, labels) } }
            if (more == null) {
                DiagLog.w(TAG, "Android Auto's settings opened, but its menu button never showed")
                leave(service, target)
                return Result.FAILED
            }
            press(service, more)
            val item = waitFor(2000) { find(service, target) { it.text?.toString().let { t -> t == START || t == STOP } } }
            if (item == null) {
                DiagLog.w(TAG, "Android Auto's menu has no \"$START\", so its developer settings are off")
                leave(service, target)
                return Result.NO_DEVELOPER_MENU
            }
            if (item.text?.toString() == START) press(service, item) else service.back()
            val on = waitFor(3000) { if (AaInstaller.serverRunning(port)) true else null } == true
            waitFor(800) { if (find(service, target) { it.text?.toString().let { t -> t == START || t == STOP } } == null) true else null }
            leave(service, target)
            val took = SystemClock.elapsedRealtime() - began
            if (on) DiagLog.i(TAG, "switched on Android Auto's head unit server through accessibility in $took ms")
            else DiagLog.w(TAG, "tapped \"$START\" but Android Auto's head unit server did not open its port ($took ms)")
            return if (on) Result.ON else Result.FAILED
        } catch (t: Throwable) {
            DiagLog.w(TAG, "switching Android Auto's head unit server on failed: $t")
            return Result.FAILED
        } finally {
            busy = false
        }
    }

    private fun leave(service: FTTouchService, target: String) {
        repeat(3) {
            if (!showing(service, target)) return
            service.back()
            if (waitFor(700) { if (showing(service, target)) null else true } == true) return
        }
    }

    private fun showing(service: FTTouchService, target: String): Boolean =
        roots(service).any { it.packageName?.toString() == target }

    private fun roots(service: FTTouchService): List<AccessibilityNodeInfo> {
        val found = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList()).toMutableList()
        service.rootInActiveWindow?.let { active -> if (found.none { it == active }) found += active }
        return found
    }

    private fun locked(context: Context): Boolean {
        val keys = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return keys?.isKeyguardLocked == true || power?.isInteractive == false
    }

    private fun overflowLabels(context: Context, target: String): Set<String> {
        val found = linkedSetOf("More options")
        runCatching {
            val res = context.packageManager.getResourcesForApplication(target)
            listOf("abc_action_menu_overflow_description", "m3c_top_app_bar_overflow_description", "more_options").forEach { name ->
                val id = res.getIdentifier(name, "string", target)
                if (id != 0) found += res.getString(id)
            }
        }
        runCatching {
            val id = Resources.getSystem().getIdentifier("action_menu_overflow_description", "string", "android")
            if (id != 0) found += Resources.getSystem().getString(id)
        }
        return found
    }

    private fun overflow(node: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        val said = node.contentDescription?.toString() ?: return false
        return said in labels
    }

    private fun find(service: FTTouchService, target: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in roots(service)) {
            if (root.packageName?.toString() != target) continue
            val hit = walk(root, match)
            if (hit != null) return hit
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = walk(child, match)
            if (hit != null) return hit
        }
        return null
    }

    private fun press(service: FTTouchService, node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        service.tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun <T> waitFor(timeoutMs: Long, check: () -> T?): T? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val got = runCatching { check() }.getOrNull()
            if (got != null) return got
            if (SystemClock.elapsedRealtime() >= end) return null
            Thread.sleep(STEP_MS)
        }
    }
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

    fun switchOn(context: Context): Boolean = synchronized(one) { switchOnNow(context) }

    private fun switchOnNow(context: Context): Boolean {
        val app = context.applicationContext
        if (AaInstaller.serverRunning(FTApp.instance.prefs.aaSelfPort)) return true
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
                scope.launch { switchOn(context) }
            }
        }
        waiting = receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }.onFailure { waiting = null }
    }
}
