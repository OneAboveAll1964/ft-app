package app.ft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ft.FTApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = FTApp.instance.prefs
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Group("Car") {
                    TextSetting("Car name shown to the head unit", p.carName) { p.carName = it }
                    TextSetting("Car WiFi Direct name (blank matches CarLife)", p.carP2pName) { p.carP2pName = it }
                    TextSetting("Car Bluetooth name for auto-start (blank = any)", p.carBtName) { p.carBtName = it }
                    TextSetting("WiFi Direct PIN (blank = push button)", p.carWpsPin) { p.carWpsPin = it }
                    BoolSetting("Auto-connect at boot and on Bluetooth", p.autoConnect) { p.autoConnect = it }
                }
            }
            item {
                Group("Projection") {
                    IntSetting("Force width (0 = as the head unit asks)", p.forceWidth) { p.forceWidth = it }
                    IntSetting("Force height (0 = as the head unit asks)", p.forceHeight) { p.forceHeight = it }
                    IntSetting("Force frame rate (0 = as the head unit asks)", p.forceFps) { p.forceFps = it }
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
                    IntSetting("TCP port", p.aaPort) { p.aaPort = it }
                    IntSetting("Video width", p.aaWidth) { p.aaWidth = it }
                    IntSetting("Video height", p.aaHeight) { p.aaHeight = it }
                    IntSetting("Frame rate", p.aaFps) { p.aaFps = it }
                    IntSetting("Density", p.aaDensity) { p.aaDensity = it }
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
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun IntSetting(label: String, initial: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}
