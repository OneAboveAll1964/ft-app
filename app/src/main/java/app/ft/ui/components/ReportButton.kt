package app.ft.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ft.core.DiagReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    IconButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val app = context.applicationContext
            val report = withContext(Dispatchers.IO) { runCatching { DiagReport.build(app) }.getOrNull() }
            val uri = report?.let { withContext(Dispatchers.IO) { DiagReport.saveToDownloads(app, it) } }
            busy = false
            if (report == null || uri == null) {
                Toast.makeText(context, "Could not make the report", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, "Report saved in Downloads/FT", Toast.LENGTH_LONG).show()
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, report.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, "Send the FT report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Share, contentDescription = "Make a report for the developer")
    }
}
