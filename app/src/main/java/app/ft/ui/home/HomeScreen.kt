package app.ft.ui.home

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaInstaller
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState
import app.ft.core.DiagLog

private data class Hero(val headline: String, val detail: String, val level: Int)

private fun hero(car: CarState, autoConnect: Boolean, direct: Boolean): Hero {
    val s = car.session
    return when {
        s is CarLifeSession.State.Projecting -> Hero("Projecting to the car", "${s.width}×${s.height} at ${s.fps} fps over ${s.via}", 3)
        s is CarLifeSession.State.Negotiated -> Hero("Head unit connected", "Negotiated ${s.width}×${s.height}, starting video", 3)
        s is CarLifeSession.State.Linked -> Hero("Head unit connected", "Handshake in progress", 2)
        car.carIp != null -> Hero("On the car network", "Car at ${car.carIp}, waiting for the head unit", 2)
        car.p2p.startsWith("connecting") -> Hero("Joining the car", car.p2p.replaceFirstChar { it.uppercase() }, 1)
        car.running && direct && car.p2p == "searching" -> Hero("Looking for the car", "Over WiFi Direct and bluetooth", 1)
        car.running && direct -> Hero("Waiting for the car", "Over WiFi Direct and bluetooth", 1)
        car.running -> Hero("Waiting for the car", car.ip?.let { "Ready on $it, connect the car to this phone" } ?: "Turn on the hotspot so the car can join", 1)
        autoConnect -> Hero("Starting", "FT is turning on", 1)
        else -> Hero("Off", "Turn this on to let FT connect to the car", 0)
    }
}

@Composable
fun HomeScreen(pad: PaddingValues, onOpenLog: () -> Unit, onAllowMirror: () -> Unit, onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit, onTakeOverAa: () -> Unit) {
    val context = LocalContext.current
    val app = FTApp.instance
    val car by CarLifeService.state.collectAsState()
    val log by DiagLog.entries.collectAsState()
    val mirrorGranted by app.mirrorGranted.collectAsState()
    var autoConnect by remember { mutableStateOf(app.prefs.autoConnect) }
    LaunchedEffect(car.running) { if (car.running) autoConnect = true }
    var aaAuto by remember { mutableStateOf(app.prefs.aaAutoStart) }
    var linkMode by remember { mutableIntStateOf(app.prefs.linkMode) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val touchOn = remember(resumed) { FTTouchService.enabled }
    val aaStep = remember(resumed) { AaInstaller.step(context) }
    val aaSays = remember(resumed) { AaInstaller.explain(context) }
    val aaHasCopy = remember(resumed) { AaInstaller.stashed(context).isNotEmpty() }
    val overlayOn = remember(resumed) { Settings.canDrawOverlays(context) }
    val h = hero(car, autoConnect, linkMode == 1)
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (h.level) { 3 -> scheme.primaryContainer; 2 -> scheme.secondaryContainer; 1 -> scheme.tertiaryContainer; else -> scheme.surfaceContainerHigh },
        label = "hero"
    )
    val onContainer by animateColorAsState(
        when (h.level) { 3 -> scheme.onPrimaryContainer; 2 -> scheme.onSecondaryContainer; 1 -> scheme.onTertiaryContainer; else -> scheme.onSurface },
        label = "heroText"
    )
    val dot by animateColorAsState(
        when (h.level) { 3 -> scheme.primary; 2 -> scheme.secondary; 1 -> scheme.tertiary; else -> scheme.outline },
        label = "dot"
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = car.running || autoConnect, onCheckedChange = { on ->
                        autoConnect = on
                        app.prefs.autoConnect = on
                        if (on) CarLifeService.startAuto(context) else CarLifeService.stop(context)
                    })
                }
            }
        }

        item {
            Section("Car link", Icons.Filled.Place) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Hotspot", 1 to "WiFi + BL").forEach { (value, label) ->
                        if (linkMode == value) {
                            FilledTonalButton(onClick = {}, modifier = Modifier.weight(1f)) {
                                Text(label, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    linkMode = value
                                    app.prefs.linkMode = value
                                    CarLifeService.startAuto(context)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Text(
                    if (linkMode == 1) "FT asks the car over bluetooth to raise its own WiFi Direct group, then joins it."
                    else "The car joins the network this phone is sharing, and reaches FT on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                val missing = remember(resumed, linkMode, car.ip) { whatIsMissing(context, linkMode == 1, car.ip) }
                missing.forEach { need ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(need.title, style = MaterialTheme.typography.bodyLarge, color = scheme.error)
                            Text(need.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { runCatching { context.startActivity(need.fix) } }) { Text("Turn on") }
                    }
                }
                if (missing.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (car.step.isNotBlank()) InfoRow("Now", car.step)
                InfoRow("Phone address", (car.ip ?: "Not on a network yet") + (if (car.beacon) " · calling the head unit" else ""))
                if (linkMode == 1) {
                    InfoRow("WiFi Direct", car.p2p.replaceFirstChar { it.uppercase() })
                    InfoRow("Bluetooth", "FT needs bluetooth on and the car paired")
                    if (car.peers.isNotEmpty()) {
                        Text("Nearby", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        car.peers.forEach { name ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (name == app.prefs.carP2pName) "Remembered as your car" else "Tap Use to pick this one",
                                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                FilledTonalButton(onClick = { CarLifeService.pickCar(name) }) { Text("Use") }
                            }
                        }
                    }
                } else {
                    InfoRow("Head unit ports", "${app.prefs.cmdPort} · ${app.prefs.videoPort} · ${app.prefs.touchPort}")
                }
                Actions {
                    FilledTonalButton(onClick = { if (car.running) CarLifeService.searchAgain() else CarLifeService.startAuto(context) }, modifier = Modifier.weight(1f)) { Text("Search", maxLines = 1) }
                    OutlinedButton(onClick = { CarLifeService.startWifi(context) }, modifier = Modifier.weight(1f)) { Text("Listen", maxLines = 1) }
                    OutlinedButton(onClick = { CarLifeService.forgetCar() }, modifier = Modifier.weight(1f)) { Text("Forget", maxLines = 1) }
                }
            }
        }

        item {
            Section("Android Auto", Icons.Filled.PlayArrow) {
                InfoRow(
                    if (car.aaOverlay) "Showing on the car" else "Your phone's Android Auto, on the car",
                    "FT pretends to be a car, so Android Auto projects onto it and FT passes it to your car"
                )
                InfoRow(aaSays.first, aaSays.second)
                SwitchRow("Start automatically", "Opens Android Auto as soon as the car is projecting", aaAuto) {
                    aaAuto = it
                    app.prefs.aaAutoStart = it
                }
                Actions {
                    FilledTonalButton(
                        onClick = { if (aaStep == AaInstaller.Step.DONE) CarLifeService.startAa() else onTakeOverAa() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (aaStep) {
                                AaInstaller.Step.DONE -> "Start Android Auto"
                                AaInstaller.Step.REMOVE_UPDATES -> "Remove the Android Auto update"
                                AaInstaller.Step.UNINSTALL -> "Remove Android Auto"
                                AaInstaller.Step.INSTALL -> if (aaHasCopy) "Put Android Auto back" else "Install Android Auto"
                            },
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            Section("Phone apps on the car", Icons.Filled.Share) {
                Permission("Screen mirror", "Shows the app you open on the car display", mirrorGranted, onAllowMirror)
                Permission("Touch control", "The car touchscreen drives the mirrored app", touchOn, onOpenAccessibility)
                Permission("Display over apps", "Lets tiles open apps while FT is in the background", overlayOn, onOpenOverlay)
            }
        }

        item {
            Section("Activity", Icons.Filled.Build) {
                Column(Modifier.fillMaxWidth().height(96.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    log.takeLast(5).forEach { e ->
                        Text("${e.tag}  ${e.text}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onOpenLog, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Open the full log") }
            }
        }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(headline: String, supporting: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Permission(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Lock,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        else FilledTonalButton(onClick = onGrant) { Text("Allow") }
    }
}

@Composable
private fun Actions(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private data class Need(val title: String, val detail: String, val fix: android.content.Intent)

private fun whatIsMissing(context: android.content.Context, direct: Boolean, ip: String?): List<Need> {
    val needs = ArrayList<Need>()
    if (direct) {
        val bt = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (bt != null && !bt.isEnabled) {
            needs += Need(
                "Bluetooth is off",
                "The car calls this phone over bluetooth to start WiFi Direct",
                android.content.Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )
        }
        val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            needs += Need(
                "WiFi is off",
                "WiFi Direct needs WiFi switched on",
                android.content.Intent(Settings.ACTION_WIFI_SETTINGS)
            )
        }
    } else if (ip == null) {
        val tether = android.content.Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        val fix = if (context.packageManager.resolveActivity(tether, 0) != null) tether
        else android.content.Intent(Settings.ACTION_WIRELESS_SETTINGS)
        needs += Need("The hotspot is off", "The car joins the network this phone shares", fix)
    }
    return needs
}
