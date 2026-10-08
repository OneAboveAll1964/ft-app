package app.ft.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaInstaller
import app.ft.ui.components.Stepper
import app.ft.ui.components.StepState
import app.ft.ui.components.StepMark
import app.ft.ui.components.AaStartChoice
import app.ft.ui.components.rememberRootGranted
import app.ft.core.RootPrep
import app.ft.ui.components.rememberAaServerOn
import app.ft.ui.components.StepItem
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState

private data class Status(val title: String, val detail: String, val level: Int)

@Composable
fun HomeScreen(pad: PaddingValues, onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit, onOpenAaSetup: () -> Unit) {
    val context = LocalContext.current
    val app = FTApp.instance
    val car by CarLifeService.state.collectAsState()
    var autoConnect by remember { mutableStateOf(app.prefs.autoConnect) }
    LaunchedEffect(car.running) { if (car.running) autoConnect = true }
    var linkMode by remember { mutableIntStateOf(app.prefs.linkMode) }
    var aaAuto by remember { mutableStateOf(app.prefs.aaAutoStart) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { RootPrep.revision.collect { resumed++ } }
    val radios = rememberRadios(context, resumed)
    val touchOn = remember(resumed) { FTTouchService.enabled }
    val overlayOn = remember(resumed) { Settings.canDrawOverlays(context) }
    val aaStep = remember(resumed) { AaInstaller.step(context) }
    val aaInstalled = remember(resumed) { AaInstaller.installed(context) }

    val on = car.running || autoConnect
    val cable = car.usb || linkMode == 2
    val direct = !cable && linkMode == 1
    val running = car.running && if (cable) car.usb else !car.usb && car.direct == direct
    val session = car.session
    val connected = session !is CarLifeSession.State.Idle
    val projecting = session is CarLifeSession.State.Projecting
    val steps = when {
        cable -> usbSteps(running, connected, projecting)
        direct -> directSteps(context, radios, car, running, connected, projecting)
        else -> hotspotSteps(context, radios, running, connected, projecting)
    }
    val way = when {
        car.usb -> "Using USB"
        car.running -> if (car.direct) "Using WiFi + BL" else "Using Hotspot"
        else -> when (linkMode) { 1 -> "Using WiFi + BL"; 2 -> "Using USB"; else -> "Using Hotspot" }
    }
    val status = when {
        projecting -> Status(
            "Connected",
            when {
                car.aaOverlay -> "Android Auto is on the car"
                car.mirroring -> "Your app is on the car"
                car.usb -> "FT is on the car screen over USB"
                else -> "FT is on the car screen"
            },
            3
        )
        connected -> Status("Connecting", if (car.usb) "Starting the car screen over USB" else "Starting the car screen", 2)
        car.running -> Status("Waiting for your car", way, 1)
        on -> Status("Starting", way, 1)
        else -> Status("Off", "Turn on to connect to your car", 0)
    }

    fun pickMode(value: Int) {
        if (value == linkMode) return
        linkMode = value
        app.prefs.linkMode = value
        if (car.running) CarLifeService.startAuto(context)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StatusCard(status, on) { want ->
                autoConnect = want
                if (want) CarLifeService.switchOn(context) else CarLifeService.switchOff(context)
            }
        }

        item {
            Section("Connection") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(0 to "Hotspot", 1 to "WiFi + BL", 2 to "USB").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = linkMode == value,
                            onClick = { pickMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(targetState = if (cable) 2 else if (direct) 1 else 0, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "steps") { _ ->
                    Stepper(steps, running)
                }
                if (car.usb && linkMode != 2) {
                    Text(
                        "The car is on the USB cable. FT goes back to ${if (linkMode == 1) "WiFi + BL" else "Hotspot"} when it is unplugged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                val refused = car.refused
                AnimatedVisibility(visible = refused.isNotBlank() && !connected && !cable) {
                    Notice(refused, if (direct) "Use Hotspot" else "Use WiFi + BL") { pickMode(if (direct) 0 else 1) }
                }
                if (direct && running && !connected) {
                    TextButton(onClick = { CarLifeService.tryAgain() }, modifier = Modifier.align(Alignment.End)) { Text("Try again") }
                }
            }
        }

        if (!touchOn || !overlayOn) {
            item {
                Section("Finish setup") {
                    Text("Needed to use phone apps on the car", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Check("Touch control", touchOn, onOpenAccessibility)
                    Check("Open apps from the car", overlayOn, onOpenOverlay)
                }
            }
        }

        item {
            Section("Android Auto") {
                if (!aaInstalled) {
                    Text(
                        "Android Auto is not on this phone. Install it from the Play Store, or let FT install it in Settings, Android Auto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenAaSetup, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Open Settings, Android Auto") }
                }
                if (aaInstalled) {
                    if (!rememberRootGranted()) {
                        AaStartChoice(rememberAaServerOn(), reinstalled = aaStep == AaInstaller.Step.DONE) {
                            if (aaStep != AaInstaller.Step.DONE) {
                                FilledTonalButton(onClick = onOpenAaSetup, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Set it up in Settings", maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    SwitchRow("Start with the car", aaAuto) {
                        aaAuto = it
                        app.prefs.aaAutoStart = it
                    }
                    FilledTonalButton(
                        onClick = { CarLifeService.startAa() },
                        enabled = projecting,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) { Text("Start Android Auto on the car", maxLines = 1) }
                    if (!projecting) {
                        Text(
                            if (on) "Works once FT is on the car screen." else "Turn FT on first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private fun usbSteps(running: Boolean, connected: Boolean, projecting: Boolean) = listOf(
    StepItem("Cable to the car", connected, hint = if (running) "Plug into the car's USB and open CarLife there. If the phone asks, pick FT and tap Always" else null),
    StepItem("On the car screen", projecting, hint = if (connected) "Starting the picture" else null)
)

private fun hotspotSteps(
    context: android.content.Context,
    radios: Radios,
    running: Boolean,
    connected: Boolean,
    projecting: Boolean
) = listOf(
    StepItem("Hotspot on", radios.hotspot, action = "Turn on", onAction = { Fixes.open(context, Fixes.hotspot(context)) }),
    StepItem("Car connected", connected, hint = if (running) "Join this hotspot from the car. Some cars only have Wi-Fi inside their CarLife settings" else null),
    StepItem("On the car screen", projecting, hint = if (connected) "Starting the picture" else null)
)

private fun directSteps(
    context: android.content.Context,
    radios: Radios,
    car: CarState,
    running: Boolean,
    connected: Boolean,
    projecting: Boolean
): List<StepItem> {
    val bluetooth = when {
        !radios.nearby -> StepItem("Bluetooth on", false, hint = "Allow nearby devices", action = "Allow", onAction = { Fixes.open(context, Fixes.appSettings(context)) })
        else -> StepItem("Bluetooth on", radios.bluetooth, action = "Turn on", onAction = { Fixes.open(context, Fixes.bluetooth(context)) })
    }
    val wifi = when {
        !radios.wifi -> StepItem("WiFi on", false, action = "Turn on", onAction = { Fixes.open(context, Fixes.wifi()) })
        !radios.wifiAllowed -> StepItem("WiFi on", false, hint = "Allow nearby devices", action = "Allow", onAction = { Fixes.open(context, Fixes.appSettings(context)) })
        !radios.location -> StepItem("WiFi on", false, hint = "WiFi Direct needs location on", action = "Turn on", onAction = { Fixes.open(context, Fixes.location()) })
        else -> StepItem("WiFi on", true)
    }
    return listOf(
        bluetooth,
        wifi,
        StepItem("Car found", car.btCar != null || car.carWifi != null, hint = if (running) "Waiting for the car over Bluetooth" else null),
        StepItem(
            "WiFi Direct connected",
            car.wifiDirect,
            hint = if (running) car.carWifi?.let { "Joining $it" } ?: "Asking the car to turn it on" else null
        ),
        StepItem(
            "On the car screen",
            projecting,
            hint = when {
                connected -> "Starting the picture"
                running && car.wifiDirect -> "Waiting for the car to connect"
                else -> null
            }
        )
    )
}


@Composable
private fun StatusCard(status: Status, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (status.level) { 3 -> scheme.primaryContainer; 2 -> scheme.secondaryContainer; 1 -> scheme.tertiaryContainer; else -> scheme.surfaceContainerHigh },
        label = "status"
    )
    val content by animateColorAsState(
        when (status.level) { 3 -> scheme.onPrimaryContainer; 2 -> scheme.onSecondaryContainer; 1 -> scheme.onTertiaryContainer; else -> scheme.onSurface },
        label = "statusText"
    )
    val dot by animateColorAsState(
        when (status.level) { 3 -> scheme.primary; 2 -> scheme.secondary; 1 -> scheme.tertiary; else -> scheme.outline },
        label = "dot"
    )
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(status.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action, color = scheme.onErrorContainer) }
        }
    }
}

@Composable
private fun Check(title: String, granted: Boolean, onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        StepMark(0, if (granted) StepState.DONE else StepState.LATER, working = false)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!granted) FilledTonalButton(onClick = onGrant) { Text("Allow") }
        else Text("Done", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}
