package app.ft.supercast

import android.os.Bundle
import android.view.Surface
import app.ft.FTApp
import app.ft.core.DiagLog
import app.ft.core.CarConnection
import app.ft.core.Prefs
import app.ft.core.Root
import app.ft.server.IFtClient
import app.ft.server.IFtServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

object SuperCast {
    private const val TAG = "SuperCast"

    val token: String
        get() {
            val stored = FTApp.instance.prefs.supercastToken
            return if (stored.isNotEmpty()) stored else freshToken()
        }
    @Volatile private var server: IFtServer? = null
    @Volatile var helperUid: Int = -1

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val lifecycleLock = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val starting = java.util.concurrent.atomic.AtomicInteger()
    val isBusy: Boolean get() = _ready.value || starting.get() > 0

    val client: IFtClient = object : IFtClient.Stub() {
        override fun onServer(bound: IFtServer?, sent: String?) = synchronized(lifecycleLock) {
            if (sent != token) {
                DiagLog.w(TAG, "a helper offered itself with the wrong token, ignoring it")
                return
            }
            if (bound == null) {
                drop()
                return
            }
            server = bound
            _ready.value = true
            CarConnection.lifecycleChanged()
            DiagLog.i(TAG, "the SuperCast helper is connected")
        }

        override fun onEvent(event: Bundle?) {
            DiagLog.i(TAG, "helper event: ${event?.getString("event")}")
        }

        override fun onAudio(pcm: ByteArray?, sampleRate: Int, channels: Int) = Unit
    }

    fun freshToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val made = bytes.joinToString("") { "%02x".format(it) }
        FTApp.instance.prefs.supercastToken = made
        return made
    }

    fun drop() = synchronized(lifecycleLock) {
        // A disconnected helper cannot reattach after a profile becomes selectable.
        freshToken()
        generation.incrementAndGet()
        server = null
        _ready.value = false
        CarConnection.lifecycleChanged()
    }

    fun call(request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.call(request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); if (server === bound) drop() }
            .getOrNull()
    }

    private fun callWith(surface: Surface, request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.withSurface(surface, request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); if (server === bound) drop() }
            .getOrNull()
    }

    private fun ask(what: String, build: Bundle.() -> Unit = {}): Bundle? =
        call(Bundle().apply { putString("do", what); build() })

    fun ping(): Boolean = ask("ping")?.getString("pong") == "ft"

    fun openPane(name: String, surface: Surface, width: Int, height: Int, dpi: Int, packageName: String?): Int {
        val answer = callWith(surface, Bundle().apply {
            putString("do", "open")
            putString("name", name)
            putInt("width", width)
            putInt("height", height)
            putInt("dpi", dpi)
            if (!packageName.isNullOrEmpty()) putString("package", packageName)
        }) ?: return -1
        return answer.getInt("display", -1)
    }

    /** Null means the helper accepted the launch; otherwise returns a diagnostic error. */
    fun openAlicePane(name: String, surface: Surface, width: Int, height: Int, dpi: Int): String? {
        val answer = callWith(surface, Bundle().apply {
            putString("do", "assistant-open")
            putString("name", name)
            putInt("width", width)
            putInt("height", height)
            putInt("dpi", dpi)
        }) ?: return "helper"
        return if (answer.getBoolean("ok")) null else answer.getString("error", "display")
    }

    fun alicePaneStatus(name: String): String =
        ask("assistant-status") { putString("name", name) }?.getString("state") ?: "helper"

    fun closePane(name: String) {
        ask("close") { putString("name", name) }
    }

    fun detachPane(name: String) {
        ask("detach") { putString("name", name) }
    }

    fun launch(name: String, packageName: String): Boolean =
        ask("launch") { putString("name", name); putString("package", packageName) }?.getBoolean("ok") == true

    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast").apply { isDaemon = true }
    }

    fun later(block: () -> Unit) {
        val expected = generation.get()
        runCatching { queue.execute {
            if (generation.get() == expected) {
                runCatching(block).onFailure { DiagLog.w(TAG, "helper call failed: ${it.message}") }
            }
        } }
    }

    /** Release borrowed surfaces even when a helper generation has been cancelled. */
    fun surfaceLater(surface: Surface, block: (Surface) -> Unit) {
        val expected = generation.get()
        try {
            queue.execute {
                try {
                    if (generation.get() == expected) block(surface)
                } catch (e: Exception) {
                    DiagLog.w(TAG, "surface call failed: " + e.message)
                } finally {
                    surface.release()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            surface.release()
        }
    }

    private val touchThread = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast-touch").apply { isDaemon = true }
    }
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    fun touch(name: String, action: Int, x: Float, y: Float, fromWidth: Int, fromHeight: Int) {
        if (server == null) return
        val expected = generation.get()
        val moving = action == android.view.MotionEvent.ACTION_MOVE
        if (moving && waiting.get() > 2) return
        waiting.incrementAndGet()
        runCatching {
            touchThread.execute {
                try {
                    if (generation.get() != expected) return@execute
                    ask("touch") {
                        putString("name", name)
                        putInt("action", action)
                        putFloat("x", x)
                        putFloat("y", y)
                        putInt("from", fromWidth)
                        putInt("fromHeight", fromHeight)
                    }
                } finally {
                    waiting.decrementAndGet()
                }
            }
        }.onFailure { waiting.decrementAndGet() }
    }

    fun key(name: String, keyCode: Int) {
        ask("key") { putString("name", name); putInt("key", keyCode) }
    }

    fun apps(): List<Pair<String, String>> {
        val answer = ask("apps") ?: return emptyList()
        val packages = answer.getStringArrayList("packages") ?: return emptyList()
        val labels = answer.getStringArrayList("labels") ?: return emptyList()
        return packages.mapIndexed { index, pkg -> pkg to (labels.getOrNull(index) ?: pkg) }
    }

    fun phoneScreen(on: Boolean) {
        ask("screen") { putBoolean("on", on) }
    }

    fun manageScreen(darkenByItself: Boolean) {
        ask("screen") { putBoolean("on", false); putBoolean("auto", darkenByItself) }
    }

    fun powerPressed() {
        ask("power")
    }

    fun keepAwake(on: Boolean, wake: Boolean = false) {
        ask("awake") { putBoolean("on", on); putBoolean("wake", wake) }
    }

    fun stopAll() {
        ask("stop")
        drop()
    }

    /** Reserve the selected vehicle before handing work to a thread or coroutine. */
    fun prepareStart(bridge: Boolean = false, prefs: Prefs = FTApp.instance.prefs): StartRequest {
        synchronized(lifecycleLock) { starting.incrementAndGet() }
        CarConnection.lifecycleChanged()
        return StartRequest(prefs, bridge)
    }

    class StartRequest internal constructor(private val prefs: Prefs, private val bridge: Boolean) : AutoCloseable {
        private val state = java.util.concurrent.atomic.AtomicInteger()

        fun start(context: android.content.Context): Boolean {
            if (!state.compareAndSet(0, 1)) return false
            try {
                return if (bridge) startOverBridge(context) else start(context, prefs)
            } finally {
                state.set(2)
                finishStart()
                CarConnection.lifecycleChanged()
            }
        }

        override fun close() {
            // Cancellation must not release a request whose blocking worker is still running.
            if (state.compareAndSet(0, 2)) {
                finishStart()
                CarConnection.lifecycleChanged()
            }
        }
    }

    fun startAsync(context: android.content.Context, bridge: Boolean = false) {
        val request = prepareStart(bridge)
        try {
            Thread {
                runCatching { request.start(context) }
                    .onFailure { DiagLog.w(TAG, "helper setup failed: ${it.message}") }
            }.start()
        } catch (error: Throwable) {
            request.close()
            throw error
        }
    }

    fun start(context: android.content.Context): Boolean = start(context, FTApp.instance.prefs)

    private fun start(context: android.content.Context, prefs: Prefs): Boolean {
        if (_ready.value) return true
        val fresh = beginStart()
        CarConnection.lifecycleChanged()
        try {
            if (Root.ensure()) {
                if (Helper.startWithRoot(context, fresh) { _ready.value }) {
                    prefs.supercastWay = WAY_ROOT
                    DiagLog.i(TAG, "apps on the car are ready, set up with root")
                    return true
                }
                DiagLog.w(TAG, "root is there but the helper did not come back to FT")
            }
            return when (Helper.startWithAdb(context, fresh)) {
                Helper.Adb.STARTED -> {
                    val up = waitForHandshake()
                    if (up) {
                        prefs.supercastWay = WAY_BRIDGE
                        DiagLog.i(TAG, "apps on the car are ready, set up through the phone's debug bridge")
                    } else {
                        DiagLog.w(TAG, "the helper started over the debug bridge but did not come back to FT")
                    }
                    up
                }
                Helper.Adb.NOT_ALLOWED -> {
                    DiagLog.w(TAG, "the phone has not allowed FT on its debug bridge yet")
                    false
                }
                Helper.Adb.NO_BRIDGE -> {
                    DiagLog.w(TAG, "the phone's debug bridge is not switched on, so FT cannot set this up on its own")
                    false
                }
                Helper.Adb.FAILED -> false
            }
        } finally {
            finishStart()
            CarConnection.lifecycleChanged()
        }
    }

    private fun waitForHandshake(): Boolean {
        repeat(24) {
            if (_ready.value) return true
            Thread.sleep(250)
        }
        return _ready.value
    }

    fun startOverBridge(context: android.content.Context): Boolean {
        val fresh = beginStart()
        CarConnection.lifecycleChanged()
        try {
            val result = Helper.startWithAdb(context, fresh)
            DiagLog.i(TAG, "bridge start result: $result")
            return result == Helper.Adb.STARTED && waitForHandshake()
        } finally {
            finishStart()
            CarConnection.lifecycleChanged()
        }
    }

    private fun beginStart(): String = synchronized(lifecycleLock) {
        starting.incrementAndGet()
        freshToken()
    }

    private fun finishStart() = synchronized(lifecycleLock) {
        // A timed-out launch may still send a late Binder handshake.
        if (starting.get() == 1 && !_ready.value) freshToken()
        starting.decrementAndGet()
    }

    const val WAY_NONE = 0
    const val WAY_ROOT = 1
    const val WAY_BRIDGE = 2
}
