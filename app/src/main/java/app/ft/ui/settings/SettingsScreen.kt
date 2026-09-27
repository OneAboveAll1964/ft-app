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

@Composable
fun SettingsScreen(pad: PaddingValues) {
    val p = FTApp.instance.prefs
    var rev by remember { mutableIntStateOf(0) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Group("Car") {
                TextSetting("Name shown to the head unit", p.carName) { p.carName = it }
                TextSetting("WiFi Direct name (blank = any CarLife)", p.carP2pName) { p.carP2pName = it }
                TextSetting("Bluetooth name for auto-start (blank = any)", p.carBtName) { p.carBtName = it }
                TextSetting("WiFi Direct PIN (blank = push button)", p.carWpsPin) { p.carWpsPin = it }
                BoolSetting("Auto-connect at boot and on Bluetooth", p.autoConnect) { p.autoConnect = it }
            }
        }
        item {
            Group("Steering wheel") {
                BoolSetting("Swap next and previous", p.swapTrackKeys) { p.swapTrackKeys = it }
            }
        }
        item {
            Group("Sound") {
                BoolSetting("Let the car play the sound over bluetooth", p.audioOverBluetooth) { p.audioOverBluetooth = it }
                BoolSetting("Silence the phone while FT streams the sound", p.muteWhileProjecting) { p.muteWhileProjecting = it }
            }
        }
        item {
            Group("Picture quality") {
                Text(
                    "Presets set everything below. Change any one of them and it becomes Custom.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val current = presetName(p)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { preset ->
                        val chosen = current == preset.name
                        if (chosen) {
                            FilledTonalButton(onClick = { apply(p, preset); rev++ }, modifier = Modifier.weight(1f)) {
                                Text(preset.name, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        } else {
                            OutlinedButton(onClick = { apply(p, preset); rev++ }, modifier = Modifier.weight(1f)) {
                                Text(preset.name, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Text(
                    when (current) {
                        CUSTOM -> "Custom settings"
                        DEFAULT.name -> "The settings FT started with"
                        else -> "$current quality"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(
                    onClick = { apply(p, DEFAULT); rev++ },
                    enabled = current != DEFAULT.name,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (current == DEFAULT.name) "Already back to default" else "Put everything back to default")
                }
                Text("Resolution", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SIZES.take(3).forEach { size ->
                        SizeChip(size, p.forceWidth, p.forceHeight, Modifier.weight(1f)) {
                            p.forceWidth = size.w; p.forceHeight = size.h; rev++
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SIZES.drop(3).forEach { size ->
                        SizeChip(size, p.forceWidth, p.forceHeight, Modifier.weight(1f)) {
                            p.forceWidth = size.w; p.forceHeight = size.h; rev++
                        }
                    }
                }
                Text("Frame rate", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "As the car asks", 24 to "24", 30 to "30", 60 to "60").forEach { (value, label) ->
                        ValueChip(label, p.forceFps == value, Modifier.weight(1f)) { p.forceFps = value; rev++ }
                    }
                }
                Text("Picture data limit", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1_500_000 to "1.5", 3_000_000 to "3", 6_000_000 to "6", 10_000_000 to "10").forEach { (value, label) ->
                        ValueChip("$label Mbps", p.maxBitrate == value, Modifier.weight(1f)) { p.maxBitrate = value; rev++ }
                    }
                }
                IntSetting("Width, 0 means as the car asks", p.forceWidth, rev) { p.forceWidth = it }
                IntSetting("Height, 0 means as the car asks", p.forceHeight, rev) { p.forceHeight = it }
                IntSetting("Frame rate, 0 means as the car asks", p.forceFps, rev) { p.forceFps = it }
                IntSetting("Lowest frame rate to accept, 0 means no floor", p.minFps, rev) { p.minFps = it }
                IntSetting("Picture data limit in bits per second", p.maxBitrate, rev) { p.maxBitrate = it }
                IntSetting("Sharpness of the car screen", p.carDensity, rev) { p.carDensity = it }
            }
        }
        item {
            Group("CarLife ports the head unit connects to") {
                IntSetting("Command", p.cmdPort) { p.cmdPort = it }
                IntSetting("Video", p.videoPort) { p.videoPort = it }
                IntSetting("Media", p.mediaPort) { p.mediaPort = it }
                IntSetting("TTS", p.ttsPort) { p.ttsPort = it }
                IntSetting("Voice", p.vrPort) { p.vrPort = it }
                IntSetting("Touch", p.touchPort) { p.touchPort = it }
            }
        }
        item {
            Group("Android Auto head unit") {
                BoolSetting("Draw at the car's own size", p.aaMatchCar) { p.aaMatchCar = it }
                BoolSetting("Put its controls on the left", p.aaControlsLeft) { p.aaControlsLeft = it }
                IntSetting("TCP port", p.aaPort, rev) { p.aaPort = it }
                IntSetting("Video width", p.aaWidth, rev) { p.aaWidth = it }
                IntSetting("Video height", p.aaHeight, rev) { p.aaHeight = it }
                IntSetting("Frame rate", p.aaFps, rev) { p.aaFps = it }
                IntSetting("Density", p.aaDensity, rev) { p.aaDensity = it }
                BoolSetting("Start the head unit at launch", p.autoStartAa) { p.autoStartAa = it }
            }
        }
        item {
            Group("Launcher tiles") {
                TextSetting("Maps package", p.mapsPackage) { p.mapsPackage = it }
                TextSetting("Music package", p.musicPackage) { p.musicPackage = it }
                TextSetting("Video package", p.videoPackage) { p.videoPackage = it }
                TextSetting("Phone package", p.phonePackage) { p.phonePackage = it }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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

private const val CUSTOM = "Custom"

private data class Preset(val name: String, val w: Int, val h: Int, val fps: Int, val bitrate: Int, val density: Int, val minFps: Int = 0)

private data class Size(val label: String, val w: Int, val h: Int)

private val DEFAULT = Preset("Default", 0, 0, 0, 3_000_000, 160)

private val PRESETS = listOf(
    Preset("Low", 1280, 720, 24, 1_500_000, 160),
    Preset("Medium", 0, 0, 30, 3_000_000, 160),
    Preset("High", 0, 0, 60, 8_000_000, 200)
)

private val SIZES = listOf(
    Size("As the car", 0, 0),
    Size("800×480", 800, 480),
    Size("1280×720", 1280, 720),
    Size("1600×720", 1600, 720),
    Size("1920×720", 1920, 720),
    Size("1920×1080", 1920, 1080)
)

private fun matches(p: app.ft.core.Prefs, preset: Preset) =
    preset.w == p.forceWidth && preset.h == p.forceHeight && preset.fps == p.forceFps &&
        preset.bitrate == p.maxBitrate && preset.density == p.carDensity && preset.minFps == p.minFps

private fun presetName(p: app.ft.core.Prefs): String =
    (listOf(DEFAULT) + PRESETS).firstOrNull { matches(p, it) }?.name ?: CUSTOM

private fun apply(p: app.ft.core.Prefs, preset: Preset) {
    p.minFps = preset.minFps
    p.forceWidth = preset.w
    p.forceHeight = preset.h
    p.forceFps = preset.fps
    p.maxBitrate = preset.bitrate
    p.carDensity = preset.density
    p.aaFps = if (preset.fps == 0) 30 else preset.fps
    p.aaDensity = preset.density
}

@Composable
private fun SizeChip(size: Size, w: Int, h: Int, modifier: Modifier, onPick: () -> Unit) {
    ValueChip(size.label, w == size.w && h == size.h, modifier, onPick)
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
