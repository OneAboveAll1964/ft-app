package app.ft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.ft.ui.car.CarIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.carlife.QuietShare
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.ft.carlife.WheelAction
import app.ft.ui.components.AaMissing
import app.ft.ui.components.AaStart
import app.ft.ui.components.rememberRootGranted
import app.ft.ui.components.rememberAaServerOn
import app.ft.ui.components.CornerPicker
import app.ft.ui.car.CarStyles
import app.ft.aa.AaInstaller
import app.ft.core.CarAudioBus
import app.ft.projection.VideoPlans
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.ft.R

enum class SettingsPage(@StringRes val title: Int, @StringRes val about: Int) {
    LOOK(R.string.settings_look_title, R.string.settings_look_about),
    CAR_SCREEN(R.string.settings_car_screen_title, R.string.settings_car_screen_about),
    PICTURE(R.string.settings_picture_title, R.string.settings_picture_about),
    SOUND(R.string.settings_sound_title, R.string.settings_sound_about),
    CAR(R.string.settings_car_title, R.string.settings_car_about),
    ANDROID_AUTO(R.string.settings_aa_title, R.string.settings_aa_about),
    ADVANCED(R.string.settings_advanced_title, R.string.settings_advanced_about)
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.LOOK -> CarIcons.Tune
    SettingsPage.CAR_SCREEN -> CarIcons.Apps
    SettingsPage.PICTURE -> CarIcons.Tune
    SettingsPage.SOUND -> CarIcons.Music
    SettingsPage.CAR -> Icons.Filled.Share
    SettingsPage.ANDROID_AUTO -> CarIcons.AndroidAuto
    SettingsPage.ADVANCED -> Icons.Filled.Build
}

@Composable
fun SettingsScreen(pad: PaddingValues, page: SettingsPage?, onPickAaFile: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val root = rememberRootGranted()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (page == null) {
            items(SettingsPage.entries) { p -> PageButton(p) { onOpen(p) } }
            item { MadeBy() }
        } else {
            if (page == SettingsPage.ANDROID_AUTO && !root) item { Group { AaSetup(onPickAaFile) } }
            if (page == SettingsPage.CAR_SCREEN) item { Group { CornerSetting() } }
            item { Group { PageContent(page) } }
        }
    }
}

@Composable
private fun PageButton(page: SettingsPage, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Icon(page.icon(), contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(page.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(page.about), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MadeBy() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.ft.core.Links.GITHUB)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) {
            Text(stringResource(R.string.made_by, "OneAboveAll1964"), style = MaterialTheme.typography.titleSmall)
        }
        if (version.isNotBlank()) {
            Text("FT $version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
private fun AaSetup(onPickAaFile: () -> Unit) {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val installed = remember(resumed) { AaInstaller.installed(context) }
    val ownCopy = remember(resumed) { AaInstaller.weInstalledIt(context) }
    val serverOn = rememberAaServerOn()
    if (installed) AaStart(serverOn, ownCopy, framed = false) else AaMissing(onPickAaFile, framed = false)
}

@Composable
private fun CornerSetting() {
    val p = FTApp.instance.prefs
    var corner by remember { mutableIntStateOf(p.aaCorner) }
    Text(stringResource(R.string.corner_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.corner_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    CornerPicker(corner, p.aaWidth, p.aaHeight) {
        corner = it
        p.aaCorner = it
        CarStyles.reload()
    }
}

@Composable
private fun PageContent(page: SettingsPage) {
    val p = FTApp.instance.prefs
    when (page) {
        SettingsPage.LOOK -> LookSettings()
        SettingsPage.CAR_SCREEN -> CarLookSettings()
        SettingsPage.PICTURE -> PictureSettings()
        SettingsPage.SOUND -> {
            GuidanceSetting()
            BoolSetting(stringResource(R.string.sound_bluetooth), p.soundOverBluetooth) { p.soundOverBluetooth = it }
            BoolSetting(stringResource(R.string.sound_silence_phone), p.muteWhileProjecting) { p.muteWhileProjecting = it }
            QuietShareSetting()
        }
        SettingsPage.CAR -> {
            TextSetting(stringResource(R.string.car_name_label), p.carName) { p.carName = it }
            TextSetting(stringResource(R.string.car_p2p_label), p.carP2pName) { p.carP2pName = it }
            TextSetting(stringResource(R.string.car_bt_label), p.carBtName) { p.carBtName = it }
            TextSetting(stringResource(R.string.car_pin_label), p.carWpsPin) { p.carWpsPin = it }
            BoolSetting(stringResource(R.string.car_swap_keys), p.swapTrackKeys) { p.swapTrackKeys = it }
            Text(stringResource(R.string.wheel_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.wheel_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WheelSetting(stringResource(R.string.wheel_next_again), p.wheelLongNext) { p.wheelLongNext = it }
            WheelSetting(stringResource(R.string.wheel_prev_again), p.wheelLongPrev) { p.wheelLongPrev = it }
        }
        SettingsPage.ANDROID_AUTO -> {
            BoolSetting(stringResource(R.string.aa_match_car), p.aaMatchCar) { p.aaMatchCar = it }
            BoolSetting(stringResource(R.string.aa_controls_left), p.aaControlsLeft) { p.aaControlsLeft = it }
            IntSetting(stringResource(R.string.aa_port), p.aaPort) { p.aaPort = it }
            IntSetting(stringResource(R.string.aa_width), p.aaWidth) { p.aaWidth = it }
            IntSetting(stringResource(R.string.aa_height), p.aaHeight) { p.aaHeight = it }
            IntSetting(stringResource(R.string.aa_fps), p.aaFps) { p.aaFps = it }
            IntSetting(stringResource(R.string.aa_density), p.aaDensity) { p.aaDensity = it }
        }
        SettingsPage.ADVANCED -> {
            Text(stringResource(R.string.adv_ports), style = MaterialTheme.typography.labelLarge)
            IntSetting(stringResource(R.string.adv_command), p.cmdPort) { p.cmdPort = it }
            IntSetting(stringResource(R.string.adv_video), p.videoPort) { p.videoPort = it }
            IntSetting(stringResource(R.string.adv_media), p.mediaPort) { p.mediaPort = it }
            IntSetting(stringResource(R.string.adv_tts), p.ttsPort) { p.ttsPort = it }
            IntSetting(stringResource(R.string.adv_voice), p.vrPort) { p.vrPort = it }
            IntSetting(stringResource(R.string.adv_touch), p.touchPort) { p.touchPort = it }
        }
    }
}

private val GUIDANCE = listOf(
    Triple(CarAudioBus.GUIDANCE_UNTOUCHED, R.string.guidance_like_baidu, R.string.guidance_like_baidu_body),
    Triple(CarAudioBus.GUIDANCE_IN_STEP, R.string.guidance_in_step, R.string.guidance_in_step_body),
    Triple(CarAudioBus.GUIDANCE_DIP, R.string.guidance_on_time, R.string.guidance_on_time_body)
)

@Composable
private fun GuidanceSetting() {
    val p = FTApp.instance.prefs
    var mode by remember { mutableIntStateOf(p.guidanceMode) }
    val chosen = GUIDANCE.firstOrNull { it.first == mode } ?: GUIDANCE.first()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.guidance_title), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GUIDANCE.forEach { (value, label, _) ->
                ValueChip(stringResource(label), chosen.first == value, Modifier.weight(1f)) {
                    mode = value
                    p.guidanceMode = value
                    CarAudioBus.guidance = value
                }
            }
        }
        Text(stringResource(chosen.third), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietShareSetting() {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    var allowed by remember { mutableStateOf(QuietShare.allowed(context)) }
    LaunchedEffect(resumed) { allowed = QuietShare.allowed(context) }
    DisposableEffect(Unit) {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val watch = AppOpsManager.OnOpChangedListener { _, _ -> allowed = QuietShare.allowed(context) }
        runCatching { ops?.startWatchingMode(QuietShare.OP, context.packageName, watch) }
        onDispose { runCatching { ops?.stopWatchingMode(watch) } }
    }
    val command = if (allowed) QuietShare.undo(context) else QuietShare.command(context)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.quiet_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(50),
                color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (allowed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(stringResource(if (allowed) R.string.state_on else R.string.state_off), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Text(
            stringResource(if (allowed) R.string.quiet_on_body else R.string.quiet_off_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FT", command))
            }) { Text(stringResource(R.string.action_copy)) }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) { Text(stringResource(R.string.action_share)) }
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun IntSetting(label: String, initial: Int, rev: Int = 0, onChange: (Int) -> Unit) {
    var text by remember(rev) { mutableStateOf(initial.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; v.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TextSetting(label: String, initial: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; onChange(v) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BoolSetting(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

private val SIZE_CHOICES = listOf(
    VideoPlans.SIZE_CAR to R.string.picture_size_car,
    VideoPlans.SIZE_BAIDU to R.string.picture_size_baidu,
    VideoPlans.SIZE_CUSTOM to R.string.picture_size_custom
)

private val FPS_CHOICES = listOf(0 to "", 15 to "15", 20 to "20", 24 to "24", 30 to "30")

private val RATE_CHOICES = listOf(2_000_000 to "2", 2_560_000 to "2.56", 3_000_000 to "3", 3_840_000 to "3.84", 5_000_000 to "5")

private val FLOOR_CHOICES = listOf(0 to "", 18 to "18", 22 to "22", 26 to "26")

@Composable
private fun PictureSettings() {
    val p = FTApp.instance.prefs
    var rev by remember { mutableIntStateOf(0) }
    var size by remember(rev) { mutableIntStateOf(p.videoSize) }
    var fps by remember(rev) { mutableIntStateOf(p.videoFps) }
    var rate by remember(rev) { mutableIntStateOf(p.videoBitrate) }
    var floor by remember(rev) { mutableIntStateOf(p.videoQpFloor) }
    val defaults = size == VideoPlans.SIZE_CAR && fps == 0 && rate == VideoPlans.DEFAULT_BITRATE && p.videoMinFps == 0 && floor == DEFAULT_FLOOR
    Text(
        stringResource(if (defaults) R.string.picture_defaults_body else R.string.picture_changed_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(stringResource(R.string.picture_size_label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SIZE_CHOICES.forEach { (value, label) ->
            ValueChip(stringResource(label), size == value, Modifier.weight(1f)) { size = value; p.videoSize = value }
        }
    }
    if (size == VideoPlans.SIZE_CUSTOM) {
        IntSetting(stringResource(R.string.picture_width), p.videoWidth, rev) { p.videoWidth = it }
        IntSetting(stringResource(R.string.picture_height), p.videoHeight, rev) { p.videoHeight = it }
    }
    Text(stringResource(R.string.picture_fps_label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FPS_CHOICES.forEach { (value, label) ->
            ValueChip(label.ifEmpty { stringResource(R.string.choice_auto) }, fps == value, Modifier.weight(1f)) { fps = value; p.videoFps = value }
        }
    }
    Text(stringResource(R.string.picture_rate_label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RATE_CHOICES.forEach { (value, label) ->
            ValueChip(label, rate == value, Modifier.weight(1f)) { rate = value; p.videoBitrate = value }
        }
    }
    Text(
        stringResource(R.string.picture_rate_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(stringResource(R.string.picture_floor_label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FLOOR_CHOICES.forEach { (value, label) ->
            ValueChip(label.ifEmpty { stringResource(R.string.state_off) }, floor == value, Modifier.weight(1f)) { floor = value; p.videoQpFloor = value }
        }
    }
    Text(
        stringResource(R.string.picture_floor_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    IntSetting(stringResource(R.string.picture_min_fps), p.videoMinFps, rev) { p.videoMinFps = it }
    IntSetting(stringResource(R.string.picture_density), p.carDensity, rev) { p.carDensity = it }
    OutlinedButton(
        onClick = {
            p.videoSize = VideoPlans.SIZE_CAR
            p.videoWidth = 0
            p.videoHeight = 0
            p.videoFps = 0
            p.videoMinFps = 0
            p.videoBitrate = VideoPlans.DEFAULT_BITRATE
            p.videoQpFloor = DEFAULT_FLOOR
            p.carDensity = 160
            rev++
        },
        enabled = !defaults || p.carDensity != 160,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(if (defaults && p.carDensity == 160) R.string.picture_at_defaults else R.string.picture_reset))
    }
}

private const val DEFAULT_FLOOR = 22

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WheelSetting(label: String, initial: Int, onChange: (Int) -> Unit) {
    var chosen by remember { mutableIntStateOf(initial) }
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WheelAction.ALL.forEach { action ->
            ValueChip(stringResource(WheelAction.label(action)), chosen == action, Modifier) {
                chosen = action
                onChange(action)
            }
        }
    }
}

@Composable
private fun ValueChip(label: String, chosen: Boolean, modifier: Modifier, onPick: () -> Unit) {
    if (chosen) {
        FilledTonalButton(onClick = onPick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Text(label, maxLines = 1, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onPick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Text(label, maxLines = 1, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, overflow = TextOverflow.Ellipsis)
        }
    }
}
