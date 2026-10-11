package app.ft.supercast

import android.os.Bundle
import app.ft.FTApp
import app.ft.core.DiagLog
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
}
