package app.ft.ui.home

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.aa.AaState
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState
import app.ft.core.DiagLog

private data class Hero(val headline: String, val detail: String, val level: Int)

private fun hero(car: CarState, aa: AaState, autoConnect: Boolean): Hero {
    val s = car.session
    return when {
        s is CarLifeSession.State.Projecting -> Hero("Projecting to the car", "${s.width}×${s.height} at ${s.fps} fps over ${s.via}", 3)
        s is CarLifeSession.State.Negotiated -> Hero("Head unit connected", "Negotiated ${s.width}×${s.height}, starting video", 3)
        s is CarLifeSession.State.Linked -> Hero("Head unit connected", "Handshake in progress", 2)
        car.carIp != null -> Hero("On the car network", "Car at ${car.carIp}, waiting for the head unit", 2)
        car.p2p.startsWith("connecting") -> Hero("Joining the car", car.p2p.replaceFirstChar { it.uppercase() }, 1)
        car.running && car.p2p == "searching" -> Hero("Searching for the car", "WiFi Direct scan in progress", 1)
        car.running -> Hero("Ready", if (car.listening) "Listening for a head unit" + (car.ip?.let { " at $it" } ?: "") else "Service running", 1)
        autoConnect -> Hero("Starting", "Auto-connect is turning on", 1)
        else -> Hero("Off", "Turn on auto-connect to find the car by itself", 0)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenLog: () -> Unit, onAllowMirror: () -> Unit, onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit) {
    val context = LocalContext.current
    val app = FTApp.instance
    val car by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    val log by DiagLog.entries.collectAsState()
    var autoConnect by remember { mutableStateOf(app.prefs.autoConnect) }
    var bluetooth by remember { mutableStateOf(app.prefs.aaBluetooth) }
    val h = hero(car, aa, autoConnect)
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (h.level) { 3 -> scheme.primaryContainer; 2 -> scheme.secondaryContainer; else -> scheme.surfaceVariant },
        label = "hero"
    )
    val dot by animateColorAsState(
        when (h.level) { 3 -> scheme.primary; 2 -> scheme.secondary; 1 -> scheme.tertiary; else -> scheme.outline },
        label = "dot"
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("FT", style = MaterialTheme.typography.headlineLarge, color = scheme.primary) }) }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = container)) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.headline, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.detail, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = autoConnect, onCheckedChange = { on ->
                            autoConnect = on
                            app.prefs.autoConnect = on
                            if (on) CarLifeService.startAuto(context) else CarLifeService.stop(context)
                        })
                    }
                }
            }

            item {
                Section("Car link", Icons.Filled.Place) {
                    InfoRow(headline = "WiFi Direct", supporting = car.p2p.replaceFirstChar { it.uppercase() })
                    InfoRow(headline = "Phone address", supporting = car.ip ?: "Not on a network yet")
                    InfoRow(headline = "Head unit ports", supporting = "${app.prefs.cmdPort} · ${app.prefs.videoPort} · ${app.prefs.touchPort}")
                    if (car.peers.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Nearby", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                        car.peers.forEach { name ->
                            ListItem(
                                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(if (name == app.prefs.carP2pName) "Remembered as your car" else "Tap Use to pick this one") },
                                trailingContent = { FilledTonalButton(onClick = { CarLifeService.pickCar(name) }) { Text("Use") } },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                    FlowRow(Modifier.padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (car.running) CarLifeService.searchAgain() else CarLifeService.startAuto(context) }) { Text("Search again") }
                        OutlinedButton(onClick = { CarLifeService.startWifi(context) }) { Text("Listen only") }
                        OutlinedButton(onClick = { CarLifeService.stop(context) }) { Text("Stop") }
                    }
                }
            }

            item {
                Section("Android Auto head unit", Icons.Filled.PlayArrow) {
                    InfoRow(
                        headline = when {
                            aa.connected && aa.phase == AaSession.Phase.STREAMING -> "Streaming from ${aa.deviceName.ifBlank { "the phone" }}"
                            aa.connected -> "Connecting to ${aa.deviceName.ifBlank { "the phone" }}"
                            aa.listening -> "Waiting for a phone"
                            else -> "Off"
                        },
                        supporting = when {
                            aa.listening -> "TCP ${aa.port}" + (aa.hotspot?.let { " · hotspot ${it.ssid}" } ?: "") + (if (aa.bluetooth != "idle") " · Bluetooth ${aa.bluetooth}" else "")
                            else -> "Starts by itself when you tap Android Auto on the car"
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Bluetooth handoff") },
                        supportingContent = { Text("Lets a second phone find this head unit") },
                        trailingContent = { Switch(checked = bluetooth, onCheckedChange = { bluetooth = it; app.prefs.aaBluetooth = it }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    FlowRow(Modifier.padding(start = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { AaHeadUnitService.start(context, bluetooth) }) { Text("Start now") }
                        OutlinedButton(onClick = { AaHeadUnitService.stop(context) }) { Text("Stop") }
                    }
                }
            }

            item {
                Section("Phone apps on the car", Icons.Filled.Share) {
                    Permission("Screen mirror", "Shows the app you open on the car display", app.mirrorData != null, onAllowMirror)
                    Permission("Touch control", "The car touchscreen drives the mirrored app", FTTouchService.enabled, onOpenAccessibility)
                    Permission("Display over apps", "Lets tiles open apps while FT is in the background", Settings.canDrawOverlays(context), onOpenOverlay)
                }
            }

            item {
                Section("Activity", Icons.Filled.Build) {
                    Column(Modifier.fillMaxWidth().height(126.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        log.takeLast(5).forEach { e ->
                            Text("${e.tag}  ${e.text}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onOpenLog, modifier = Modifier.padding(start = 8.dp)) { Text("Open the full log") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(headline: String, supporting: String) {
    ListItem(
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun Permission(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(if (granted) Icons.Filled.CheckCircle else Icons.Filled.Lock, contentDescription = null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { if (!granted) FilledTonalButton(onClick = onGrant) { Text("Allow") } else Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
