package app.ft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.ui.car.AppCatalog
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ft.ui.theme.Localized
import kotlinx.coroutines.launch

data class Option(val label: String, val detail: String? = null, val icon: ImageVector? = null)

@Composable
fun OptionRow(title: String, value: String, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PickSetting(title: String, options: List<Option>, chosen: Int, modifier: Modifier = Modifier, about: String? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OptionRow(title, options.getOrNull(chosen)?.label.orEmpty(), modifier) { open = true }
    if (open) {
        OptionSheet(title, options, chosen, about = about, onDismiss = { open = false }) { index ->
            onPick(index)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionSheet(
    title: String,
    options: List<Option>,
    chosen: Int,
    about: String? = null,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(after: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Localized {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                if (about != null) {
                    Text(
                        about,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp)
                    )
                }
                Spacer(Modifier.size(12.dp))
                LazyColumn(Modifier.fillMaxWidth().selectableGroup()) {
                    itemsIndexed(options) { index, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = index == chosen, role = Role.RadioButton) { close { onPick(index) } }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = index == chosen, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            if (option.icon != null) {
                                Icon(option.icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                if (option.detail != null) {
                                    Text(option.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListSheet(title: String, onDismiss: () -> Unit, footer: (@Composable () -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.(close: (() -> Unit) -> Unit) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { after ->
        scope.launch { sheet.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Localized {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f, fill = false)) {
                    LazyColumn(Modifier.fillMaxWidth()) { content(close) }
                }
                if (footer != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) { footer() }
                }
            }
        }
    }
}

@Composable
fun AppChecklist(chosen: Set<String>, onDismiss: () -> Unit, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val apps by AppCatalog.apps.collectAsState()
    LaunchedEffect(Unit) { AppCatalog.refresh(context) }
    var picked by remember(chosen) { mutableStateOf(chosen) }
    val ours = context.packageName
    val list = remember(apps, ours) { apps.orEmpty().filter { it.pkg != ours } }
    ListSheet(
        title = stringResource(R.string.supercast_which_apps),
        onDismiss = {
            onChange(picked)
            onDismiss()
        },
        footer = {
            if (picked.isNotEmpty()) {
                TextButton(onClick = { picked = emptySet() }) { Text(stringResource(R.string.supercast_all_apps)) }
            }
        }
    ) { _ ->
        items(list, key = { it.pkg }) { app ->
            val on = app.pkg in picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = on, role = Role.Checkbox) {
                        picked = if (on) picked - app.pkg else picked + app.pkg
                    }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Spacer(Modifier.width(16.dp))
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
