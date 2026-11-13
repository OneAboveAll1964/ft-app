package app.ft.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.R
import app.ft.core.BluetoothCalls
import app.ft.core.BluetoothCallsState
import app.ft.core.BluetoothCallsStatus
import app.ft.ui.home.Fixes

@Composable
fun BluetoothCallsSetting(prominent: Boolean = false, projectionConnected: Boolean = false) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BluetoothCallsState()) }
    var monitor by remember { mutableStateOf<BluetoothCalls?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Fixes.open(context, Fixes.appSettings(context))
        monitor?.refresh()
    }
    LifecycleResumeEffect(context) {
        val current = BluetoothCalls(context)
        monitor = current
        current.start()
        state = current.state.value
        onPauseOrDispose {
            current.close()
            if (monitor === current) monitor = null
        }
    }
    LaunchedEffect(monitor) {
        monitor?.state?.collect { state = it }
    }
    val disconnected = state.status == BluetoothCallsStatus.DISCONNECTED || state.status == BluetoothCallsStatus.OFF
    val attention = disconnected || state.status == BluetoothCallsStatus.PERMISSION_REQUIRED || state.status == BluetoothCallsStatus.UNAVAILABLE
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attention) Icon(Icons.Default.Warning, contentDescription = null)
                Text(stringResource(when {
                    disconnected -> R.string.bluetooth_calls_not_connected
                    attention -> R.string.bluetooth_calls_not_checked
                    else -> R.string.bluetooth_calls_title
                }), style = MaterialTheme.typography.titleMedium)
            }
            Text(when (state.status) {
                BluetoothCallsStatus.CHECKING -> stringResource(R.string.bluetooth_calls_checking)
                BluetoothCallsStatus.CONNECTED -> stringResource(R.string.bluetooth_calls_connected, state.devices.joinToString())
                BluetoothCallsStatus.DISCONNECTED -> stringResource(R.string.bluetooth_calls_disconnected)
                BluetoothCallsStatus.OFF -> stringResource(R.string.bluetooth_calls_off)
                BluetoothCallsStatus.PERMISSION_REQUIRED -> stringResource(R.string.bluetooth_calls_permission)
                BluetoothCallsStatus.UNAVAILABLE -> stringResource(R.string.bluetooth_calls_unavailable)
            })
            Text(stringResource(when {
                disconnected && projectionConnected -> R.string.bluetooth_calls_projection_only
                disconnected -> R.string.bluetooth_calls_connect_help
                else -> R.string.bluetooth_calls_about
            }), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = {
                if (state.status == BluetoothCallsStatus.PERMISSION_REQUIRED && Build.VERSION.SDK_INT >= 31) {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else Fixes.open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }) {
                Text(stringResource(if (state.status == BluetoothCallsStatus.PERMISSION_REQUIRED) R.string.bluetooth_calls_allow else R.string.bluetooth_calls_settings))
            }
        }
    }
    if (prominent) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
        ) { content(Modifier.padding(16.dp)) }
    } else content(Modifier)
}
