package app.ft.ui.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
    value: String,
    onValue: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shift by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { ch ->
                        val label = if (shift) ch.uppercaseChar() else ch
                        Key(label.toString(), Modifier.weight(1f)) { onValue(value + label) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key(if (shift) "ABC" else "abc", Modifier.width(110.dp)) { shift = !shift }
                Key("/", Modifier.width(78.dp)) { onValue("$value/") }
                Key("space", Modifier.weight(1f)) { onValue("$value ") }
                Key(".com", Modifier.width(110.dp)) { onValue("$value.com") }
                Key("del", Modifier.width(96.dp)) { if (value.isNotEmpty()) onValue(value.dropLast(1)) }
                Button(
                    onClick = onGo,
                    modifier = Modifier.width(120.dp).height(56.dp),
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Go") }
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
