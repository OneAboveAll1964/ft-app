package app.ft.ui.components

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ft.R
import app.ft.aa.AaInstaller
import app.ft.ui.theme.Localized

@Composable
fun SystemReportHelp(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openDeveloperOptions: () -> Unit = {
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        }.getOrDefault(false)
        val openedDeveloper = enabled && runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess
        if (!openedDeveloper) {
            val openedInfo = runCatching {
                context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            }.isSuccess
            Toast.makeText(
                context,
                if (openedInfo) R.string.report_system_enable_developer else R.string.report_system_settings_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Localized { Text(stringResource(R.string.report_system_title)) } },
        text = {
            Localized {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.report_system_step_usb))
                    FilledTonalButton(onClick = openDeveloperOptions) {
                        Text(stringResource(R.string.report_system_open_android))
                    }
                    Text(stringResource(R.string.report_system_developer_hint))
                    Text(stringResource(R.string.report_system_intro))
                    Text(stringResource(R.string.report_system_step_aa))
                    FilledTonalButton(onClick = { AaInstaller.openSettings(context) }) {
                        Text(stringResource(R.string.aa_open_aa))
                    }
                    Text(stringResource(R.string.report_system_step_reproduce))
                    Text(stringResource(R.string.report_system_step_android))
                    FilledTonalButton(onClick = openDeveloperOptions) {
                        Text(stringResource(R.string.report_system_open_android))
                    }
                }
            }
        },
        confirmButton = {
            Localized {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.report_system_close)) }
            }
        }
    )
}
