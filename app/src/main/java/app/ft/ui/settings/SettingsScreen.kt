package app.ft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Head unit")
            TextSetting("Car name", p.carName) { p.carName = it }
            IntSetting("Force width (0 = as requested)", p.forceWidth) { p.forceWidth = it }
            IntSetting("Force height (0 = as requested)", p.forceHeight) { p.forceHeight = it }
            IntSetting("Force fps (0 = as requested)", p.forceFps) { p.forceFps = it }

            Section("CarLife over WiFi (phone listens)")
            IntSetting("Command port", p.cmdPort) { p.cmdPort = it }
            IntSetting("Video port", p.videoPort) { p.videoPort = it }
            IntSetting("Media port", p.mediaPort) { p.mediaPort = it }
            IntSetting("TTS port", p.ttsPort) { p.ttsPort = it }
            IntSetting("VR port", p.vrPort) { p.vrPort = it }
            IntSetting("Touch port", p.touchPort) { p.touchPort = it }
            BoolSetting("Listen on WiFi at launch", p.autoStartWifi) { p.autoStartWifi = it }

            Section("Android Auto head unit")
            IntSetting("TCP port", p.aaPort) { p.aaPort = it }
            IntSetting("Video width", p.aaWidth) { p.aaWidth = it }
            IntSetting("Video height", p.aaHeight) { p.aaHeight = it }
            IntSetting("Frame rate", p.aaFps) { p.aaFps = it }
            IntSetting("Density", p.aaDensity) { p.aaDensity = it }
            BoolSetting("Start head unit at launch", p.autoStartAa) { p.autoStartAa = it }

            Section("Launcher tiles")
            TextSetting("Maps package", p.mapsPackage) { p.mapsPackage = it }
            TextSetting("Music package", p.musicPackage) { p.musicPackage = it }
            TextSetting("Video package", p.videoPackage) { p.videoPackage = it }
            TextSetting("Phone package", p.phonePackage) { p.phonePackage = it }

            Section("Developer")
            BoolSetting("USB simulator link (tcp ${p.debugMuxPort})", p.debugMuxEnabled) { p.debugMuxEnabled = it }
            IntSetting("USB simulator port", p.debugMuxPort) { p.debugMuxPort = it }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
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
