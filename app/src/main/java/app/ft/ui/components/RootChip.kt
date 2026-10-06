package app.ft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.core.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RootGreen = Color(0xFF1E8E52)

@Composable
fun rememberRootGranted(): Boolean {
    val state by Root.stateFlow.collectAsState()
    var ticks by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) { ticks++; onPauseOrDispose { } }
    LaunchedEffect(ticks) { withContext(Dispatchers.IO) { Root.ensure() } }
    return state == Root.State.GRANTED
}

@Composable
fun RootChip() {
    if (!rememberRootGranted()) return
    var open by remember { mutableStateOf(false) }
    Surface(
        color = RootGreen,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).clickable { open = true }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
            Text("ROOT", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp)
        }
    }
    if (open) RootSheet { open = false }
}

@Composable
private fun RootSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RootGreen))
                Text("Root is on", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "Your phone is rooted and you let FT use it, so FT sets everything up by itself. Without root FT works the same, it just asks you to do these by hand.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                "Turns on Wi-Fi, Bluetooth and the hotspot for the car",
                "Gives FT the permissions it needs",
                "Sends the phone's sound to the car with no share-screen pop-up",
                "Switches on touch control and drawing over apps",
                "Keeps FT awake so it connects faster and stays connected"
            ).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("•", color = RootGreen, fontWeight = FontWeight.Bold)
                    Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
