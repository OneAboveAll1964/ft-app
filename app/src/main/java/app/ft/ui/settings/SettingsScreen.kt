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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import app.ft.ui.components.AaStartChoice
import app.ft.ui.components.rememberAaServerOn
import app.ft.ui.components.Stepper
import app.ft.ui.components.StepItem
import app.ft.ui.components.CornerPicker
import app.ft.ui.car.CarStyles
import app.ft.aa.AaInstaller
import app.ft.core.CarAudioBus
import app.ft.projection.VideoPlans

enum class SettingsPage(val title: String, val about: String) {
    CAR_SCREEN("Car screen", "Background, colours and the buttons on the car"),
    PICTURE("Picture", "Size, frame rate and quality of the picture sent to the car"),
    SOUND("Sound", "Directions over music, bluetooth sound and sharing the phone's sound"),
    CAR("Car and connection", "Names, WiFi Direct, bluetooth and steering wheel keys"),
    ANDROID_AUTO("Android Auto", "How Android Auto is drawn on the car"),
    ADVANCED("Advanced", "CarLife ports the head unit connects to")
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.CAR_SCREEN -> CarIcons.Apps
    SettingsPage.PICTURE -> CarIcons.Tune
    SettingsPage.SOUND -> CarIcons.Music
    SettingsPage.CAR -> Icons.Filled.Share
    SettingsPage.ANDROID_AUTO -> CarIcons.AndroidAuto
    SettingsPage.ADVANCED -> Icons.Filled.Build
}

@Composable
fun SettingsScreen(pad: PaddingValues, page: SettingsPage?, onTakeOverAa: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (page == null) {
            items(SettingsPage.entries) { p -> PageButton(p) { onOpen(p) } }
            item { MadeBy() }
        } else {
            if (page == SettingsPage.ANDROID_AUTO) item { Group { AaSetup(onTakeOverAa) } }
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
                Text(page.title, style = MaterialTheme.typography.titleMedium)
                Text(page.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROFILE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) {
            Text("Made by OneAboveAll1964", style = MaterialTheme.typography.titleSmall)
        }
        if (version.isNotBlank()) {
            Text("FT $version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val PROFILE = "https://github.com/OneAboveAll1964"

@Composable
private fun AaSetup(onTakeOverAa: () -> Unit) {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val step = remember(resumed) { AaInstaller.step(context) }
    val hasCopy = remember(resumed) { AaInstaller.stashed(context).isNotEmpty() }
    val installed = remember(resumed) { AaInstaller.installed(context) }
    val serverOn = rememberAaServerOn()
    if (!installed && !hasCopy) {
        Text("Android Auto is not on this phone", style = MaterialTheme.typography.titleMedium)
        Text(
            "Install it from the Play Store, or pick an Android Auto file and FT installs it so it starts by itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Stepper(aaSetupSteps(step, hasCopy, onTakeOverAa), running = false)
        return
    }
    AaStartChoice(serverOn, reinstalled = step == AaInstaller.Step.DONE) {
        if (step != AaInstaller.Step.DONE) {
            Spacer(Modifier.height(10.dp))
            Stepper(aaSetupSteps(step, hasCopy, onTakeOverAa), running = false)
            Text(
                "Afterwards turn off Play Store auto-update for Android Auto, or an update takes it back from FT and this needs doing again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
private fun CornerSetting() {
    val p = FTApp.instance.prefs
    var corner by remember { mutableIntStateOf(p.aaCorner) }
    Text("FT button on the car", style = MaterialTheme.typography.titleMedium)
    Text(
        "Where the FT button sits on the car screen over Android Auto, phone apps and FT's own screens. It always takes you back to FT's car home.",
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
        SettingsPage.CAR_SCREEN -> CarLookSettings()
        SettingsPage.PICTURE -> PictureSettings()
        SettingsPage.SOUND -> {
            GuidanceSetting()
            BoolSetting("Use the car's bluetooth for sound instead", p.soundOverBluetooth) { p.soundOverBluetooth = it }
            BoolSetting("Silence the phone while FT streams the sound", p.muteWhileProjecting) { p.muteWhileProjecting = it }
            QuietShareSetting()
        }
        SettingsPage.CAR -> {
            TextSetting("Name shown to the head unit", p.carName) { p.carName = it }
            TextSetting("WiFi Direct name (blank = any CarLife)", p.carP2pName) { p.carP2pName = it }
            TextSetting("Bluetooth name for auto-start (blank = any)", p.carBtName) { p.carBtName = it }
            TextSetting("WiFi Direct PIN (blank = push button)", p.carWpsPin) { p.carWpsPin = it }
            BoolSetting("Swap the steering wheel's next and previous", p.swapTrackKeys) { p.swapTrackKeys = it }
        }
        SettingsPage.ANDROID_AUTO -> {
            BoolSetting("Draw at the car's own size", p.aaMatchCar) { p.aaMatchCar = it }
            BoolSetting("Put its controls on the left", p.aaControlsLeft) { p.aaControlsLeft = it }
            IntSetting("TCP port", p.aaPort) { p.aaPort = it }
            IntSetting("Video width", p.aaWidth) { p.aaWidth = it }
            IntSetting("Video height", p.aaHeight) { p.aaHeight = it }
            IntSetting("Frame rate", p.aaFps) { p.aaFps = it }
            IntSetting("Density", p.aaDensity) { p.aaDensity = it }
        }
        SettingsPage.ADVANCED -> {
            Text("Ports the head unit connects to", style = MaterialTheme.typography.labelLarge)
            IntSetting("Command", p.cmdPort) { p.cmdPort = it }
            IntSetting("Video", p.videoPort) { p.videoPort = it }
            IntSetting("Media", p.mediaPort) { p.mediaPort = it }
            IntSetting("TTS", p.ttsPort) { p.ttsPort = it }
            IntSetting("Voice", p.vrPort) { p.vrPort = it }
            IntSetting("Touch", p.touchPort) { p.touchPort = it }
        }
    }
}

private val GUIDANCE = listOf(
    Triple(CarAudioBus.GUIDANCE_UNTOUCHED, "Like Baidu", "Directions go to the car on their own channel the moment they arrive and the car decides how the music sits under them, the way Baidu CarLife does it."),
    Triple(CarAudioBus.GUIDANCE_IN_STEP, "In step", "FT mixes directions into the music and dips the music exactly while they speak. On drives where the car holds the music longer, directions wait for it."),
    Triple(CarAudioBus.GUIDANCE_DIP, "On time", "Directions speak the moment they arrive and FT dips the music with them, which on some drives reaches the car late.")
)

@Composable
private fun GuidanceSetting() {
    val p = FTApp.instance.prefs
    var mode by remember { mutableIntStateOf(p.guidanceMode) }
    val chosen = GUIDANCE.firstOrNull { it.first == mode } ?: GUIDANCE.first()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Directions over music", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GUIDANCE.forEach { (value, label, _) ->
                ValueChip(label, chosen.first == value, Modifier.weight(1f)) {
                    mode = value
                    p.guidanceMode = value
                    CarAudioBus.guidance = value
                }
            }
        }
        Text(chosen.third, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Share sound without asking", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(50),
                color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (allowed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(if (allowed) "On" else "Off", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Text(
            if (allowed) "FT passes other apps' sound to the car by itself, with no question on the phone. To turn this off, run:"
            else "Android asks on the phone before FT can pass other apps' sound to the car. Run this once from a computer with USB debugging on, and it stops asking:",
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
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) { Text("Share") }
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
    VideoPlans.SIZE_BAIDU to "Like Baidu",
    VideoPlans.SIZE_CAR to "Car's own",
    VideoPlans.SIZE_CUSTOM to "Custom"
)

private val FPS_CHOICES = listOf(0 to "Like Baidu", 15 to "15", 20 to "20", 24 to "24", 30 to "30")

private val RATE_CHOICES = listOf(0 to "Like Baidu", 1_500_000 to "1.5", 2_500_000 to "2.5", 4_000_000 to "4", 6_000_000 to "6")

private val FLOOR_CHOICES = listOf(0 to "Off", 18 to "18", 22 to "22", 26 to "26")

@Composable
private fun PictureSettings() {
    val p = FTApp.instance.prefs
    var rev by remember { mutableIntStateOf(0) }
    var size by remember(rev) { mutableIntStateOf(p.videoSize) }
    var fps by remember(rev) { mutableIntStateOf(p.videoFps) }
    var rate by remember(rev) { mutableIntStateOf(p.videoBitrate) }
    var floor by remember(rev) { mutableIntStateOf(p.videoQpFloor) }
    val baidu = size == VideoPlans.SIZE_BAIDU && fps == 0 && rate == 0 && p.videoMinFps == 0 && floor == DEFAULT_FLOOR
    Text(
        if (baidu) "FT sends the car its picture the way Baidu CarLife does: 1280×720 to a 1920×720 car like the Corolla, starting at 20 frames a second and following what the car asks for."
        else "Changes take effect the next time the car connects.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("Size sent to the car", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SIZE_CHOICES.forEach { (value, label) ->
            ValueChip(label, size == value, Modifier.weight(1f)) { size = value; p.videoSize = value }
        }
    }
    if (size == VideoPlans.SIZE_CUSTOM) {
        IntSetting("Width", p.videoWidth, rev) { p.videoWidth = it }
        IntSetting("Height", p.videoHeight, rev) { p.videoHeight = it }
    }
    Text("Frames a second", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FPS_CHOICES.forEach { (value, label) ->
            ValueChip(label, fps == value, Modifier.weight(if (value == 0) 2f else 1f)) { fps = value; p.videoFps = value }
        }
    }
    Text("Picture data in Mbps", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RATE_CHOICES.forEach { (value, label) ->
            ValueChip(label, rate == value, Modifier.weight(if (value == 0) 2f else 1f)) { rate = value; p.videoBitrate = value }
        }
    }
    Text("Quality floor", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FLOOR_CHOICES.forEach { (value, label) ->
            ValueChip(label, floor == value, Modifier.weight(1f)) { floor = value; p.videoQpFloor = value }
        }
    }
    Text(
        "Keeps every full picture small enough for the car to take, so the screen comes back after the car shows its own screens. Baidu does this on its newest phones. Lower numbers are sharper and bigger, Off sends whatever the phone makes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    IntSetting("Lowest frame rate to accept, 0 means no floor", p.videoMinFps, rev) { p.videoMinFps = it }
    IntSetting("Sharpness of apps on the car screen", p.carDensity, rev) { p.carDensity = it }
    OutlinedButton(
        onClick = {
            p.videoSize = VideoPlans.SIZE_BAIDU
            p.videoWidth = 0
            p.videoHeight = 0
            p.videoFps = 0
            p.videoMinFps = 0
            p.videoBitrate = 0
            p.videoQpFloor = DEFAULT_FLOOR
            p.carDensity = 160
            rev++
        },
        enabled = !baidu || p.carDensity != 160,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (baidu && p.carDensity == 160) "Already at the defaults" else "Put everything back to the defaults")
    }
}

private const val DEFAULT_FLOOR = 22

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
