package app.ft.ui.diag

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ft.core.DiagLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(pad: PaddingValues) {
    val entries by DiagLog.entries.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    val tags = remember(entries) { entries.map { it.tag }.distinct().sorted() }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val shown = remember(entries, filter) { if (filter == null) entries else entries.filter { it.tag == filter } }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }
    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            tags.forEach { t -> FilterChip(selected = filter == t, onClick = { filter = if (filter == t) null else t }, label = { Text(t) }) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 16.dp)
        ) {
            items(shown) { e ->
                val color = when (e.level) {
                    Log.ERROR -> MaterialTheme.colorScheme.error
                    Log.WARN -> MaterialTheme.colorScheme.tertiary
                    Log.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    "${fmt.format(Date(e.time))} ${e.tag.padEnd(8)} ${e.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
