package app.ft.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.ft.FTApp
import app.ft.R
import app.ft.core.CarInfo
import app.ft.ui.car.AppCatalog
import app.ft.ui.car.CarIcons
import app.ft.ui.car.CarPreview
import app.ft.ui.car.CarStyle
import app.ft.ui.car.CarStyles
import app.ft.ui.car.tileIcon
import app.ft.ui.car.tileLabel
import app.ft.ui.components.ListSheet
import app.ft.ui.components.Option
import app.ft.ui.components.OptionRow
import app.ft.ui.components.PickSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAR_W = 1280f
private const val CAR_H = 480f

@Stable
class CarPreviewState(night: Boolean) {
    var night by mutableStateOf(night)
    var pillTick by mutableIntStateOf(0)
}

@Composable
fun rememberCarPreviewState(): CarPreviewState {
    val style by CarStyles.current.collectAsState()
    return remember { CarPreviewState(style.dayNight && style.night) }
}

private fun save(change: () -> Unit) {
    change()
    CarStyles.reload()
}

@Composable
fun CarPreviewHeader(preview: CarPreviewState, pinned: Boolean) {
    val style by CarStyles.current.collectAsState()
    val back by animateColorAsState(
        if (pinned) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        label = "header"
    )
    var pill by remember { mutableStateOf(false) }
    LaunchedEffect(preview.pillTick) {
        if (preview.pillTick > 0) {
            pill = true
            delay(2500)
            pill = false
        }
    }
    Column(
        Modifier.layout { measurable, constraints ->
            val side = 16.dp.roundToPx()
            val wide = constraints.copy(maxWidth = constraints.maxWidth + side * 2, minWidth = constraints.minWidth + side * 2)
            val placeable = measurable.measure(wide)
            layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
        }.background(back).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(CAR_W / CAR_H).clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            val scale = maxWidth.value / CAR_W
            Box(Modifier.requiredSize(CAR_W.dp, CAR_H.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                CarPreview(style.copy(night = style.dayNight && preview.night), Modifier.fillMaxSize(), pill = pill)
            }
        }
        if (style.dayNight) {
            val modes = listOf(R.string.look_preview_day to CarIcons.Sunny, R.string.look_preview_night to CarIcons.Moon)
            key(LocalLayoutDirection.current) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (label, icon) ->
                        SegmentedButton(
                            selected = preview.night == (i == 1),
                            onClick = { preview.night = i == 1 },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
fun CarColourSettings(preview: CarPreviewState) {
    val p = FTApp.instance.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val style by CarStyles.current.collectAsState()
    var photoFailed by remember { mutableStateOf(false) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { CarStyles.savePhoto(context, uri) }
            photoFailed = !ok
            if (ok) save { p.carBackground = CarStyle.PHOTO }
            preview.night = false
        }
    }

    Section(stringResource(R.string.look_section_colours))
    Label(stringResource(R.string.look_background))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(CarStyle.PICTURES) { (name, res) ->
            Swatch(chosen = style.background == name, onClick = { preview.night = false; save { p.carBackground = name } }) {
                Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        items(CarStyle.BACKGROUNDS) { (name, colors) ->
            Swatch(chosen = style.background == name, onClick = { preview.night = false; save { p.carBackground = name } }) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
            }
        }
        item {
            Swatch(chosen = style.background == CarStyle.PHOTO, onClick = {
                photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.look_your_photo), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
    if (photoFailed) Text(stringResource(R.string.look_photo_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

    Label(stringResource(R.string.look_colour))
    Accents(style.accent, allowSame = false) { argb -> preview.night = false; save { p.carAccent = argb } }

    Toggle(stringResource(R.string.look_daynight), style.dayNight, about = stringResource(R.string.look_daynight_about)) { on ->
        preview.night = on
        save { p.carDayNight = on }
    }
    if (style.dayNight) {
        Label(stringResource(R.string.look_night_background))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Swatch(chosen = style.nightBackground.isEmpty(), onClick = { preview.night = true; save { p.carNightBackground = "" } }) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.look_same_as_day), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }
            items(CarStyle.BACKGROUNDS) { (name, colors) ->
                Swatch(chosen = style.nightBackground == name, onClick = { preview.night = true; save { p.carNightBackground = name } }) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
                }
            }
        }
        Label(stringResource(R.string.look_night_colour))
        Accents(style.nightAccent, allowSame = true) { argb -> preview.night = true; save { p.carNightAccent = argb } }
    }
}

@Composable
fun CarHomeSettings() {
    val p = FTApp.instance.prefs
    val style by CarStyles.current.collectAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Section(stringResource(R.string.look_section_home))
    val clockOnRight = style.infoRight != rtl
    PickSetting(
        stringResource(R.string.look_clock_side),
        listOf(Option(stringResource(R.string.side_left)), Option(stringResource(R.string.side_right))),
        if (clockOnRight) 1 else 0
    ) { index -> save { p.carInfoRight = (index == 1) != rtl } }
    val columnChoices = listOf(0, 2, 3, 4)
    PickSetting(
        stringResource(R.string.look_columns),
        columnChoices.map { Option(if (it == 0) stringResource(R.string.choice_auto) else "$it") },
        columnChoices.indexOf(style.columns).coerceAtLeast(0)
    ) { index -> save { p.carColumns = columnChoices[index] } }

    Label(stringResource(R.string.look_cards))
    Toggle(stringResource(R.string.look_show_clock), style.showClock) { on -> save { p.carShowClock = on } }
    if (style.showClock) Toggle(stringResource(R.string.look_clock24), style.clock24) { on -> save { p.carClock24 = on } }
    Toggle(stringResource(R.string.look_show_link), style.showLink) { on -> save { p.carShowLink = on } }
    Toggle(stringResource(R.string.look_show_car_home), style.showCarHome) { on -> save { p.carShowCarHome = on } }
    Toggle(stringResource(R.string.look_show_music), style.showMusic) { on -> save { p.carShowMusic = on } }
    Toggle(stringResource(R.string.look_show_aa), style.showAa) { on -> save { p.carShowAa = on } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarButtonSettings() {
    val p = FTApp.instance.prefs
    val context = LocalContext.current
    val style by CarStyles.current.collectAsState()
    var picking by remember { mutableIntStateOf(-1) }
    var pickingMaps by remember { mutableStateOf(false) }
    var mapsTick by remember { mutableIntStateOf(0) }

    Section(stringResource(R.string.look_buttons))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3
    ) {
        style.tiles.forEachIndexed { i, key ->
            TileChip(key, Modifier.weight(1f)) { picking = i }
        }
        if (style.tiles.size < CarStyle.MAX_TILES) {
            Surface(
                onClick = { picking = style.tiles.size },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).height(76.dp)
            ) {
                ChipBody(label = stringResource(R.string.action_add)) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp)) }
            }
        }
        repeat((3 - (style.tiles.size + if (style.tiles.size < CarStyle.MAX_TILES) 1 else 0) % 3) % 3) {
            Box(Modifier.weight(1f))
        }
    }
    val maps = remember(mapsTick) { tileLabel(context, "app:${p.mapsPackage}") }
    OptionRow(stringResource(R.string.look_maps_app), maps) { pickingMaps = true }
    OutlinedButton(
        onClick = { save { p.carTiles = CarStyle.DEFAULT_TILES } },
        enabled = style.tiles != CarStyle.parseTiles(CarStyle.DEFAULT_TILES),
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.look_default_buttons)) }

    if (picking >= 0) {
        TilePicker(
            current = style.tiles.getOrNull(picking),
            canRemove = style.tiles.size > 1 && picking < style.tiles.size,
            onDismiss = { picking = -1 },
            onPick = { key ->
                val list = style.tiles.toMutableList()
                val at = picking
                when {
                    key == null -> if (at < list.size) list.removeAt(at)
                    at < list.size -> list[at] = key
                    else -> list += key
                }
                save { p.carTiles = list.distinct().joinToString(",") }
            }
        )
    }
    if (pickingMaps) {
        AppPicker(title = stringResource(R.string.look_maps_app), chosen = p.mapsPackage, onDismiss = { pickingMaps = false }) { pkg ->
            p.mapsPackage = pkg
            mapsTick++
            CarStyles.reload()
        }
    }
}

@Composable
fun CarWidgetSettings() {
    val p = FTApp.instance.prefs
    val context = LocalContext.current
    val style by CarStyles.current.collectAsState()
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }

    Section(stringResource(R.string.look_widgets), stringResource(R.string.look_widgets_about))
    fun widget(key: String, on: Boolean) {
        val list = style.widgets.toMutableList()
        if (on) { if (key !in list) list += key } else list.remove(key)
        save { p.carWidgets = list.joinToString(",") }
    }
    Toggle(stringResource(R.string.widget_weather), CarStyle.WIDGET_WEATHER in style.widgets) { on -> widget(CarStyle.WIDGET_WEATHER, on) }
    if (CarStyle.WIDGET_WEATHER in style.widgets) {
        PickSetting(
            stringResource(R.string.widget_temperature),
            listOf(Option(stringResource(R.string.widget_celsius)), Option(stringResource(R.string.widget_fahrenheit))),
            if (style.fahrenheit) 1 else 0
        ) { index -> save { p.weatherFahrenheit = index == 1 } }
    }
    Toggle(stringResource(R.string.widget_battery), CarStyle.WIDGET_BATTERY in style.widgets) { on -> widget(CarStyle.WIDGET_BATTERY, on) }
    Toggle(stringResource(R.string.widget_speed), CarStyle.WIDGET_SPEED in style.widgets) { on -> widget(CarStyle.WIDGET_SPEED, on) }
    if (CarStyle.WIDGET_SPEED in style.widgets) {
        PickSetting(
            stringResource(R.string.widget_speed_unit),
            listOf(Option(stringResource(R.string.widget_kmh)), Option(stringResource(R.string.widget_mph))),
            if (style.mph) 1 else 0
        ) { index -> save { p.speedMph = index == 1 } }
    }
    Toggle(stringResource(R.string.widget_calendar), CarStyle.WIDGET_CALENDAR in style.widgets) { on -> widget(CarStyle.WIDGET_CALENDAR, on) }

    val needsLocation = remember(permissionTick, style.widgets) { CarInfo.needsLocation(context) }
    val needsCalendar = remember(permissionTick, style.widgets) { CarInfo.needsCalendar(context) }
    val wantsLocation = CarStyle.WIDGET_WEATHER in style.widgets || CarStyle.WIDGET_SPEED in style.widgets
    if (wantsLocation && needsLocation) {
        Need(stringResource(R.string.widget_needs_location)) {
            permissions.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    if (CarStyle.WIDGET_CALENDAR in style.widgets && needsCalendar) {
        Need(stringResource(R.string.widget_needs_calendar)) {
            permissions.launch(arrayOf(android.Manifest.permission.READ_CALENDAR))
        }
    }
}

@Composable
fun CarCornerSettings(preview: CarPreviewState) {
    val p = FTApp.instance.prefs
    val style by CarStyles.current.collectAsState()
    Section(stringResource(R.string.corner_title), stringResource(R.string.corner_body))
    val corners = listOf(R.string.corner_top_left, R.string.corner_top_right, R.string.corner_bottom_left, R.string.corner_bottom_right)
    PickSetting(
        stringResource(R.string.corner_place),
        corners.map { Option(stringResource(it)) },
        style.corner
    ) { index ->
        save { p.aaCorner = index }
        preview.pillTick++
    }
}

@Composable
fun CarConnectSettings() {
    val p = FTApp.instance.prefs
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val chosen = remember(tick) { p.openOnConnect }
    Section(stringResource(R.string.connect_section))
    OptionRow(
        stringResource(R.string.connect_open),
        chosen.ifEmpty { null }?.let { tileLabel(context, it) } ?: stringResource(R.string.connect_open_none)
    ) { picking = true }
    if (picking) {
        TilePicker(
            current = chosen.ifEmpty { null },
            canRemove = chosen.isNotEmpty(),
            removeLabel = stringResource(R.string.connect_open_none),
            onDismiss = { picking = false },
            onPick = { key ->
                p.openOnConnect = key.orEmpty()
                tick++
            }
        )
    }
}

@Composable
private fun Section(title: String, about: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (about != null) {
        Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Accents(chosen: Int, allowSame: Boolean, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowSame) item {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(if (chosen == 0) 3.dp else 1.dp, if (chosen == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPick(0) },
                contentAlignment = Alignment.Center
            ) {
                if (chosen == 0) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.look_same_as_day), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        items(CarStyle.ACCENTS) { argb ->
            val c = Color(argb)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (chosen == argb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center
            ) {
                if (chosen == argb) Icon(Icons.Filled.Check, contentDescription = null, tint = if (c.luminance() > 0.45f) Color.Black else Color.White)
            }
        }
    }
}

@Composable
private fun TileChip(key: String, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember(key) {
        if (!key.startsWith("app:")) null
        else runCatching { context.packageManager.getApplicationIcon(key.removePrefix("app:")).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.height(76.dp)
    ) {
        ChipBody(label = tileLabel(context, key)) {
            if (app != null) Image(app, contentDescription = null, modifier = Modifier.size(24.dp))
            else Icon(tileIcon(key), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ChipBody(label: String, icon: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Need(text: String, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Toggle(label: String, initial: Boolean, about: String? = null, onChange: (Boolean) -> Unit) {
    var on by remember(initial) { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable { on = !on; onChange(on) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (about != null) Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
private fun Swatch(chosen: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = if (chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(width = 72.dp, height = 48.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (chosen) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun installedApps(): List<AppCatalog.App> {
    val context = LocalContext.current
    val apps by AppCatalog.apps.collectAsState()
    LaunchedEffect(Unit) { AppCatalog.refresh(context) }
    return apps.orEmpty()
}

@Composable
private fun TilePicker(current: String?, canRemove: Boolean, onDismiss: () -> Unit, removeLabel: String? = null, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    val apps = installedApps()
    val title = if (current == null) stringResource(R.string.look_add_button) else stringResource(R.string.look_change_button, tileLabel(context, current))
    val remove = removeLabel ?: stringResource(R.string.action_remove)
    ListSheet(
        title = title,
        onDismiss = onDismiss,
        footer = if (canRemove) {
            { TextButton(onClick = { onPick(null); onDismiss() }) { Text(remove) } }
        } else null
    ) { close ->
        items(CarStyle.BUILT_IN_TILES) { key ->
            ListItem(
                headlineContent = { Text(tileLabel(context, key)) },
                leadingContent = { Icon(tileIcon(key), contentDescription = null) },
                trailingContent = { if (key == current) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { close { onPick(key) } }.padding(horizontal = 8.dp)
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 24.dp)) }
        items(apps, key = { it.pkg }) { a ->
            AppRow(a, chosen = current == "app:${a.pkg}") { close { onPick("app:${a.pkg}") } }
        }
    }
}

@Composable
private fun AppPicker(title: String, chosen: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val apps = installedApps()
    ListSheet(title = title, onDismiss = onDismiss) { close ->
        items(apps, key = { it.pkg }) { a -> AppRow(a, chosen = a.pkg == chosen) { close { onPick(a.pkg) } } }
    }
}

@Composable
private fun AppRow(a: AppCatalog.App, chosen: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 32.dp.roundToPx() }
    val icon by produceState(AppCatalog.cachedIcon(a, px), a, px) {
        if (value == null) value = AppCatalog.icon(context, a, px)
    }
    ListItem(
        headlineContent = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            val i = icon
            if (i != null) Image(i, contentDescription = null, modifier = Modifier.size(32.dp))
            else Icon(CarIcons.Apps, contentDescription = null)
        },
        trailingContent = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp)
    )
}
