package app.ft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.R
import app.ft.supercast.Helper
import app.ft.supercast.SuperCast
import app.ft.ui.components.AppChecklist
import app.ft.ui.components.Option
import app.ft.ui.components.OptionRow
import app.ft.ui.components.PickSetting
import app.ft.ui.components.rememberRootGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SuperCastSettings() {
    val context = LocalContext.current
    val prefs = FTApp.instance.prefs
    val scope = rememberCoroutineScope()
    val root = rememberRootGranted()
    val ready by SuperCast.ready.collectAsState()
    var onCar by remember { mutableStateOf(prefs.supercastOn) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableIntStateOf(-1) }

    fun setUp() {
        failed = false
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { SuperCast.start(context.applicationContext) }
            busy = false
            failed = !ok
        }
    }

    PickSetting(
        stringResource(R.string.apps_how),
        listOf(
            Option(stringResource(R.string.apps_mirror), stringResource(R.string.apps_mirror_about)),
            Option(stringResource(R.string.apps_run), stringResource(R.string.apps_run_about))
        ),
        if (onCar) 1 else 0
    ) { index ->
        onCar = index == 1
        prefs.supercastOn = onCar
        if (onCar) setUp() else scope.launch { withContext(Dispatchers.IO) { SuperCast.stopAll() } }
    }

    if (!onCar) {
        BoolSetting(stringResource(R.string.apps_car_size), prefs.carSizedApps) { prefs.carSizedApps = it }
        Text(
            stringResource(R.string.apps_car_size_about),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Status(ready, busy)
    if (!ready && !busy) {
        FilledTonalButton(onClick = { setUp() }) { Text(stringResource(R.string.apps_setup_now)) }
    }
    if (failed && !ready) {
        Text(stringResource(R.string.apps_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    BoolSetting(stringResource(R.string.supercast_screen_off), prefs.supercastScreenOff, stringResource(R.string.supercast_screen_off_about)) { prefs.supercastScreenOff = it }
    var chosen by remember { mutableStateOf(prefs.supercastApps.split(',').filter { it.isNotBlank() }.toSet()) }
    var picking by remember { mutableStateOf(false) }
    OptionRow(
        stringResource(R.string.supercast_which_apps),
        if (chosen.isEmpty()) stringResource(R.string.supercast_all_apps) else stringResource(R.string.supercast_some_apps, chosen.size),
        detail = stringResource(R.string.supercast_which_about)
    ) { picking = true }
    if (picking) {
        AppChecklist(chosen, onDismiss = { picking = false }) { now ->
            chosen = now
            prefs.supercastApps = now.joinToString(",")
        }
    }

    Text(stringResource(R.string.supercast_ways), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Way(
        index = 0,
        open = open,
        onOpen = { open = if (open == 0) -1 else 0 },
        title = stringResource(R.string.supercast_way_root),
        body = stringResource(R.string.supercast_way_root_body),
        best = root
    )
    Way(
        index = 1,
        open = open,
        onOpen = { open = if (open == 1) -1 else 1 },
        title = stringResource(R.string.supercast_way_wireless),
        body = stringResource(R.string.supercast_way_wireless_body),
        best = !root
    )
    Way(
        index = 2,
        open = open,
        onOpen = { open = if (open == 2) -1 else 2 },
        title = stringResource(R.string.supercast_way_computer),
        body = stringResource(R.string.supercast_way_computer_body)
    ) {
        val command = remember { Helper.computerCommand(SuperCast.token) }
        Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("FT", command))
            Toast.makeText(context, context.getString(R.string.supercast_copy_command), Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.supercast_copy_command)) }
    }
}

@Composable
private fun Status(ready: Boolean, busy: Boolean) {
    val colour = when {
        ready -> MaterialTheme.colorScheme.primary
        busy -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colour)
        else Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(
                when {
                    ready -> R.string.apps_ready_now
                    busy -> R.string.apps_setting_up
                    else -> R.string.supercast_not_ready
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = colour
        )
    }
}

@Composable
private fun Way(
    index: Int,
    open: Int,
    onOpen: () -> Unit,
    title: String,
    body: String,
    best: Boolean = false,
    extra: @Composable (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (best) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            AnimatedVisibility(visible = open == index) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (extra != null) {
                        Spacer(Modifier.height(6.dp))
                        extra()
                    }
                }
            }
        }
    }
}
