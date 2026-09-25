package app.ft.ui.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ROWS = listOf(
    "1234567890",
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm.-"
)

@Composable
fun CarKeyboard(
    onInsert: (String) -> Unit,
    onBackspace: () -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    goLabel: String = "Go",
    modifier: Modifier = Modifier
) {
    var shift by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { ch ->
                        val label = if (shift) ch.uppercaseChar() else ch
                        Key(label.toString(), Modifier.weight(1f)) { onInsert(label.toString()) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key(if (shift) "ABC" else "abc", Modifier.width(100.dp)) { shift = !shift }
                Key("/", Modifier.width(70.dp)) { onInsert("/") }
                Key("space", Modifier.weight(1f)) { onInsert(" ") }
                Key(".com", Modifier.width(100.dp)) { onInsert(".com") }
                Key("del", Modifier.width(88.dp), onClick = onBackspace)
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.width(96.dp).height(56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
                ) { Text("Close", maxLines = 1) }
                Button(
                    onClick = onGo,
                    modifier = Modifier.width(104.dp).height(56.dp),
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
                ) { Text(goLabel, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
