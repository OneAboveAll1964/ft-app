package app.ft.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import app.ft.R

/** Launches the phone's assistant without changing AA video focus or ending its session. */
class CarVoiceAssistant(
    private val context: Context,
    prefs: () -> Prefs,
    aaVisible: () -> Boolean,
    private val showAlice: (() -> Unit)? = null,
    sendAa: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val button = VoiceButton(
        selected = { prefs().voiceAssistant },
        aaVisible = aaVisible,
        sendAa = {
            DiagLog.i("VoiceButton", "routing voice key to Android Auto")
            sendAa()
        },
        openPhone = { assistant -> main.post { openPhone(assistant) } },
        now = SystemClock::elapsedRealtime
    )

    fun press() = button.press()

    private fun openPhone(assistant: VoiceAssistant) {
        if (assistant == VoiceAssistant.ALICE) {
            if (showAlice != null) {
                showAlice.invoke()
                return
            }
            // Explicit packages leave the user's default assistant unchanged. Do not open
            // Yandex's ordinary launcher: that does not necessarily start listening.
            for (pkg in listOf("com.yandex.searchapp", "ru.yandex.searchplugin")) {
                if (launch(Intent(Intent.ACTION_ASSIST).setPackage(pkg))) return
            }
            DiagLog.w("VoiceButton", "Alice could not be opened; no fallback to Google")
            Toast.makeText(context, R.string.voice_alice_unavailable, Toast.LENGTH_LONG).show()
        } else if (!launch(Intent(Intent.ACTION_VOICE_COMMAND))) {
            Toast.makeText(context, R.string.voice_default_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DiagLog.i("VoiceButton", "requested ${intent.action}, package=${intent.`package` ?: "system"}; phone microphone, AA session unchanged")
            true
        } catch (e: ActivityNotFoundException) {
            DiagLog.w("VoiceButton", "no assistant activity for ${intent.`package` ?: "system"}")
            false
        } catch (e: SecurityException) {
            DiagLog.w("VoiceButton", "assistant launch denied for ${intent.`package` ?: "system"}: ${e.message}")
            false
        }
    }
}
