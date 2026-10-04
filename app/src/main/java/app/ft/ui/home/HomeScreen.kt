package app.ft.ui.home

import app.ft.ui.car.CarStyles
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
import androidx.compose.material3.OutlinedButton
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
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaInstaller
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState

private data class StepItem(
    val title: String,
    val done: Boolean,
    val hint: String? = null,
    val action: String? = null,
    val onAction: (() -> Unit)? = null
)

private enum class StepState { DONE, NOW, LATER }

private data class Status(val title: String, val detail: String, val level: Int)

@Composable
fun HomeScreen(pad: PaddingValues, onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit, onTakeOverAa: () -> Unit) {
    val context = LocalContext.current
    val app = FTApp.instance
    val car by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    var autoConnect by remember { mutableStateOf(app.prefs.autoConnect) }
    LaunchedEffect(car.running) { if (car.running) autoConnect = true }
    var linkMode by remember { mutableIntStateOf(app.prefs.linkMode) }
    var aaAuto by remember { mutableStateOf(app.prefs.aaAutoStart) }
    var aaCorner by remember { mutableIntStateOf(app.prefs.aaCorner) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val radios = rememberRadios(context, resumed)
    val touchOn = remember(resumed) { FTTouchService.enabled }
    val overlayOn = remember(resumed) { Settings.canDrawOverlays(context) }
    val aaStep = remember(resumed) { AaInstaller.step(context) }
    val aaInstalled = remember(resumed) { AaInstaller.installed(context) }
    val aaHasCopy = remember(resumed) { AaInstaller.stashed(context).isNotEmpty() }

    val on = car.running || autoConnect
    val direct = linkMode == 1
    val running = car.running && car.direct == direct
    val session = car.session
    val connected = session !is CarLifeSession.State.Idle
    val projecting = session is CarLifeSession.State.Projecting
    val steps = if (direct) directSteps(context, radios, car, running, connected, projecting)
    else hotspotSteps(context, radios, running, connected, projecting)
    val status = when {
        projecting -> Status(
            "Connected",
            when {
                car.aaOverlay -> "Android Auto is on the car"
                car.mirroring -> "Your app is on the car"
                else -> "FT is on the car screen"
            },
            3
        )
        connected -> Status("Connecting", "Starting the car screen", 2)
        car.running -> Status("Waiting for your car", if (car.direct) "Using WiFi + BL" else "Using Hotspot", 1)
        on -> Status("Starting", if (direct) "Using WiFi + BL" else "Using Hotspot", 1)
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StatusCard(status, on) { want ->
                autoConnect = want
                app.prefs.autoConnect = want
                if (want) CarLifeService.startAuto(context) else CarLifeService.stop(context)
            }
        }

        item {
            Section("Connection") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(0 to "Hotspot", 1 to "WiFi + BL").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = linkMode == value,
                            onClick = { pickMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(targetState = direct, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "steps") { _ ->
                    Stepper(steps, running)
                }
                val refused = car.refused
                AnimatedVisibility(visible = refused.isNotBlank() && !connected) {
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
                if (aaStep != AaInstaller.Step.DONE) {
                    Stepper(aaSetupSteps(aaStep, aaHasCopy, onTakeOverAa), running = false)
                    if (aaInstalled) Spacer(Modifier.height(12.dp))
                }
                if (aaInstalled) {
                    val serverOn = if (aa.listening) aa.selfServer else app.prefs.aaServerOn
                    ServerRow(serverOn) { AaInstaller.openSettings(context) }
                    SwitchRow("Start with the car", aaAuto) {
                        aaAuto = it
                        app.prefs.aaAutoStart = it
                    }
                    Text(
                        "FT button on the car",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                    CornerPicker(aaCorner, app.prefs.aaWidth, app.prefs.aaHeight) {
                        aaCorner = it
                        app.prefs.aaCorner = it
                        CarStyles.reload()
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                AaHeadUnitService.start(context, app.prefs.aaBluetooth)
                                CarLifeService.startAa()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Start", maxLines = 1) }
                        OutlinedButton(onClick = { AaInstaller.openSettings(context) }, modifier = Modifier.weight(1f)) {
                            Text("Settings", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun hotspotSteps(
    context: android.content.Context,
    radios: Radios,
    running: Boolean,
    connected: Boolean,
    projecting: Boolean
) = listOf(
    StepItem("Hotspot on", radios.hotspot, action = "Turn on", onAction = { Fixes.open(context, Fixes.hotspot(context)) }),
    StepItem("Car connected", connected, hint = if (running) "Waiting for the car to join the hotspot" else null),
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

private fun aaSetupSteps(step: AaInstaller.Step, hasCopy: Boolean, run: () -> Unit): List<StepItem> {
    val remove = when (step) {
        AaInstaller.Step.REMOVE_UPDATES -> StepItem("Remove the Android Auto update", false, hint = "FT keeps a copy first", action = "Remove", onAction = run)
        AaInstaller.Step.UNINSTALL -> StepItem("Remove Android Auto", false, hint = "FT keeps a copy first", action = "Remove", onAction = run)
        else -> if (hasCopy) StepItem("Remove the Play Store copy", true) else null
    }
    val install = if (hasCopy || remove != null) StepItem("Put Android Auto back", step == AaInstaller.Step.DONE, action = "Put back", onAction = run)
    else StepItem("Install Android Auto", step == AaInstaller.Step.DONE, hint = "Pick the Android Auto file", action = "Pick file", onAction = run)
    return listOfNotNull(remove, install)
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
private fun Stepper(steps: List<StepItem>, running: Boolean) {
    val reached = steps.indexOfLast { it.done }
    val done = steps.mapIndexed { i, s -> s.done || i < reached }
    val now = done.indexOfFirst { !it }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        steps.forEachIndexed { i, step ->
            val state = when {
                done[i] -> StepState.DONE
                i == now -> StepState.NOW
                else -> StepState.LATER
            }
            StepRow(i + 1, step, state, last = i == steps.lastIndex, working = running && state == StepState.NOW && step.action == null)
        }
    }
}

@Composable
private fun StepRow(number: Int, step: StepItem, state: StepState, last: Boolean, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMark(number, state, working)
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (state == StepState.DONE) scheme.primary else scheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
            Box(Modifier.heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (state == StepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state == StepState.LATER) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state == StepState.NOW && step.hint != null) {
                Text(step.hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (state == StepState.NOW && step.action != null && step.onAction != null) {
                FilledTonalButton(onClick = step.onAction, modifier = Modifier.padding(top = 8.dp)) { Text(step.action) }
            }
        }
    }
}

@Composable
private fun StepMark(number: Int, state: StepState, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when {
            state == StepState.DONE -> Box(
                Modifier.fillMaxSize().clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            working -> CircularProgressIndicator(Modifier.fillMaxSize().padding(2.dp), strokeWidth = 3.dp, color = scheme.primary)
            state == StepState.NOW -> Box(
                Modifier.fillMaxSize().border(2.dp, scheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.Bold)
            }
            else -> Box(
                Modifier.fillMaxSize().border(1.5.dp, scheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (number > 0) Text("$number", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
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
private fun ServerRow(on: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Head unit server", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (on) "On" else "Off · start it from Android Auto's menu",
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) scheme.primary else scheme.onSurfaceVariant
            )
        }
        if (!on) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onOpen) { Text("Open") }
        }
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

@Composable
private fun CornerPicker(selected: Int, carWidth: Int, carHeight: Int, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (carHeight > 0) (carWidth.toFloat() / carHeight).coerceIn(1.6f, 3f) else 16f / 9f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(shape)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHighest)
            .border(2.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Text(
            "Car screen",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
        listOf(0 to Alignment.TopStart, 1 to Alignment.TopEnd, 2 to Alignment.BottomStart, 3 to Alignment.BottomEnd).forEach { (value, align) ->
            val chosen = selected == value
            Box(
                Modifier
                    .align(align)
                    .padding(8.dp)
                    .size(width = 46.dp, height = 26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) scheme.primary else scheme.surfaceContainer)
                    .border(1.dp, if (chosen) scheme.primary else scheme.outline, RoundedCornerShape(50))
                    .clickable { onPick(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "FT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = if (chosen) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}
