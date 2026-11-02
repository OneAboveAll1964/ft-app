package app.ft.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.R
import app.ft.core.CarConnection
import app.ft.iccoa.IccoaPhase
import app.ft.iccoa.IccoaService
import app.ft.iccoa.IccoaState
import app.ft.ui.components.ReportButton

internal class IccoaConnectionActions(val denied: Boolean, val start: (scanAgain: Boolean) -> Unit)

@Composable
internal fun rememberIccoaConnectionActions(): IccoaConnectionActions {
    val context = LocalContext.current
    val prefs = FTApp.instance.prefs
    val state by IccoaService.state.collectAsState()
    var denied by remember { mutableStateOf(false) }
    var rescan by remember { mutableStateOf(false) }
    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Coarse is requested alongside Fine for Android 12's location permission dialog.
        denied = permissions.any { it != Manifest.permission.ACCESS_COARSE_LOCATION && result[it] != true }
        if (!denied) {
            if (rescan && state.running) {
                prefs.autoConnect = true
                IccoaService.scanAgain(context)
            } else CarConnection.switchOn(context)
        }
    }
    fun start(scanAgain: Boolean) {
        rescan = scanAgain
        if (permissions.any { it != Manifest.permission.ACCESS_COARSE_LOCATION && context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) permissionRequest.launch(permissions)
        else {
            denied = false
            if (scanAgain && state.running) {
                prefs.autoConnect = true
                IccoaService.scanAgain(context)
            } else CarConnection.switchOn(context)
        }
    }
    return IccoaConnectionActions(denied, ::start)
}

@Composable
internal fun IccoaConnectionControls(state: IccoaState, actions: IccoaConnectionActions) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(state.pinRequired) { pin = "" }
    val scan = state.discovery
    val scanning = state.running && scan.phase in listOf(IccoaPhase.SCANNING, IccoaPhase.INSPECTING)
    val busy = scanning || state.connecting
    val phase = if (!state.running && scan.phase != IccoaPhase.PERMISSION_REQUIRED) R.string.state_off else when (scan.phase) {
        IccoaPhase.IDLE -> R.string.iccoa_ready
        IccoaPhase.SCANNING -> R.string.iccoa_scanning
        IccoaPhase.SCAN_COMPLETE -> R.string.iccoa_scan_complete
        IccoaPhase.INSPECTING -> R.string.iccoa_inspecting
        IccoaPhase.GATT_COMPLETE -> R.string.iccoa_inspect_complete
        IccoaPhase.BLUETOOTH_OFF -> R.string.iccoa_bluetooth_off
        IccoaPhase.PERMISSION_REQUIRED -> R.string.iccoa_permission
        IccoaPhase.LOCATION_OFF -> R.string.iccoa_location_off
        IccoaPhase.FAILED -> R.string.iccoa_failed
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.iccoa_experimental_body))
        Text(state.status.ifBlank { stringResource(phase) }, style = MaterialTheme.typography.titleMedium)
        if (busy && !state.pinRequired) CircularProgressIndicator()
        if (state.pinRequired) {
            Text(stringResource(R.string.iccoa_pin_help))
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = value.filter { it in '0'..'9' }.take(6) },
                label = { Text(stringResource(R.string.iccoa_pin)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(onClick = {
                IccoaService.submitPin(context, pin)
                pin = ""
            }, enabled = pin.length == 6) { Text(stringResource(R.string.iccoa_submit_pin)) }
        }
        if (state.connected) Text(stringResource(R.string.iccoa_connected_help))
        if (actions.denied || scan.phase == IccoaPhase.PERMISSION_REQUIRED) {
            Text(stringResource(R.string.iccoa_permission))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                Text(stringResource(R.string.iccoa_open_permissions))
            }
        }
        if (state.connected || state.connecting || state.pinRequired) {
            TextButton(onClick = { IccoaService.disconnect(context) }) {
                Text(stringResource(if (state.connected) R.string.iccoa_disconnect else R.string.iccoa_cancel))
            }
        } else {
            FilledTonalButton(onClick = { actions.start(true) }, enabled = !busy) { Text(stringResource(R.string.iccoa_scan_again)) }
        }
        if (!state.connected && !state.connecting) Text(stringResource(R.string.iccoa_device_count, scan.peers.size))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.iccoa_report), Modifier.weight(1f))
            ReportButton()
        }
    }
}

internal fun LazyListScope.iccoaDevices(state: IccoaState) {
    val scan = state.discovery
    val busy = scan.phase in listOf(IccoaPhase.SCANNING, IccoaPhase.INSPECTING)
    if (!state.connected && !state.connecting && !state.pinRequired) {
        item { Text(stringResource(R.string.iccoa_device_help), style = MaterialTheme.typography.bodySmall) }
        items(scan.peers, key = { it.address }) { peer ->
            val context = LocalContext.current
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(peer.name.ifBlank { stringResource(R.string.iccoa_unnamed) }, style = MaterialTheme.typography.titleMedium)
                    Text("${peer.address} · ${peer.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    if (peer.hint) Text(stringResource(R.string.iccoa_hint), style = MaterialTheme.typography.bodySmall)
                    if (scan.inspected == peer.address && scan.phase == IccoaPhase.GATT_COMPLETE) Text(stringResource(R.string.iccoa_inspect_complete))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { IccoaService.connect(context, peer.address) }, enabled = state.running && scan.phase != IccoaPhase.INSPECTING && peer.connectable) {
                            Text(stringResource(R.string.iccoa_connect))
                        }
                        TextButton(onClick = { IccoaService.inspect(context, peer.address) }, enabled = state.running && !busy && peer.connectable) {
                            Text(stringResource(R.string.iccoa_inspect))
                        }
                    }
                }
            }
        }
    }
}
