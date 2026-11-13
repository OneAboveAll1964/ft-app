package app.ft.ui.cars

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ft.FTApp
import app.ft.R
import app.ft.carlife.CarLifeService
import app.ft.core.CarConnection
import app.ft.core.CarModel
import app.ft.core.CarProfile
import app.ft.core.CarProfiles
import app.ft.core.CarProtocol
import app.ft.ui.car.CarIcons
import app.ft.ui.components.Option
import app.ft.ui.components.OptionRow
import app.ft.ui.settings.SettingsPage

@Composable
fun CarsScreen(
    pad: PaddingValues,
    onOpenSettings: (SettingsPage) -> Unit,
    onConnect: () -> Unit,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val cars = FTApp.instance.cars
    val state by cars.state.collectAsStateWithLifecycle()
    val busy by CarConnection.busy.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val profile = state.profiles.firstOrNull { it.id == detailId }
    val carsList = rememberLazyListState()
    val profileList = rememberSaveable(profile?.id, saver = LazyListState.Saver) { LazyListState() }

    fun select(id: String): Boolean {
        val success = !CarConnection.isBusy && cars.select(id)
        failed = !success
        return success
    }

    BackHandler(isActive && (adding || profile != null)) {
        if (adding) adding = false else detailId = null
        editName = false
        failed = false
    }

    val contentPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = pad.calculateTopPadding() + 12.dp,
        bottom = pad.calculateBottomPadding() + 24.dp
    )
    if (adding) {
        CreateCarScreen(
            contentPadding = contentPadding,
            busy = busy,
            failed = failed,
            onBack = { adding = false; failed = false },
            onDisconnect = { CarConnection.switchOff(context) },
            onCreate = { name, model, protocol, mode ->
                val id = if (CarConnection.isBusy) null else cars.create(name, model, protocol, mode)
                failed = id == null
                if (id != null) {
                    adding = false
                    detailId = id
                }
            }
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = if (profile == null) carsList else profileList,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (profile == null) {
                item {
                    Text(stringResource(R.string.cars_about), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) item { ConnectionLock { CarConnection.switchOff(context) } }
                if (failed) item { OperationError() }
                items(state.profiles, key = { it.id }) { car ->
                    CarProfileCard(car, car.id == state.activeId) {
                        detailId = car.id
                        failed = false
                    }
                }
                item {
                    FilledTonalButton(onClick = { adding = true; failed = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        Text(stringResource(R.string.cars_add))
                    }
                }
            } else {
                val selected = profile.id == state.activeId
                item {
                    BackToCars(onClick = { detailId = null; failed = false })
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        CarIllustration(profile.model.illustration)
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(profile.displayName(), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(profile.model.title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected) SelectedCarLabel()
                        }
                    }
                }
                if (busy) item { ConnectionLock { CarConnection.switchOff(context) } }
                if (failed) item { OperationError() }
                item {
                    Text(
                        stringResource(if (selected) R.string.cars_selected_about else R.string.cars_unselected_about),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!selected) OutlinedButton(
                            onClick = { select(profile.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.cars_select)) }
                        Button(
                            onClick = {
                                if (!CarConnection.isBusy && (cars.state.value.activeId == profile.id || select(profile.id))) onConnect()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(if (selected) R.string.cars_connect else R.string.cars_select_connect))
                        }
                    }
                }
                item {
                    CarsSection(stringResource(R.string.cars_profile)) {
                        OptionRow(stringResource(R.string.cars_name), profile.displayName(), enabled = selected) { editName = true }
                        CarsChoice(
                            title = stringResource(R.string.cars_model),
                            options = CarModel.entries.map { Option(stringResource(it.title)) },
                            chosen = CarModel.entries.indexOf(profile.model),
                            enabled = selected
                        ) { index ->
                            if (cars.state.value.activeId == profile.id) {
                                failed = !cars.update(profile.id, profile.name, CarModel.entries[index])
                            }
                        }
                    }
                }
                item {
                    CarsSection(stringResource(R.string.cars_connection)) {
                        CarsChoice(
                            title = stringResource(R.string.cars_protocol),
                            options = CarProtocol.entries.map { Option(stringResource(it.title)) },
                            chosen = CarProtocol.entries.indexOf(profile.protocol),
                            enabled = selected
                        ) { index ->
                            if (cars.state.value.activeId == profile.id) CarConnection.selectProtocol(context, CarProtocol.entries[index])
                        }
                        if (profile.protocol == CarProtocol.CARLIFE) {
                            CarsChoice(
                                title = stringResource(R.string.cars_transport),
                                options = transportOptions(),
                                chosen = profile.linkMode.coerceIn(0, 2),
                                enabled = selected
                            ) { mode ->
                                if (cars.state.value.activeId == profile.id) {
                                    cars.prefsFor(profile.id)?.let { prefs ->
                                        if (prefs.linkMode != mode) {
                                            prefs.linkMode = mode
                                            if (CarLifeService.state.value.running) CarConnection.startAuto(context)
                                        }
                                    }
                                }
                            }
                        } else Text(
                            stringResource(R.string.cars_iccoa_transport),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        CarsSwitch(stringResource(R.string.cars_autoconnect), profile.autoConnect, selected) { enabled ->
                            if (cars.state.value.activeId == profile.id) cars.prefsFor(profile.id)?.autoConnect = enabled
                        }
                    }
                }
                item {
                    CarsSection(stringResource(R.string.cars_settings)) {
                        carSettingsPages.forEachIndexed { index, page ->
                            OptionRow(
                                stringResource(page.title),
                                stringResource(page.about),
                                enabled = selected
                            ) {
                                if (cars.state.value.activeId == profile.id) onOpenSettings(page)
                            }
                            if (index != carSettingsPages.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { deleteId = profile.id },
                            enabled = !selected && !busy && state.profiles.size > 1,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.cars_delete)) }
                        if (selected || state.profiles.size <= 1) Text(
                            stringResource(if (state.profiles.size <= 1) R.string.cars_delete_last else R.string.cars_delete_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (editName && profile != null && profile.id == state.activeId) {
        CarNameDialog(profile, onDismiss = { editName = false }) { name ->
            failed = !cars.update(profile.id, name, profile.model)
            editName = false
        }
    }
    state.profiles.firstOrNull { it.id == deleteId }?.let { deleting ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.cars_delete_title, deleting.displayName())) },
            text = { Text(stringResource(R.string.cars_delete_about)) },
            confirmButton = {
                TextButton(onClick = {
                    val success = !CarConnection.isBusy && cars.delete(deleting.id)
                    failed = !success
                    if (success) detailId = null
                    deleteId = null
                }, enabled = !busy && deleting.id != state.activeId) { Text(stringResource(R.string.cars_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun CarProfileCard(profile: CarProfile, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        CarIllustration(profile.model.illustration)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.displayName(), style = MaterialTheme.typography.titleLarge)
            val connection = stringResource(profile.protocol.title) + " · " + stringResource(
                if (profile.protocol == CarProtocol.ICCOA) R.string.cars_transport_wireless else transportTitle(profile.linkMode)
            )
            Text(connection, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) SelectedCarLabel()
        }
    }
}

@Composable
private fun CreateCarScreen(
    contentPadding: PaddingValues,
    busy: Boolean,
    failed: Boolean,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onCreate: (String, CarModel, CarProtocol, Int) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(CarModel.OTHER) }
    var protocol by rememberSaveable { mutableStateOf(CarProtocol.CARLIFE) }
    var mode by rememberSaveable { mutableStateOf(0) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { BackToCars(onBack) }
        item { Text(stringResource(R.string.cars_add), style = MaterialTheme.typography.headlineSmall) }
        if (busy) item { ConnectionLock(onDisconnect) }
        if (failed) item { OperationError() }
        item {
            Card(shape = RoundedCornerShape(24.dp)) { CarIllustration(model.illustration) }
        }
        item {
            CarsSection(stringResource(R.string.cars_profile)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cars_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                CarsChoice(
                    title = stringResource(R.string.cars_model),
                    options = CarModel.entries.map { Option(stringResource(it.title)) },
                    chosen = CarModel.entries.indexOf(model)
                ) { model = CarModel.entries[it] }
            }
        }
        item {
            CarsSection(stringResource(R.string.cars_connection)) {
                CarsChoice(
                    title = stringResource(R.string.cars_protocol),
                    options = CarProtocol.entries.map { Option(stringResource(it.title)) },
                    chosen = CarProtocol.entries.indexOf(protocol)
                ) { protocol = CarProtocol.entries[it] }
                if (protocol == CarProtocol.CARLIFE) {
                    CarsChoice(stringResource(R.string.cars_transport), transportOptions(), mode) { mode = it }
                } else Text(
                    stringResource(R.string.cars_iccoa_transport),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Button(onClick = { onCreate(name.trim(), model, protocol, mode) }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cars_create))
            }
        }
    }
}

@Composable
private fun CarNameDialog(profile: CarProfile, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val initialName = profile.displayName()
    var name by rememberSaveable(profile.id) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cars_edit)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cars_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.cars_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun BackToCars(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(CarIcons.Back, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        Text(stringResource(R.string.action_back))
    }
}

@Composable
private fun ConnectionLock(onDisconnect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cars_locked_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cars_locked_about), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cars_disconnect)) }
        }
    }
}

@Composable
private fun OperationError() {
    Text(stringResource(R.string.cars_operation_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun transportOptions() = (0..2).map { Option(stringResource(transportTitle(it))) }

@Composable
private fun CarProfile.displayName(): String = if (id == CarProfiles.LEGACY_ID && name == CarProfiles.DEFAULT_NAME) stringResource(R.string.cars_my_car) else name

private val carSettingsPages = listOf(SettingsPage.CAR, SettingsPage.PICTURE, SettingsPage.SOUND, SettingsPage.CAR_SCREEN, SettingsPage.ANDROID_AUTO)
