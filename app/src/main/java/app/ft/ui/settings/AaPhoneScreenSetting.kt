package app.ft.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.aa.AaHeadUnitService
import app.ft.core.Prefs
import app.ft.supercast.PhoneScreenControl
import app.ft.supercast.SuperCast
import app.ft.ui.car.AaPhoneScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AaPhoneScreenSetting(prefs: Prefs) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember(prefs) { mutableStateOf(prefs.aaPhoneScreenOff) }
    val ready by SuperCast.ready.collectAsState()
    val automaticStart by AaPhoneScreenState.starting.collectAsState()
    val result by AaPhoneScreenState.result.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BoolSetting(stringResource(R.string.aa_phone_screen_off), enabled,
        stringResource(R.string.aa_phone_screen_off_body)) {
        enabled = it
        prefs.aaPhoneScreenOff = it
    }
    if (!enabled) return
    val error = (failed && !ready) || result == PhoneScreenControl.Result.REFUSED || result == PhoneScreenControl.Result.RESTORE_FAILED
    Text(stringResource(when {
        busy || automaticStart -> R.string.apps_setting_up
        error -> R.string.aa_phone_screen_failed
        !ready -> R.string.aa_phone_screen_helper
        aa.connected && result == PhoneScreenControl.Result.ACTIVE -> R.string.aa_phone_screen_active
        else -> R.string.aa_phone_screen_waiting
    }), style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (!busy && !automaticStart && (!ready || error)) {
        TextButton(onClick = {
            failed = false
            if (ready) {
                AaPhoneScreenState.retry.value++
            } else {
                val request = SuperCast.prepareStart(prefs = prefs)
                try {
                    scope.launch {
                        busy = true
                        try {
                            failed = !withContext(Dispatchers.IO) { request.start(context) }
                        } finally {
                            busy = false
                            request.close()
                        }
                    }.invokeOnCompletion { request.close() }
                } catch (error: Throwable) {
                    request.close()
                    throw error
                }
            }
        }) { Text(stringResource(R.string.apps_setup_now)) }
    }
    if (!ready) {
        Text(stringResource(R.string.aa_phone_screen_adb), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SuperCastComputerCommand()
    }
}
