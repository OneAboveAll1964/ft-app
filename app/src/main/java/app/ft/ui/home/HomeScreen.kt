package app.ft.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.core.DiagLog
import app.ft.core.Transport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onAllowMirror: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onConnectUsb: () -> Unit
) {
    val context = LocalContext.current
    val app = FTApp.instance
    val car by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    val log by DiagLog.entries.collectAsState()
    var transport by remember { mutableStateOf(app.prefs.transport) }
    var bluetooth by remember { mutableStateOf(app.prefs.aaBluetooth) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FT", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary) },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onDiagnostics) { Icon(Icons.Filled.Info, contentDescription = "Diagnostics") }
                    androidx.compose.material3.IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Car projection", style = MaterialTheme.typography.titleLarge)
                    val s = car.session
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (s is CarLifeSession.State.Projecting) LoadingIndicator()
                        Text(
                            when (s) {
                                is CarLifeSession.State.Projecting -> "Projecting ${s.width}×${s.height} @ ${s.fps} via ${s.via}"
                                is CarLifeSession.State.Negotiated -> "Negotiated ${s.width}×${s.height} via ${s.via}"
                                is CarLifeSession.State.Linked -> "Linked via ${s.via}, waiting for the head unit"
                                else -> if (car.listening.isNotEmpty()) "Listening: ${car.listening.joinToString()}" else "Not connected"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    car.ip?.let { Text("Phone IP $it · ports ${app.prefs.cmdPort}/${app.prefs.videoPort}/${app.prefs.touchPort}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Transport.entries.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = transport == t,
                                onClick = { transport = t; app.prefs.transport = t },
                                shape = SegmentedButtonDefaults.itemShape(i, Transport.entries.size)
                            ) { Text(t.name) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (transport != Transport.WIFI) FilledTonalButton(onClick = onConnectUsb) { Text("Connect USB") }
                        if (transport != Transport.USB) FilledTonalButton(onClick = { CarLifeService.startWifi(context) }) { Text("Listen on WiFi") }
                        OutlinedButton(onClick = { CarLifeService.stop(context) }) { Text("Stop") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Android Auto head unit", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            aa.connected -> "${aa.deviceName.ifBlank { "Phone" }} · ${aa.phase?.name?.lowercase() ?: "connecting"}"
                            aa.listening -> "Waiting for a phone on tcp:${aa.port}" + (aa.hotspot?.let { " · hotspot ${it.ssid}" } ?: "")
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = bluetooth, onCheckedChange = { bluetooth = it; app.prefs.aaBluetooth = it })
                        Text("Advertise over Bluetooth for a second phone")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { AaHeadUnitService.start(context, bluetooth) }, shapes = ButtonDefaults.shapes()) { Text("Start head unit") }
                        OutlinedButton(onClick = { AaHeadUnitService.stop(context) }) { Text("Stop") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Phone apps on the car screen", style = MaterialTheme.typography.titleLarge)
                    Text("Tiles on the car launcher open an app here and mirror it to the car. Two one-time permissions make that interactive.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = onAllowMirror, label = { Text(if (app.mirrorData != null) "Mirror allowed" else "Allow mirror") })
                        AssistChip(onClick = onOpenAccessibility, label = { Text(if (FTTouchService.enabled) "Touch enabled" else "Enable touch") })
                        AssistChip(onClick = onOpenOverlay, label = { Text("Display over apps") })
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Live", style = MaterialTheme.typography.titleMedium)
                    }
                    log.takeLast(7).forEach { e ->
                        Text("${e.tag}  ${e.text}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
