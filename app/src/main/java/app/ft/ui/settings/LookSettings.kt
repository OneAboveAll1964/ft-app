package app.ft.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import app.ft.FtTile
import app.ft.R
import app.ft.core.Lang
import app.ft.core.Language
import app.ft.core.Looks

@Composable
fun LookSettings() {
    val modern by app.ft.core.PhoneStyle.modern.collectAsState()
    app.ft.ui.components.PickSetting(
        stringResource(R.string.look_phone_style),
        listOf(
            app.ft.ui.components.Option(stringResource(R.string.look_phone_classic)),
            app.ft.ui.components.Option(stringResource(R.string.look_phone_modern))
        ),
        if (modern) 1 else 0
    ) { app.ft.core.PhoneStyle.setModern(it == 1) }
    val language by Lang.current.collectAsState()
    val mode by Looks.mode.collectAsState()
    Text(stringResource(R.string.look_language), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Language.entries.forEach { l ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = l == language, role = Role.RadioButton) { Lang.set(l) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = l == language, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(name(l), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text(stringResource(R.string.look_theme), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val modes = listOf(Looks.AUTO to R.string.look_theme_auto, Looks.LIGHT to R.string.look_theme_light, Looks.DARK to R.string.look_theme_dark)
    key(LocalLayoutDirection.current) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { Looks.set(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
    Text(
        stringResource(R.string.look_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current
        Text(stringResource(R.string.tile_add), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.tile_add_about),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(onClick = { FtTile.offer(context) }) { Text(stringResource(R.string.tile_add_do)) }
    }
}

@Composable
private fun name(l: Language): String = when (l) {
    Language.SYSTEM -> stringResource(R.string.look_language_system)
    Language.ENGLISH -> "English"
    Language.ARABIC -> "العربية"
    Language.SORANI -> "کوردی (سۆرانی)"
    Language.BADINI -> "کوردی (بادینی)"
    Language.RUSSIAN -> "Русский"
}
