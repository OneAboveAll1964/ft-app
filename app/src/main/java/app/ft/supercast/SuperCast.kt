package app.ft.supercast

import android.os.Bundle
import android.view.Surface
import app.ft.FTApp
import app.ft.core.DiagLog
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

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val client: IFtClient = object : IFtClient.Stub() {
        override fun onServer(bound: IFtServer?, sent: String?) {
            if (sent != token) {
                DiagLog.w(TAG, "a helper offered itself with the wrong token, ignoring it")
                return
            }
            server = bound
            _ready.value = bound != null
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

    fun drop() {
        server = null
        _ready.value = false
    }

    fun call(request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.call(request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
            .getOrNull()
    }

    private fun callWith(surface: Surface, request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.withSurface(surface, request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
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

    fun closePane(name: String) {
        ask("close") { putString("name", name) }
    }

    fun launch(name: String, packageName: String): Boolean =
        ask("launch") { putString("name", name); putString("package", packageName) }?.getBoolean("ok") == true

    fun swap(first: String, second: String) {
        ask("swap") { putString("first", first); putString("second", second) }
    }

    fun touch(name: String, action: Int, x: Float, y: Float, fromWidth: Int, fromHeight: Int) {
        ask("touch") {
            putString("name", name)
            putInt("action", action)
            putFloat("x", x)
            putFloat("y", y)
            putInt("from", fromWidth)
            putInt("fromHeight", fromHeight)
        }
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

    fun stopAll() {
        ask("stop")
        drop()
    }

    fun start(context: android.content.Context): Boolean {
        if (_ready.value) return true
        val fresh = freshToken()
        if (Root.ensure() && Helper.startWithRoot(context, fresh)) {
            FTApp.instance.prefs.supercastWay = WAY_ROOT
            return true
        }
        return when (Helper.startWithAdb(context, fresh)) {
            Helper.Adb.STARTED -> {
                FTApp.instance.prefs.supercastWay = WAY_BRIDGE
                true
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
    }

    fun startOverBridge(context: android.content.Context): Boolean {
        val fresh = freshToken()
        val result = Helper.startWithAdb(context, fresh)
        DiagLog.i(TAG, "bridge start result: $result")
        return result == Helper.Adb.STARTED
    }

    const val WAY_NONE = 0
    const val WAY_ROOT = 1
    const val WAY_BRIDGE = 2
}
