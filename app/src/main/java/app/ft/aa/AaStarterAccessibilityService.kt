package app.ft.aa

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.ft.MainActivity
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Independent, user-enabled helper. No gestures, global actions or window enumeration. */
class AaStarterAccessibilityService : AccessibilityService() {
    companion object {
        internal const val TIMEOUT_MS = 8_000L
        @Volatile internal var instance: AaStarterAccessibilityService? = null
            private set
        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected
    }

    private class Request(
        val token: Any,
        val attempt: AaStarterAttempt,
        val completed: (AaSwitch.Result) -> Unit
    ) {
        var job: Job? = null
        var finishing = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Request? = null
    private var overflowLabels = emptySet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mutableConnected.value = true
        overflowLabels = overflowLabels()
        DiagLog.i("AA", "separate Android Auto starter connected")
    }

    /** Called on main, before opening AA settings. An overlapping request is rejected. */
    internal fun armForLaunch(token: Any, port: Int, completed: (AaSwitch.Result) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (request != null) return false
        val current = Request(token, AaStarterAttempt(SystemClock.elapsedRealtime() + TIMEOUT_MS), completed)
        request = current
        handler.postDelayed({
            if (request === current) finish(current, AaSwitch.Result.FAILED)
        }, current, TIMEOUT_MS)
        current.job = scope.launch {
            // Polling also detects a foreground change: events from other packages are filtered out.
            while (request === current && !current.finishing) {
                val on = withContext(Dispatchers.IO) { AaInstaller.serverRunning(port) }
                inspect(current, on)
                delay(150)
            }
        }
        return true
    }

    internal fun cancel(token: Any) {
        request?.takeIf { it.token === token }?.let { finish(it, AaSwitch.Result.FAILED, returnToFt = false, immediately = true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = request ?: return
        if (current.finishing || event?.packageName?.toString() != AaInstaller.GEARHEAD) return
        inspect(current, serverOn = false)
    }

    private fun inspect(current: Request, serverOn: Boolean) {
        if (request !== current || current.finishing) return
        try {
            if (current.attempt.expired(SystemClock.elapsedRealtime())) {
                finish(current, AaSwitch.Result.FAILED)
                return
            }
            val locked = AaSwitch.locked(this)
            if (locked) {
                finish(current, AaSwitch.Result.LOCKED, returnToFt = false)
                return
            }
            val root = rootInActiveWindow
            val aaWindow = root?.packageName?.toString()?.let { it == AaInstaller.GEARHEAD }
            val nodes = if (aaWindow == true) AaStarterNodes.menu(root, overflowLabels) else AaStarterNodes.Menu()
            when (current.attempt.next(SystemClock.elapsedRealtime(), false, aaWindow, serverOn,
                nodes.start != null, nodes.stop != null, nodes.overflow != null)) {
                AaStarterAttempt.Action.ON -> finish(current, AaSwitch.Result.ON)
                AaStarterAttempt.Action.LOCKED -> finish(current, AaSwitch.Result.LOCKED, returnToFt = false)
                AaStarterAttempt.Action.FAILED -> finish(current, AaSwitch.Result.FAILED)
                AaStarterAttempt.Action.OPEN_MENU -> click(current, nodes.overflow!!, root!!.windowId)
                AaStarterAttempt.Action.START -> click(current, nodes.start!!, root!!.windowId)
                AaStarterAttempt.Action.WAIT -> Unit
            }
        } catch (e: Exception) {
            DiagLog.w("AA", "Android Auto starter failed (${e.javaClass.simpleName})")
            finish(current, AaSwitch.Result.FAILED)
        }
    }

    private fun click(current: Request, node: AccessibilityNodeInfo, windowId: Int) {
        // Recheck focus immediately before ACTION_CLICK; never fall back to screen coordinates.
        val active = rootInActiveWindow
        if (current.attempt.expired(SystemClock.elapsedRealtime()) || AaSwitch.locked(this) || active?.packageName?.toString() != AaInstaller.GEARHEAD ||
            active.windowId != windowId || !AaStarterNodes.click(node, windowId)) {
            finish(current, AaSwitch.Result.FAILED)
        }
    }

    private fun finish(current: Request, result: AaSwitch.Result, returnToFt: Boolean = true, immediately: Boolean = false) {
        if (request !== current || (current.finishing && !immediately)) return
        current.finishing = true
        current.job?.cancel()
        handler.removeCallbacksAndMessages(current)
        val complete = Runnable {
            if (request !== current) return@Runnable
            // Do not take focus back from an app the user has switched to.
            if (returnToFt && current.attempt.observedAa && !AaSwitch.locked(this)) {
                runCatching {
                    if (rootInActiveWindow?.packageName?.toString() == AaInstaller.GEARHEAD) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
            }
            request = null
            DiagLog.i("AA", "Android Auto starter completed: $result")
            current.completed(result)
        }
        if (immediately) complete.run() else handler.postDelayed(complete, current, 200)
    }

    override fun onInterrupt() {
        request?.let { finish(it, AaSwitch.Result.FAILED, returnToFt = false, immediately = true) }
    }

    private fun disconnected() {
        request?.let { finish(it, AaSwitch.Result.NO_HELPER, returnToFt = false, immediately = true) }
        if (instance === this) {
            instance = null
            mutableConnected.value = false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        scope.cancel()
        super.onDestroy()
    }

    private fun overflowLabels(): Set<String> = buildSet {
        addAll(listOf("More options", "Ещё", "Еще"))
        runCatching {
            val res = packageManager.getResourcesForApplication(AaInstaller.GEARHEAD)
            for (name in listOf("abc_action_menu_overflow_description", "m3c_top_app_bar_overflow_description", "more_options")) {
                val id = res.getIdentifier(name, "string", AaInstaller.GEARHEAD)
                if (id != 0) add(res.getString(id))
            }
        }
        runCatching {
            val res = Resources.getSystem()
            val id = res.getIdentifier("action_menu_overflow_description", "string", "android")
            if (id != 0) add(res.getString(id))
        }
    }
}

internal object AaStarterNodes {
    data class Menu(val start: AccessibilityNodeInfo? = null, val stop: AccessibilityNodeInfo? = null, val overflow: AccessibilityNodeInfo? = null)

    private fun allowed(node: AccessibilityNodeInfo, windowId: Int) =
        node.packageName?.toString() == AaInstaller.GEARHEAD && node.windowId == windowId && node.isVisibleToUser && node.isEnabled

    fun menu(root: AccessibilityNodeInfo, overflowLabels: Set<String>): Menu {
        if (root.packageName?.toString() != AaInstaller.GEARHEAD) return Menu()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var start: AccessibilityNodeInfo? = null
        var stop: AccessibilityNodeInfo? = null
        var overflow: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 512) {
            val node = queue.removeFirst()
            if (!allowed(node, root.windowId)) continue
            when (node.text?.toString()) {
                "Start head unit server", "Запустить сервер головного устройства" -> start = node
                "Stop head unit server", "Остановить сервер головного устройства" -> stop = node
            }
            if (node.contentDescription?.toString() in overflowLabels ||
                node.className?.toString()?.endsWith("OverflowMenuButton") == true) overflow = node
            for (i in 0 until node.childCount.coerceAtMost(64)) {
                if (queue.size >= 512) break
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return Menu(start, stop, overflow)
    }

    fun click(node: AccessibilityNodeInfo, windowId: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (!allowed(candidate, windowId)) return false
            if (candidate.isClickable) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = candidate.parent
        }
        return false
    }
}
