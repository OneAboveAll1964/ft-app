package app.ft.ui.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.ft.FTApp
import app.ft.core.DiagLog
import app.ft.supercast.PhoneScreenControl
import app.ft.supercast.SuperCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal object AaPhoneScreenState {
    val result = MutableStateFlow(PhoneScreenControl.Result.IDLE)
    val starting = MutableStateFlow(false)
    val retry = MutableStateFlow(0)
}

/** Lives on the car's virtual display, independently of the phone activity's screen state. */
@Composable
internal fun CarPhoneScreen(aaConnected: Boolean, panesVisible: Boolean) {
    val context = LocalContext.current.applicationContext
    val prefs = FTApp.instance.prefs
    val ready by SuperCast.ready.collectAsState()
    var aaOff by remember(prefs) { mutableStateOf(prefs.aaPhoneScreenOff) }
    var panesOff by remember(prefs) { mutableStateOf(prefs.supercastScreenOff) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            aaOff = prefs.aaPhoneScreenOff
            panesOff = prefs.supercastScreenOff
        }
        prefs.registerListener(listener)
        aaOff = prefs.aaPhoneScreenOff
        panesOff = prefs.supercastScreenOff
        onDispose { prefs.unregisterListener(listener) }
    }
    val aaOwns = aaConnected && aaOff
    // One effect and one queue prevent a hidden pane's cleanup from waking an active AA panel.
    val darken: Boolean? = when {
        aaOwns -> true
        panesVisible -> panesOff
        else -> null
    }
    val control = remember {
        PhoneScreenControl(object : PhoneScreenControl.Backend {
            override fun awake(on: Boolean) = SuperCast.keepAwake(on, wake = on)
            override fun manage(darken: Boolean) = SuperCast.manageScreen(darken)
            override fun restore() = SuperCast.phoneScreen(true)
        })
    }
    val retry by AaPhoneScreenState.retry.collectAsState()
    DisposableEffect(darken, ready, aaOwns, retry) {
        val cancelled = AtomicBoolean(false)
        val watch = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                SuperCast.later {
                    if (!cancelled.get()) {
                        SuperCast.keepAwake(true, wake = true)
                        SuperCast.powerPressed()
                    }
                }
            }
        }
        val watching = darken != null && ready
        if (watching) context.registerReceiver(watch, IntentFilter(Intent.ACTION_SCREEN_OFF))
        SuperCast.later {
            if (!cancelled.get()) {
                val result = control.apply(darken, ready)
                AaPhoneScreenState.result.value = if (aaOwns || result == PhoneScreenControl.Result.RESTORE_FAILED) result else PhoneScreenControl.Result.IDLE
                DiagLog.i("PhoneScreen", "screen mode: aa=$aaOwns darken=$darken ready=$ready result=$result")
            }
        }
        onDispose {
            cancelled.set(true)
            if (watching) runCatching { context.unregisterReceiver(watch) }
            SuperCast.later {
                val result = control.apply(null, SuperCast.ready.value)
                AaPhoneScreenState.result.value = result
                if (result == PhoneScreenControl.Result.RESTORE_FAILED) DiagLog.w("PhoneScreen", "could not restore the phone panel")
            }
        }
    }
    // One automatic attempt per activation. A failed attempt can be retried in AA settings.
    LaunchedEffect(aaOwns, prefs) {
        if (!aaOwns || SuperCast.isBusy) return@LaunchedEffect
        val request = SuperCast.prepareStart(prefs = prefs)
        AaPhoneScreenState.starting.value = true
        try {
            withContext(Dispatchers.IO) { request.start(context) }
        } finally {
            request.close()
            AaPhoneScreenState.starting.value = false
        }
    }
}
