package app.ft.ui.car

import app.ft.ui.theme.ownDirection
import app.ft.ui.components.InkText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.aa.AaVideoSink
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.carlife.CarState
import app.ft.core.DiagLog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.ft.supercast.SuperCast
import app.ft.core.CarInfo
import app.ft.core.Links
import app.ft.media.CarPlayer
import app.ft.media.MediaLibrary
import app.ft.projection.CarTouchZones
import app.ft.projection.MirrorSink
import app.ft.ui.theme.CarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.AbsoluteAlignment
import app.ft.R
import app.ft.ui.theme.Localized
import app.ft.ui.theme.LeftToRight
import java.util.Calendar

enum class CarView { LAUNCHER, DRAWER, BROWSER, YOUTUBE, MUSIC, VIDEOS, SUPERCAST }

fun Modifier.touchZone(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { CarTouchZones.remove(key) } }
    onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        CarTouchZones.put(key, Rect(b.left.toInt() - 8, b.top.toInt() - 8, b.right.toInt() + 8, b.bottom.toInt() + 8))
    }
}

val CAR_EDGE = 14.dp
val CAR_ROW = 44.dp

val PILL_SPACE = 94.dp

private fun sidePadding(corner: Int): PaddingValues =
    PaddingValues(start = 28.dp, end = 28.dp, top = CAR_EDGE, bottom = 20.dp)

@Composable
fun CarScreen() {
    val style by CarStyles.current.collectAsState()
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
        Localized {
            CarTheme(style.accentColor) {
                CompositionLocalProvider(LocalContentColor provides Color.White) { CarRoot(style) }
            }
        }
    }
}

@Composable
private fun CarRoot(style: CarStyle) {
    val state by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    var view by remember { mutableStateOf(CarView.LAUNCHER) }
    var nowPlaying by remember { mutableStateOf(false) }
    var panes by remember { mutableStateOf<List<CarPane>>(emptyList()) }
    var dockApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var castFull by remember { mutableStateOf(false) }
    var dockHidden by remember { mutableStateOf(FTApp.instance.prefs.supercastDockHidden) }
    val castFullNow = castFull && view == CarView.SUPERCAST && panes.isNotEmpty() && !state.aaOverlay && !state.mirroring && !state.waitingForShare
    val drawerGrid = rememberLazyGridState()
    val browser = remember { WebPage("https://duckduckgo.com") }
    val youtube = remember { WebPage("https://m.youtube.com", "https://m.youtube.com/results?search_query=%s") }
    DisposableEffect(Unit) {
        onDispose {
            browser.destroy()
            youtube.destroy()
        }
    }
    val pad = sidePadding(style.corner)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(style.widgets) {
        CarInfo.start(context, scope, style.widgets)
        onDispose { CarInfo.stop(context) }
    }
    val superCastReady by SuperCast.ready.collectAsState()
    val darkenPhone = FTApp.instance.prefs.supercastScreenOff
    val panesLive = view == CarView.SUPERCAST && panes.isNotEmpty() && superCastReady
    DisposableEffect(panesLive, darkenPhone) {
        if (!panesLive) return@DisposableEffect onDispose { }
        val watch = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                DiagLog.i("SuperCast", "power button pressed while apps run, keeping the phone awake and switching its screen")
                SuperCast.later {
                    SuperCast.keepAwake(true, wake = true)
                    SuperCast.powerPressed()
                }
            }
        }
        context.registerReceiver(watch, IntentFilter(Intent.ACTION_SCREEN_OFF))
        SuperCast.later {
            SuperCast.keepAwake(true, wake = true)
            SuperCast.manageScreen(darkenPhone)
        }
        onDispose {
            runCatching { context.unregisterReceiver(watch) }
            SuperCast.later {
                SuperCast.phoneScreen(true)
                SuperCast.keepAwake(false)
            }
        }
    }
    LaunchedEffect(superCastReady, view) {
        if (superCastReady && view == CarView.SUPERCAST && dockApps.isEmpty()) {
            val all = withContext(Dispatchers.IO) { SuperCast.apps() }
            val wanted = FTApp.instance.prefs.supercastApps.split(',').filter { it.isNotBlank() }.toSet()
            dockApps = if (wanted.isEmpty()) all else all.filter { it.first in wanted }.ifEmpty { all }
            DiagLog.i("SuperCast", "the helper listed ${all.size} apps, ${dockApps.size} shown on the car")
        }
    }
    LaunchedEffect(view, panes.isEmpty()) {
        if (view != CarView.SUPERCAST || panes.isEmpty()) castFull = false
    }
    LaunchedEffect(castFullNow) {
        if (view == CarView.SUPERCAST && panes.isNotEmpty()) DiagLog.i("SuperCast", if (castFullNow) "apps full screen on the car" else "apps back in their frames")
    }
    LaunchedEffect(view) {
        DiagLog.i("Car", "car screen: ${view.name}")
        if (view == CarView.YOUTUBE || view == CarView.BROWSER || view == CarView.VIDEOS || view == CarView.SUPERCAST) {
            CarLifeService.soundSoon()
        }
    }
    LaunchedEffect(nowPlaying) { if (view == CarView.MUSIC) DiagLog.i("Car", if (nowPlaying) "music: now playing" else "music: song list") }
    LaunchedEffect(Unit) {
        CarLifeService.openView.collect { key ->
            when (key) {
                "music" -> { view = CarView.MUSIC; nowPlaying = false }
                "videos" -> view = CarView.VIDEOS
                "youtube" -> view = CarView.YOUTUBE
                "browser" -> view = CarView.BROWSER
                "apps" -> view = CarView.DRAWER
                "supercast" -> view = CarView.SUPERCAST
            }
        }
    }
    LaunchedEffect(Unit) {
        CarLifeService.screenKeys.collect { key ->
            when {
                key == 3 -> { view = CarView.LAUNCHER; nowPlaying = false }
                castFull && view == CarView.SUPERCAST -> castFull = false
                view == CarView.MUSIC && nowPlaying -> nowPlaying = false
                view == CarView.BROWSER && browser.back() -> Unit
                view == CarView.YOUTUBE && youtube.back() -> Unit
                else -> view = CarView.LAUNCHER
            }
        }
    }
    CompositionLocalProvider(
        LocalCarCard provides style.cardColor,
        LocalPillRow provides pillRowInset(style.corner, PILL_SPACE - 28.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            CarBackground(style)
            when {
                state.waitingForShare -> ShareWait(state.mirrorPackage, state.shareForSound)
                state.aaOverlay -> LeftToRight { AaOverlay(aa) }
                state.mirroring -> LeftToRight { MirrorOverlay(style.corner) }
                view == CarView.DRAWER -> AppDrawer(drawerGrid, pad) { view = CarView.LAUNCHER }
                view == CarView.BROWSER -> CarBrowser(browser) { view = CarView.LAUNCHER }
                view == CarView.YOUTUBE -> CarBrowser(youtube) { view = CarView.LAUNCHER }
                view == CarView.MUSIC -> CarMusic(pad, nowPlaying) { nowPlaying = it }
                view == CarView.VIDEOS -> CarVideos(pad)
                view == CarView.SUPERCAST -> SuperCastScreen(
                    panes = panes,
                    apps = dockApps,
                    pad = pad,
                    ready = superCastReady,
                    full = castFullNow,
                    dockHidden = dockHidden,
                    corner = style.corner,
                    onPick = { index ->
                        val chosen = dockApps.getOrNull(index) ?: return@SuperCastScreen
                        val taken = panes.map { it.name }.toSet()
                        val slot = listOf("pane-0", "pane-1").firstOrNull { it !in taken }
                        panes = when {
                            panes.any { it.packageName == chosen.first } -> panes
                            slot != null -> panes + CarPane(slot, chosen.first, chosen.second)
                            else -> listOf(panes.first(), CarPane(panes.last().name, chosen.first, chosen.second))
                        }
                    },
                    onClose = { name ->
                        SuperCast.later { SuperCast.closePane(name) }
                        panes = panes.filterNot { it.name == name }
                    },
                    onSwap = {
                        if (panes.size == 2) {
                            val (first, second) = panes[0].name to panes[1].name
                            SuperCast.later { SuperCast.swap(first, second) }
                            panes = listOf(panes[1], panes[0])
                        }
                    },
                    onFull = { castFull = it },
                    onDock = { hidden ->
                        dockHidden = hidden
                        FTApp.instance.prefs.supercastDockHidden = hidden
                        DiagLog.i("SuperCast", if (hidden) "app row on the car hidden" else "app row on the car shown")
                    }
                )
                else -> Launcher(state, style, onApp = { packageName ->
                    val label = runCatching {
                        val manager = context.packageManager
                        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
                    }.getOrDefault(packageName)
                    panes = listOf(CarPane("pane-0", packageName, label))
                    view = CarView.SUPERCAST
                    true
                }) { v, playing ->
                    nowPlaying = playing
                    view = v
                }
            }
            AnimatedVisibility(
                visible = state.notice != 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp, start = 120.dp, end = 120.dp)
            ) {
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.82f), contentColor = Color.White) {
                    if (state.notice != 0) Text(stringResource(state.notice), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
            if ((state.aaOverlay || state.mirroring || view != CarView.LAUNCHER) && !castFullNow) {
                CornerPill(style.corner) {
                    view = CarView.LAUNCHER
                    nowPlaying = false
                    CarLifeService.goHome()
                }
            }
        }
    }
}

@Composable
private fun ShareWait(pkg: String, forSound: Boolean) {
    val context = LocalContext.current
    val label = remember(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LoadingIndicator()
            Text(
                stringResource(if (forSound) R.string.car_allow_sound else R.string.car_allow_share),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                when {
                    forSound -> stringResource(R.string.car_sound_soon)
                    label.isNotBlank() -> stringResource(R.string.car_app_soon, label)
                    else -> stringResource(R.string.car_any_app_soon)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
            Button(onClick = { CarLifeService.cancelShareWait() }) { Text(stringResource(if (forSound) R.string.action_not_now else R.string.action_cancel)) }
        }
    }
}

@Composable
private fun CornerPill(corner: Int, onClick: () -> Unit) {
    val align = when (corner) {
        1 -> AbsoluteAlignment.TopRight
        2 -> AbsoluteAlignment.BottomLeft
        3 -> AbsoluteAlignment.BottomRight
        else -> AbsoluteAlignment.TopLeft
    }
    Box(Modifier.fillMaxSize().padding(CAR_EDGE), contentAlignment = align) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(width = 68.dp, height = CAR_ROW).touchZone("pill")
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                Text("FT", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun Launcher(state: CarState, style: CarStyle, preview: Boolean = false, onApp: (String) -> Boolean = { false }, onOpen: (CarView, Boolean) -> Unit = { _, _ -> }) {
    val player by CarPlayer.state.collectAsState()
    val context = LocalContext.current
    val hasInfo = style.showClock || style.showLink || style.showCarHome || style.showMusic || style.showAa || style.widgets.isNotEmpty()
    val info: @Composable ColumnScope.() -> Unit = {
        if (style.showClock) Clock(style.clock24)
        if (style.showLink) LinkChip(state, preview)
        if (style.showCarHome) CarHomeButton { if (!preview) CarLifeService.goToCarHome() }
        CarWidgets(style, preview = preview)
        Spacer(Modifier.weight(1f))
        if (style.showMusic) {
            NowPlayingCard(player, onOpen = { if (!preview) onOpen(CarView.MUSIC, player.track != null && !player.track!!.video) }, interactive = !preview)
        }
        if (style.showAa) {
            Button(
                onClick = { if (!preview) CarLifeService.startAa() },
                modifier = Modifier.fillMaxWidth().height(84.dp),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(CarIcons.AndroidAuto, contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Text("Android Auto", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (hasInfo && !style.infoRight) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp), content = info)
            }
            TileGrid(style.tiles, Modifier.weight(if (hasInfo) 0.58f else 1f).fillMaxHeight(), interactive = !preview, columns = style.columns, onApp = onApp, onOpen = onOpen)
            if (hasInfo && style.infoRight) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp), content = info)
            }
        }
        Text(
            "OneAboveAll",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(AbsoluteAlignment.BottomRight)
                .padding(PaddingValues.Absolute(right = 20.dp, bottom = 2.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !preview) { Links.openOnPhone(context, Links.GITHUB) }
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun Clock(h24: Boolean) {
    val res = LocalContext.current.resources
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(1000)
        }
    }
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val minute = now.get(Calendar.MINUTE)
    val time = if (h24) String.format(Locale.US, "%02d:%02d", hour, minute) else String.format(Locale.US, "%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute)
    val half = if (h24) "" else res.getString(if (hour < 12) R.string.car_am else R.string.car_pm)
    val date = res.getString(
        R.string.car_date,
        res.getStringArray(R.array.car_days)[now.get(Calendar.DAY_OF_WEEK) - 1],
        now.get(Calendar.DAY_OF_MONTH).toString(),
        res.getStringArray(R.array.car_months)[now.get(Calendar.MONTH)]
    )
    Column {
        Row {
            Text(time, style = MaterialTheme.typography.displayLarge, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
            if (half.isNotEmpty()) {
                Text(half, style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.alignByBaseline().padding(start = 8.dp))
            }
        }
        Text(date, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun CarHomeButton(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
        Row(Modifier.padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CarIcons.Back, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.settings_car_screen_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LinkChip(state: CarState, preview: Boolean = false) {
    val s = state.session
    val label = when {
        s is CarLifeSession.State.Projecting -> stringResource(R.string.link_projecting, s.via, s.streamWidth, s.streamHeight, s.fps)
        preview -> stringResource(R.string.link_projecting, "WiFi", 1920, 720, 30)
        s is CarLifeSession.State.Negotiated -> stringResource(R.string.link_getting_ready, s.via)
        s is CarLifeSession.State.Linked -> stringResource(R.string.link_connected, s.via)
        else -> stringResource(R.string.link_not_connected)
    }
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
        InkText(label, MaterialTheme.typography.labelLarge, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun NowPlayingCard(player: CarPlayer.State, onOpen: () -> Unit, interactive: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = player.track
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(26.dp),
        color = LocalCarCard.current,
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t != null) {
                    Artwork(MediaLibrary.artUri(t), t.uri, 72.dp, 16.dp, if (t.video) CarIcons.Movie else CarIcons.Music)
                } else {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(CarIcons.Music, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        t?.title ?: stringResource(R.string.tile_music),
                        style = MaterialTheme.typography.titleMedium.ownDirection(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        t?.artist?.takeIf { it.isNotBlank() } ?: if (t == null) stringResource(R.string.car_play_something) else "",
                        style = MaterialTheme.typography.bodyMedium.ownDirection(),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (t != null) {
                    LeftToRight {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniKey(CarIcons.Previous, 40.dp) { CarPlayer.previous() }
                            MiniKey(if (player.playing) CarIcons.Pause else CarIcons.Play, 50.dp, filled = true) { CarPlayer.toggle() }
                            MiniKey(CarIcons.Next, 40.dp) { CarPlayer.next() }
                        }
                    }
                } else {
                    MiniKey(CarIcons.Shuffle, 50.dp, filled = true) {
                        if (interactive) scope.launch {
                            val songs = MediaLibrary.songs(context)
                            if (songs.isEmpty()) onOpen() else {
                                CarPlayer.play(context, songs.shuffled(), 0)
                                CarPlayer.setShuffle(true)
                            }
                        }
                    }
                }
            }
            if (t != null && player.durationMs > 0) LeftToRight {
                LinearProgressIndicator(
                    progress = { (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    drawStopIndicator = {}
                )
            }
        }
    }
}

@Composable
private fun MiniKey(icon: ImageVector, size: androidx.compose.ui.unit.Dp, filled: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else Color.White,
        modifier = Modifier.padding(horizontal = 2.dp).size(size)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f)) }
    }
}

private data class TileSpec(val key: String, val label: String, val icon: ImageVector?, val app: ImageBitmap?)

@Composable
private fun TileGrid(keys: List<String>, modifier: Modifier, columns: Int = 0, interactive: Boolean = true, onApp: (String) -> Boolean = { false }, onOpen: (CarView, Boolean) -> Unit) {
    val context = LocalContext.current
    val specs = remember(keys, context) { keys.map { tileSpec(context, it) } }
    val chosen = columns > 0
    val columns = if (chosen) columns.coerceAtMost(specs.size.coerceAtLeast(1)) else CarStyle.columns(specs.size)
    val rows = specs.chunked(columns)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            val spare = columns - row.size
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEachIndexed { index, spec ->
                    val wide = !chosen && spare > 0 && index == row.lastIndex
                    Tile(Modifier.weight(if (wide) 1f + spare else 1f), spec) { if (interactive) openTile(spec.key, onOpen, onApp) }
                }
                if (chosen) repeat(spare) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun openTile(key: String, onOpen: (CarView, Boolean) -> Unit, onApp: (String) -> Boolean = { false }) {
    val prefs = FTApp.instance.prefs
    if (prefs.supercastOn && SuperCast.ready.value) {
        val packageName = when {
            key == "maps" -> prefs.mapsPackage
            key == "youtube" -> "com.google.android.youtube"
            key.startsWith("app:") -> key.removePrefix("app:")
            else -> null
        }
        if (packageName != null && onApp(packageName)) return
    }
    when {
        key == "music" -> onOpen(CarView.MUSIC, false)
        key == "videos" -> onOpen(CarView.VIDEOS, false)
        key == "youtube" -> onOpen(CarView.YOUTUBE, false)
        key == "browser" -> onOpen(CarView.BROWSER, false)
        key == "apps" -> onOpen(CarView.DRAWER, false)
        key == "supercast" -> onOpen(CarView.SUPERCAST, false)
        key == "maps" -> CarLifeService.launchApp(FTApp.instance.prefs.mapsPackage)
        key == "aa" -> CarLifeService.startAa()
        key == "phone" -> CarLifeService.showPhone()
        key.startsWith("app:") -> CarLifeService.launchApp(key.removePrefix("app:"))
    }
}

fun tileLabel(context: android.content.Context, key: String): String = when {
    key == "music" -> context.getString(R.string.tile_music)
    key == "videos" -> context.getString(R.string.tile_videos)
    key == "youtube" -> context.getString(R.string.tile_youtube)
    key == "browser" -> context.getString(R.string.tile_browser)
    key == "apps" -> context.getString(R.string.tile_apps)
    key == "maps" -> context.getString(R.string.tile_maps)
    key == "aa" -> context.getString(R.string.tile_aa)
    key == "phone" -> context.getString(R.string.tile_phone)
    key == "supercast" -> context.getString(R.string.tile_supercast)
    key.startsWith("app:") -> runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(key.removePrefix("app:"), 0)).toString()
    }.getOrDefault(key.removePrefix("app:").substringAfterLast('.'))
    else -> key
}

fun tileIcon(key: String): ImageVector = when (key) {
    "music" -> CarIcons.Music
    "videos" -> CarIcons.Movie
    "youtube" -> CarIcons.Video
    "browser" -> CarIcons.Web
    "apps" -> CarIcons.Apps
    "maps" -> CarIcons.Map
    "aa" -> CarIcons.AndroidAuto
    "phone" -> CarIcons.Phone
    "supercast" -> CarIcons.Apps
    else -> CarIcons.Apps
}

private fun tileSpec(context: android.content.Context, key: String): TileSpec {
    val app = if (key.startsWith("app:")) {
        runCatching { context.packageManager.getApplicationIcon(key.removePrefix("app:")).toBitmap(144, 144).asImageBitmap() }.getOrNull()
    } else null
    return TileSpec(key, tileLabel(context, key), if (app == null) tileIcon(key) else null, app)
}

@Composable
private fun Tile(modifier: Modifier, spec: TileSpec, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(28.dp),
        color = LocalCarCard.current,
        contentColor = Color.White
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (spec.app != null) Color.Transparent else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (spec.app != null) Image(spec.app, contentDescription = null, modifier = Modifier.fillMaxSize())
                else Icon(spec.icon ?: CarIcons.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Text(spec.label, style = MaterialTheme.typography.titleLarge.ownDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AaOverlay(aa: app.ft.aa.AaState) {
    val prefs = FTApp.instance.prefs
    val (fullW, fullH) = app.ft.aa.AaProtocol.standardSize(prefs.aaWidth, prefs.aaHeight)
    val real by AaVideoSink.frame.collectAsState()
    val (frameW, frameH) = real ?: (fullW to fullH)
    Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        LaunchedEffect(Unit) {
            delay(3000)
            if (AaVideoSink.surface.value == null) DiagLog.w("Car", "Android Auto is on the car but its picture still has no place to be drawn")
        }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFixedSize(fullW, fullH)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = AaVideoSink.attach(h.surface, fullW, fullH)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = AaVideoSink.detach(h.surface)
                    })
                }
            },
            update = { v -> v.holder.setFixedSize(frameW, frameH) },
            modifier = Modifier.fillMaxWidth().aspectRatio(frameW.toFloat() / frameH).align(Alignment.TopCenter)
        )
        AnimatedVisibility(visible = aa.phase != AaSession.Phase.STREAMING, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    Text(
                        when {
                            aa.connected -> stringResource(R.string.car_aa_connecting, aa.deviceName.ifBlank { stringResource(R.string.car_aa_your_phone) })
                            aa.listening -> stringResource(R.string.car_aa_waiting, aa.port)
                            else -> stringResource(R.string.car_aa_starting)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorOverlay(corner: Int) {
    val frame by MirrorSink.frame.collectAsState()
    val touch by produceState(FTTouchService.enabled) {
        while (true) {
            value = FTTouchService.enabled
            delay(2000)
        }
    }
    val ratio = if (frame.width > 0 && frame.height > 0) frame.width.toFloat() / frame.height else 16f / 9f
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        key(frame.width, frame.height, frame.own) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        if (frame.width > 0 && frame.height > 0) holder.setFixedSize(frame.width, frame.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = MirrorSink.attach(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) = Unit
                            override fun surfaceDestroyed(h: SurfaceHolder) = MirrorSink.gone(h.surface)
                        })
                    }
                },
                modifier = Modifier.aspectRatio(ratio)
            )
        }
        if (!touch) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.75f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
            ) {
                Text(
                    stringResource(R.string.car_touch_needs_accessibility),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        val align = when (corner) {
            1 -> Alignment.BottomEnd
            2 -> Alignment.TopStart
            3 -> Alignment.TopEnd
            else -> Alignment.BottomStart
        }
        PhoneKeys(left = corner == 0 || corner == 2, rotate = !frame.own, modifier = Modifier.align(align).padding(14.dp))
    }
}

@Composable
private fun PhoneKeys(left: Boolean, rotate: Boolean, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, poke) {
        if (open) {
            if (poke == 0) DiagLog.i("Car", "phone keys shown")
            delay(6000)
            open = false
            poke = 0
        }
    }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "keys")
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF14181C).copy(alpha = 0.9f),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = modifier.touchZone("phone-keys")
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val keys: @Composable () -> Unit = {
                AnimatedVisibility(
                    visible = open,
                    enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow), expandFrom = if (left) Alignment.Start else Alignment.End) + fadeIn(),
                    exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMedium), shrinkTowards = if (left) Alignment.Start else Alignment.End) + fadeOut()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                        PhoneKey(CarIcons.Back, stringResource(R.string.key_back)) { CarLifeService.navBack(); poke++ }
                        PhoneKey(Icons.Filled.Home, stringResource(R.string.key_home)) { CarLifeService.navHome(); poke++ }
                        PhoneKey(CarIcons.Recents, stringResource(R.string.key_recents)) { CarLifeService.navRecents(); poke++ }
                        if (rotate) PhoneKey(CarIcons.Rotate, stringResource(R.string.key_rotate)) { CarLifeService.rotatePhone(); poke++ }
                        PhoneKey(Icons.Filled.Notifications, stringResource(R.string.key_notifications)) { CarLifeService.navNotifications(); poke++ }
                    }
                }
            }
            val handle: @Composable () -> Unit = {
                Surface(
                    onClick = { open = !open },
                    shape = CircleShape,
                    color = if (open) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                    contentColor = if (open) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            CarIcons.Chevron,
                            contentDescription = stringResource(if (open) R.string.keys_hide else R.string.keys_show),
                            modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = turn + if (left) 180f else 0f }
                        )
                    }
                }
            }
            if (left) {
                handle()
                keys()
            } else {
                keys()
                handle()
            }
        }
    }
}

@Composable
private fun PhoneKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White.copy(alpha = 0.08f), contentColor = Color.White, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun AppDrawer(gridState: LazyGridState, pad: PaddingValues, onClose: () -> Unit) {
    val context = LocalContext.current
    val apps by AppCatalog.apps.collectAsState()
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    LaunchedEffect(Unit) {
        AppCatalog.refresh(context)
        AppCatalog.apps.value?.let { AppCatalog.warm(context, it.take(30), px) }
    }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().height(CAR_ROW).padding(LocalPillRow.current), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tile_apps), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list, key = { it.pkg }) { app -> AppCell(app, px, onClose) }
        }
    }
}

@Composable
private fun AppCell(app: AppCatalog.App, px: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState(AppCatalog.cachedIcon(app, px), app, px) {
        if (value == null) value = AppCatalog.icon(context, app, px)
    }
    Surface(
        onClick = { onClose(); CarLifeService.launchApp(app.pkg) },
        shape = RoundedCornerShape(22.dp),
        color = LocalCarCard.current,
        contentColor = Color.White,
        modifier = Modifier.height(120.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            val i = icon
            if (i != null) Image(i, contentDescription = null, modifier = Modifier.size(44.dp))
            else Icon(CarIcons.Apps, contentDescription = null, modifier = Modifier.size(44.dp))
            Text(app.label, style = MaterialTheme.typography.titleMedium.ownDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CarPreview(style: CarStyle, modifier: Modifier = Modifier, pill: Boolean = false) {
    val state by CarLifeService.state.collectAsState()
    CarTheme(style.accentColor) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalCarCard provides style.cardColor,
            LocalPillRow provides pillRowInset(style.corner, PILL_SPACE - 28.dp)
        ) {
            Box(modifier.clip(RoundedCornerShape(16.dp))) {
                CarBackground(style)
                Launcher(state, style, preview = true)
                AnimatedVisibility(visible = pill, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
                    CornerPill(style.corner) {}
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                )
            }
        }
    }
}
