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
import app.ft.ui.car.CarIcons
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaInstaller
import app.ft.ui.components.FitText
import app.ft.ui.components.Stepper
import app.ft.ui.components.StepState
import app.ft.ui.components.StepMark
import app.ft.ui.components.AaStart
import app.ft.ui.components.rememberRootGranted
import app.ft.core.RootPrep
import app.ft.ui.components.rememberAaServerOn
import app.ft.ui.components.StepItem
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState
import androidx.compose.ui.res.stringResource
import app.ft.R

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
    val backgroundOn = remember(resumed) { (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) != false }
    val aaOwnCopy = remember(resumed) { AaInstaller.weInstalledIt(context) }
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
        else -> hotspotSteps(context, radios, running, connected, projecting, car.hotspotClients)
    }
    val way = stringResource(
        when {
            car.usb -> R.string.home_way_usb
            car.running -> if (car.direct) R.string.home_way_direct else R.string.home_way_hotspot
            else -> when (linkMode) { 1 -> R.string.home_way_direct; 2 -> R.string.home_way_usb; else -> R.string.home_way_hotspot }
        }
    )
    val status = when {
        projecting -> Status(
            stringResource(R.string.home_status_connected),
            stringResource(
                when {
                    car.aaOverlay -> R.string.home_detail_aa
                    car.mirroring -> R.string.home_detail_app
                    car.usb -> R.string.home_detail_ft_usb
                    else -> R.string.home_detail_ft
                }
            ),
            3
        )
        connected -> Status(stringResource(R.string.home_status_connecting), stringResource(if (car.usb) R.string.home_detail_starting_usb else R.string.home_detail_starting), 2)
        car.running -> Status(stringResource(R.string.home_status_waiting), way, 1)
        on -> Status(stringResource(R.string.home_status_starting), way, 1)
        else -> Status(stringResource(R.string.home_status_off), stringResource(R.string.home_detail_off), 0)
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
            Section(stringResource(R.string.home_connection)) {
                key(LocalLayoutDirection.current) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(0 to stringResource(R.string.mode_hotspot), 1 to stringResource(R.string.mode_direct), 2 to stringResource(R.string.mode_usb)).forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = linkMode == value,
                                onClick = { pickMode(value) },
                                shape = SegmentedButtonDefaults.itemShape(i, 3)
                            ) { FitText(label) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(targetState = if (cable) 2 else if (direct) 1 else 0, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "steps") { _ ->
                    Stepper(steps, running)
                }
                if (car.usb && linkMode != 2) {
                    Text(
                        stringResource(R.string.home_usb_takes_over, stringResource(if (linkMode == 1) R.string.mode_direct else R.string.mode_hotspot)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                AnimatedVisibility(visible = car.blocked != 0 && !connected) {
                    val baidu = remember(car.blocked) { runCatching { context.packageManager.getPackageInfo("com.baidu.carlife", 0); true }.getOrDefault(false) }
                    if (car.blocked == 0) Unit
                    else if (baidu) Notice(stringResource(car.blocked), stringResource(R.string.action_open)) {
                        runCatching {
                            context.startActivity(android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:com.baidu.carlife")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } else Notice(stringResource(car.blocked))
                }
                val refused = car.refused
                AnimatedVisibility(visible = refused != 0 && !connected && !cable) {
                    if (refused != 0) Notice(stringResource(refused), stringResource(R.string.home_use_mode, stringResource(if (direct) R.string.mode_hotspot else R.string.mode_direct))) { pickMode(if (direct) 0 else 1) }
                }
                if (direct && running && !connected) {
                    TextButton(onClick = { CarLifeService.tryAgain() }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.home_try_again)) }
                }
            }
        }

        item {
            Section(stringResource(R.string.settings_aa_title)) {
                if (!aaInstalled) {
                    Text(
                        stringResource(R.string.home_aa_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenAaSetup, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.home_aa_open_settings)) }
                }
                if (aaInstalled) {
                    if (!rememberRootGranted()) {
                        AaStart(rememberAaServerOn(), aaOwnCopy)
                        Spacer(Modifier.height(8.dp))
                    }
                    SwitchRow(stringResource(R.string.home_aa_start_with_car), aaAuto) {
                        aaAuto = it
                        app.prefs.aaAutoStart = it
                    }
                    FilledTonalButton(
                        onClick = { CarLifeService.startAa() },
                        enabled = projecting,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) { Text(stringResource(R.string.home_aa_start), maxLines = 1) }
                    if (!projecting) {
                        Text(
                            stringResource(if (on) R.string.home_aa_works_once else R.string.home_aa_turn_on_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (!touchOn || !overlayOn || !backgroundOn) {
            item {
                Section(stringResource(R.string.home_finish_setup)) {
                    Text(stringResource(R.string.home_finish_setup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Check(stringResource(R.string.check_touch), touchOn, onOpenAccessibility)
                    Check(stringResource(R.string.check_overlay), overlayOn, onOpenOverlay)
                    Check(stringResource(R.string.check_background), backgroundOn) { Fixes.open(context, Fixes.background(context)) }
                }
            }
        }
    }
}

@Composable
private fun usbSteps(running: Boolean, connected: Boolean, projecting: Boolean) = listOf(
    StepItem(stringResource(R.string.step_cable), connected, hint = if (running) stringResource(R.string.step_cable_hint) else null),
    StepItem(stringResource(R.string.step_on_car_screen), projecting, hint = if (connected) stringResource(R.string.step_starting_picture) else null)
)

@Composable
private fun hotspotSteps(
    context: android.content.Context,
    radios: Radios,
    running: Boolean,
    connected: Boolean,
    projecting: Boolean,
    clients: List<String>?
) = listOf(
    StepItem(stringResource(R.string.step_hotspot_on), radios.hotspot || !clients.isNullOrEmpty(), action = stringResource(R.string.action_turn_on), onAction = { Fixes.openHotspot(context) }),
    StepItem(
        stringResource(R.string.step_car_connected),
        connected,
        hint = when {
            !running -> null
            !clients.isNullOrEmpty() -> stringResource(R.string.step_hotspot_joined, clients.joinToString())
            else -> stringResource(R.string.step_hotspot_join)
        }
    ),
    StepItem(stringResource(R.string.step_on_car_screen), projecting, hint = if (connected) stringResource(R.string.step_starting_picture) else null)
)

@Composable
private fun directSteps(
    context: android.content.Context,
    radios: Radios,
    car: CarState,
    running: Boolean,
    connected: Boolean,
    projecting: Boolean
): List<StepItem> {
    val bluetooth = when {
        !radios.nearby -> StepItem(stringResource(R.string.step_bluetooth_on), false, hint = stringResource(R.string.step_allow_nearby), action = stringResource(R.string.action_allow), onAction = { Fixes.open(context, Fixes.appSettings(context)) })
        else -> StepItem(stringResource(R.string.step_bluetooth_on), radios.bluetooth, action = stringResource(R.string.action_turn_on), onAction = { Fixes.open(context, Fixes.bluetooth(context)) })
    }
    val wifi = when {
        !radios.wifi -> StepItem(stringResource(R.string.step_wifi_on), false, action = stringResource(R.string.action_turn_on), onAction = { Fixes.open(context, Fixes.wifi()) })
        !radios.wifiAllowed -> StepItem(stringResource(R.string.step_wifi_on), false, hint = stringResource(R.string.step_allow_nearby), action = stringResource(R.string.action_allow), onAction = { Fixes.open(context, Fixes.appSettings(context)) })
        !radios.location -> StepItem(stringResource(R.string.step_wifi_on), false, hint = stringResource(R.string.step_wifi_needs_location), action = stringResource(R.string.action_turn_on), onAction = { Fixes.open(context, Fixes.location()) })
        else -> StepItem(stringResource(R.string.step_wifi_on), true)
    }
    return listOf(
        bluetooth,
        wifi,
        StepItem(stringResource(R.string.step_car_found), car.btCar != null || car.carWifi != null, hint = if (running) stringResource(R.string.step_waiting_bt) else null),
        StepItem(
            stringResource(R.string.step_wifi_direct_connected),
            car.wifiDirect,
            hint = if (running) car.carWifi?.let { stringResource(R.string.step_joining, it) } ?: stringResource(R.string.step_asking_car) else null
        ),
        StepItem(
            stringResource(R.string.step_on_car_screen),
            projecting,
            hint = when {
                connected -> stringResource(R.string.step_starting_picture)
                running && car.wifiDirect -> stringResource(R.string.step_waiting_car_connect)
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
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = content.copy(alpha = 0.08f), contentColor = content) {
                    Icon(CarIcons.Phone, contentDescription = null, modifier = Modifier.padding(12.dp).size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = onToggle)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(8.dp))
                    Text(status.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(status.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(start = 14.dp, end = if (action == null) 14.dp else 6.dp, top = if (action == null) 12.dp else 6.dp, bottom = if (action == null) 12.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action, color = scheme.onErrorContainer) }
        }
    }
}

@Composable
private fun Check(title: String, granted: Boolean, onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        StepMark(0, if (granted) StepState.DONE else StepState.LATER, working = false)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (!granted) FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.action_allow)) }
        else Text(stringResource(R.string.state_done), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}
