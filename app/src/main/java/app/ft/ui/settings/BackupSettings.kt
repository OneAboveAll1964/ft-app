package app.ft.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.R
import app.ft.core.Backup
import app.ft.core.DiagLog
import app.ft.core.Lang
import app.ft.core.Language
import app.ft.core.Looks
import app.ft.ui.car.CarStyles
import app.ft.ui.theme.Localized
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettings() {
    val context = LocalContext.current
    val prefs = FTApp.instance.prefs
    var showCode by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }

    fun reloadEverything() {
        Lang.init(prefs)
        Lang.set(Language.of(prefs.language))
        Looks.set(prefs.themeMode)
        app.ft.core.PhoneStyle.init(prefs)
        CarStyles.reload()
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Backup.everything(prefs).toByteArray()) }
            true
        }.getOrDefault(false)
        Toast.makeText(context, context.getString(if (ok) R.string.backup_saved else R.string.backup_failed), Toast.LENGTH_LONG).show()
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val count = if (text == null) 0 else Backup.restore(prefs, text)
        if (count > 0) reloadEverything()
        DiagLog.i("Backup", "restored $count settings from a file")
        Toast.makeText(
            context,
            if (count > 0) context.getString(R.string.backup_restored, count) else context.getString(R.string.backup_failed),
            Toast.LENGTH_LONG
        ).show()
    }

    Text(stringResource(R.string.backup_about), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { save.launch("FT-settings.json") }) { Text(stringResource(R.string.backup_save)) }
        TextButton(onClick = { open.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.backup_load)) }
    }

    Text(stringResource(R.string.backup_look_about), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { showCode = true }) { Text(stringResource(R.string.backup_show_code)) }
        TextButton(onClick = { pasting = true }) { Text(stringResource(R.string.backup_paste_code)) }
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, Backup.lookLink(prefs))
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_show_code))) }
        }) { Text(stringResource(R.string.action_share)) }
    }

    if (showCode) {
        val link = remember { Backup.lookLink(prefs) }
        val code = remember(link) { qr(link) }
        AlertDialog(
            onDismissRequest = { showCode = false },
            title = { Localized { Text(stringResource(R.string.backup_show_code)) } },
            text = {
                Localized {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (code != null) {
                            Image(code.asImageBitmap(), contentDescription = null, modifier = Modifier.size(260.dp))
                        } else {
                            Text(link, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { Localized { TextButton(onClick = { showCode = false }) { Text(stringResource(R.string.action_close)) } } }
        )
    }

    if (pasting) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Localized { Text(stringResource(R.string.backup_paste_code)) } },
            text = {
                Localized {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Localized {
                    TextButton(onClick = {
                        val count = Backup.readLookCode(prefs, typed)
                        if (count > 0) reloadEverything()
                        pasting = false
                        Toast.makeText(
                            context,
                            if (count > 0) context.getString(R.string.backup_restored, count) else context.getString(R.string.backup_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }) { Text(stringResource(R.string.action_add)) }
                }
            },
            dismissButton = { Localized { TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.action_cancel)) } } }
        )
    }
}

private fun qr(text: String): Bitmap? = runCatching {
    val size = 512
    val hints = mapOf(
        com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        com.google.zxing.EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bitmap
}.getOrNull()
