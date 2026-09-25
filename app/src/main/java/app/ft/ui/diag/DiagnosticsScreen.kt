package app.ft.ui.diag

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import app.ft.core.DiagLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by DiagLog.entries.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    val tags = remember(entries) { entries.map { it.tag }.distinct().sorted() }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { DiagLog.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "Clear") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                tags.forEach { t -> FilterChip(selected = filter == t, onClick = { filter = if (filter == t) null else t }, label = { Text(t) }) }
            }
            val shown = if (filter == null) entries else entries.filter { it.tag == filter }
            LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(shown.asReversed()) { e ->
                    val color = when (e.level) {
                        Log.ERROR -> MaterialTheme.colorScheme.error
                        Log.WARN -> MaterialTheme.colorScheme.tertiary
                        Log.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "${fmt.format(Date(e.time))} ${e.tag.padEnd(8)} ${e.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
