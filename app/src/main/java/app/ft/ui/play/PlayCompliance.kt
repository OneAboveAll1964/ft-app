package app.ft.ui.play

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ft.BuildConfig
import app.ft.R
import app.ft.runtimePermissions

/** Public pages the Play listing and the About screen point to. */
object PlayLinks {
    const val PRIVACY = "https://github.com/OneAboveAll1964/ft-app/blob/main/PRIVACY.md"
    const val SOURCE = "https://github.com/OneAboveAll1964/ft-app"
    const val LICENSE = "https://github.com/OneAboveAll1964/ft-app/blob/main/LICENSE"
}

enum class AccessibilityUse(@StringRes val title: Int, @StringRes val body: Int) {
    TOUCH(R.string.play_touch_title, R.string.play_touch_body),
    AA_STARTER(R.string.play_starter_title, R.string.play_starter_body)
}

/**
 * Google Play requires a separate in-app disclosure, with an explicit choice, before an
 * accessibility service that is not an accessibility tool is turned on. The GitHub build opens settings directly.
 */
@Composable
fun rememberAccessibilityConsent(use: AccessibilityUse, open: () -> Unit): () -> Unit {
    if (!BuildConfig.PLAY) return open
    var asking by rememberSaveable(use) { mutableStateOf(false) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(use.title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(use.body), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { asking = false; open() }) { Text(stringResource(R.string.play_disclosure_agree)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.play_disclosure_decline)) } }
        )
    }
    return { asking = true }
}

private val explained = listOf(
    R.string.perm_nearby to R.string.perm_nearby_why,
    R.string.perm_location to R.string.perm_location_why,
    R.string.perm_mic to R.string.perm_mic_why,
    R.string.perm_media to R.string.perm_media_why,
    R.string.perm_notifications to R.string.perm_notifications_why
)

/** Shown once in the Play build before Android's own permission prompts. */
@Composable
fun PermissionsDisclosure(onContinue: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.play_perm_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.play_perm_intro), style = MaterialTheme.typography.bodyMedium)
                explained.forEach { (title, why) -> Reason(title, why) }
                Text(stringResource(R.string.play_perm_more), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.play_perm_continue)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.play_perm_later)) } }
    )
}

private val allExplained = listOf(
    R.string.perm_nearby to R.string.perm_nearby_why,
    R.string.perm_location to R.string.perm_location_why,
    R.string.perm_mic to R.string.perm_mic_why,
    R.string.perm_media to R.string.perm_media_why,
    R.string.perm_calendar to R.string.perm_calendar_why,
    R.string.perm_notifications to R.string.perm_notifications_why,
    R.string.perm_screen to R.string.perm_screen_why,
    R.string.perm_overlay to R.string.perm_overlay_why,
    R.string.perm_settings to R.string.perm_settings_why,
    R.string.perm_battery to R.string.perm_battery_why,
    R.string.perm_accessibility to R.string.perm_accessibility_why,
    R.string.perm_startup to R.string.perm_startup_why,
    R.string.perm_network to R.string.perm_network_why,
    R.string.perm_helper to R.string.perm_helper_why
)

@Composable
private fun Reason(@StringRes title: Int, @StringRes why: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AboutSettings() {
    val context = LocalContext.current
    val info = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Text("FT", style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.about_version, info?.versionName.orEmpty(), info?.longVersionCode?.toInt() ?: 0) +
            " · " + stringResource(R.string.about_edition),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(stringResource(R.string.about_data), style = MaterialTheme.typography.bodyMedium)
    FilledTonalButton(onClick = { open(PlayLinks.PRIVACY) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_privacy)) }
    OutlinedButton(onClick = { open(PlayLinks.SOURCE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_source)) }
    OutlinedButton(onClick = { open(PlayLinks.LICENSE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_license)) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.about_permissions_title), style = MaterialTheme.typography.labelLarge)
    allExplained.forEach { (title, why) -> Reason(title, why) }
    FilledTonalButton(onClick = { ask.launch(runtimePermissions(context)) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.about_review_permissions))
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.about_trademarks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
