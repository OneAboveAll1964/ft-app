package app.ft.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ft.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import app.ft.FTApp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.rememberCoroutineScope
import app.ft.aa.AaInstaller

@Composable
fun rememberAaServerOn(): Boolean? {
    val port = FTApp.instance.prefs.aaSelfPort
    var on by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(port) {
        val job = scope.launch {
            while (isActive) {
                on = withContext(Dispatchers.IO) { AaInstaller.serverRunning(port) }
                delay(2000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    return on
}

@Composable
fun AaStartChoice(serverOn: Boolean?, reinstalled: Boolean, reinstall: @Composable ColumnScope.() -> Unit) {
    var help by remember { mutableStateOf(false) }
    if (reinstalled) {
        OptionCard(1, "FT's own copy of Android Auto", active = true, status = "Ready") {
            Text(
                "FT installed Android Auto, so it starts by itself when the car connects. There is nothing to switch on in Android Auto.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            reinstall()
        }
        return
    }
    Text("Pick one of these two ways to start it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    val on = serverOn == true
    OptionCard(1, "Switch on its head unit server", active = on, status = when (serverOn) { true -> "On"; false -> "Off"; null -> "Checking" }, onInfo = if (on) null else ({ help = true })) {
        Text(
            "Quick to do in Android Auto's settings. Android Auto switches it off again when the phone restarts or Android Auto updates.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!on) {
            val context = LocalContext.current
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { help = true }) { Text("Show me how") }
                OutlinedButton(onClick = { AaInstaller.openSettings(context) }) { Text("Open Android Auto") }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text("or", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
    OptionCard(2, "Downgrade it through FT, once", active = false, status = null) {
        Text(
            "FT removes the Play Store update and puts the same version back as its own install. Android Auto then starts by itself every drive and needs no server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        reinstall()
    }
    if (help) ServerHelpSheet { help = false }
}

@Composable
private fun OptionCard(
    number: Int,
    title: String,
    active: Boolean,
    status: String?,
    onInfo: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (active) scheme.primary else scheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    else Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (status != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (active) scheme.primary else scheme.surfaceContainerHighest,
                        contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant
                    ) {
                        Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                }
                if (onInfo != null) {
                    IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = "How to switch on the head unit server", tint = scheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun ServerHelpSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Switch on the head unit server", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Four steps in Android Auto's own settings. FT connects to it by itself as soon as it is on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HelpStep(1, "Open Android Auto's settings") {
                FilledTonalButton(onClick = { AaInstaller.openSettings(context) }) { Text("Open Android Auto") }
            }
            HelpStep(2, "Scroll to the bottom and tap Version ten times", "Android Auto says developer mode is on. Only needed the first time.") {
                Shot(R.drawable.aa_server_version)
            }
            HelpStep(3, "Tap the three dots at the top right") {
                Shot(R.drawable.aa_server_menu)
            }
            HelpStep(4, "Tap Start head unit server") {
                Shot(R.drawable.aa_server_start)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    "Android Auto switches the server off when the phone restarts or Android Auto updates. Do steps 3 and 4 again then, or downgrade Android Auto through FT once in Settings, Android Auto, and skip this for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun HelpStep(number: Int, title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(28.dp).clip(CircleShape).border(2.dp, scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Shot(@DrawableRes id: Int) {
    Image(
        painterResource(id),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    )
}
