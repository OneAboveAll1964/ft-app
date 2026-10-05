package app.ft.ui.settings

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.height
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.ft.ui.car.AppCatalog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.ft.FTApp
import app.ft.ui.car.CarIcons
import app.ft.ui.car.CarPreview
import app.ft.ui.car.CarStyle
import app.ft.ui.car.CarStyles
import app.ft.ui.car.tileIcon
import app.ft.ui.car.tileLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAR_W = 1280f
private const val CAR_H = 480f


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarLookSettings() {
    val p = FTApp.instance.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val style by CarStyles.current.collectAsState()
    var picking by remember { mutableStateOf(-1) }
    var pickingMaps by remember { mutableStateOf(false) }
    var photoFailed by remember { mutableStateOf(false) }

    fun save(change: () -> Unit) {
        change()
        CarStyles.reload()
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { CarStyles.savePhoto(context, uri) }
            photoFailed = !ok
            if (ok) save { p.carBackground = CarStyle.PHOTO }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(CAR_W / CAR_H).clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            val scale = maxWidth.value / CAR_W
            Box(Modifier.requiredSize(CAR_W.dp, CAR_H.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                CarPreview(style, Modifier.fillMaxSize())
            }
        }

        Label("Background")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CarStyle.PICTURES) { (name, res) ->
                Swatch(chosen = style.background == name, onClick = { save { p.carBackground = name } }) {
                    Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            items(CarStyle.BACKGROUNDS) { (name, colors) ->
                Swatch(chosen = style.background == name, onClick = { save { p.carBackground = name } }) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
                }
            }
            item {
                Swatch(chosen = style.background == CarStyle.PHOTO, onClick = {
                    photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = "Your photo", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        if (photoFailed) Text("That photo could not be used, try another one", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        Label("Colour")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CarStyle.ACCENTS) { argb ->
                val c = Color(argb)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (style.accent == argb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { save { p.carAccent = argb } },
                    contentAlignment = Alignment.Center
                ) {
                    if (style.accent == argb) Icon(Icons.Filled.Check, contentDescription = null, tint = if (c.luminance() > 0.45f) Color.Black else Color.White)
                }
            }
        }

        Label("Buttons")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = CarStyle.columns(style.tiles.size)
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
                    ChipBody(label = "Add") { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pickingMaps = true }) { Text("Maps app: ${tileLabel(context, "app:${p.mapsPackage}")}") }
            TextButton(onClick = {
                save {
                    p.carTiles = CarStyle.DEFAULT_TILES
                    p.carAccent = CarStyle.DEFAULT_ACCENT
                    p.carBackground = CarStyle.DEFAULT_BACKGROUND
                }
            }) { Text("Reset") }
        }

        Toggle("24-hour clock", style.clock24) { on -> save { p.carClock24 = on } }
        Toggle("Show connection details", style.showLink) { on -> save { p.carShowLink = on } }
        Toggle("Show the song on the car's own display", p.carSongInfo) { on -> p.carSongInfo = on }
        Toggle("Hide WhatsApp and Telegram audio in Music", p.hideChatAudio) { on -> p.hideChatAudio = on }
    }

    if (picking >= 0) {
        TilePicker(
            current = style.tiles.getOrNull(picking),
            canRemove = style.tiles.size > 1 && picking < style.tiles.size,
            onDismiss = { picking = -1 },
            onPick = { key ->
                val list = style.tiles.toMutableList()
                when {
                    key == null -> if (picking < list.size) list.removeAt(picking)
                    picking < list.size -> list[picking] = key
                    else -> list += key
                }
                save { p.carTiles = list.distinct().joinToString(",") }
                picking = -1
            }
        )
    }
    if (pickingMaps) {
        AppPicker(title = "Maps app", onDismiss = { pickingMaps = false }) { pkg ->
            p.mapsPackage = pkg
            CarStyles.reload()
            pickingMaps = false
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
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember(initial) { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
private fun TilePicker(current: String?, canRemove: Boolean, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    val apps = installedApps()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Add a button" else "Change ${tileLabel(context, current)}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(CarStyle.BUILT_IN_TILES) { key ->
                    ListItem(
                        headlineContent = { Text(tileLabel(context, key)) },
                        leadingContent = { Icon(tileIcon(key), contentDescription = null) },
                        trailingContent = { if (key == current) Icon(Icons.Filled.Check, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(key) }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                items(apps, key = { it.pkg }) { a ->
                    AppRow(a, chosen = current == "app:${a.pkg}") { onPick("app:${a.pkg}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (canRemove) TextButton(onClick = { onPick(null) }) { Text("Remove") } }
    )
}

@Composable
private fun AppPicker(title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val apps = installedApps()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(apps, key = { it.pkg }) { a -> AppRow(a, chosen = false) { onPick(a.pkg) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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
        trailingContent = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
