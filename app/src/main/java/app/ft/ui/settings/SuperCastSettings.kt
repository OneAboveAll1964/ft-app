package app.ft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.R
import app.ft.supercast.SuperCast
import app.ft.ui.components.rememberRootGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COMPUTER_COMMAND = "adb tcpip 5555"

@Composable
fun SuperCastSettings() {
    val context = LocalContext.current
    val prefs = FTApp.instance.prefs
    val scope = rememberCoroutineScope()
    val root = rememberRootGranted()
    val ready by SuperCast.ready.collectAsState()
    var on by remember { mutableStateOf(prefs.supercastOn) }
    var busy by remember { mutableStateOf(false) }
    var open by remember { mutableIntStateOf(-1) }

    Text(stringResource(R.string.supercast_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.supercast_on), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (ready) R.string.supercast_ready else R.string.supercast_not_ready),
                style = MaterialTheme.typography.labelMedium,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = { want ->
            on = want
            prefs.supercastOn = want
            scope.launch {
                busy = true
                withContext(Dispatchers.IO) {
                    if (want) SuperCast.start(context.applicationContext) else SuperCast.stopAll()
                }
                busy = false
            }
        })
    }

    if (on) {
        BoolSetting(stringResource(R.string.supercast_screen_off), prefs.supercastScreenOff) { prefs.supercastScreenOff = it }
        BoolSetting(stringResource(R.string.supercast_tiles), prefs.supercastTiles) { prefs.supercastTiles = it }
        Text(
            stringResource(R.string.supercast_tiles_about),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!ready) {
        TextButton(onClick = {
            scope.launch {
                busy = true
                withContext(Dispatchers.IO) { SuperCast.start(context.applicationContext) }
                busy = false
            }
        }, enabled = !busy) { Text(stringResource(R.string.supercast_turn_on)) }
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
        Text(COMPUTER_COMMAND, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("FT", COMPUTER_COMMAND))
            Toast.makeText(context, context.getString(R.string.supercast_copy_command), Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.supercast_copy_command)) }
    }
    Way(
        index = 3,
        open = open,
        onOpen = { open = if (open == 3) -1 else 3 },
        title = stringResource(R.string.supercast_way_car),
        body = stringResource(R.string.supercast_way_car_body)
    )
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
