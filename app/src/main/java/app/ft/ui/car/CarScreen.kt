package app.ft.ui.car

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

enum class CarView { LAUNCHER, DRAWER, BROWSER, YOUTUBE, MUSIC, VIDEOS }

fun Modifier.touchZone(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { CarTouchZones.remove(key) } }
    onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        CarTouchZones.put(key, Rect(b.left.toInt() - 8, b.top.toInt() - 8, b.right.toInt() + 8, b.bottom.toInt() + 8))
    }
}

private fun sidePadding(corner: Int): PaddingValues {
    val left = corner == 0 || corner == 2
    return PaddingValues(start = if (left) 108.dp else 28.dp, end = if (left) 28.dp else 108.dp, top = 22.dp, bottom = 20.dp)
}

@Composable
fun CarScreen() {
    val style by CarStyles.current.collectAsState()
    CarTheme(style.accentColor) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { CarRoot(style) }
    }
}

@Composable
private fun CarRoot(style: CarStyle) {
    val state by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    var view by remember { mutableStateOf(CarView.LAUNCHER) }
    var nowPlaying by remember { mutableStateOf(false) }
    val drawerGrid = rememberLazyGridState()
    val pad = sidePadding(style.corner)
    LaunchedEffect(view) { DiagLog.i("Car", "car screen: ${view.name}") }
    LaunchedEffect(Unit) {
        CarLifeService.screenKeys.collect { key ->
            when {
                key == 3 -> { view = CarView.LAUNCHER; nowPlaying = false }
                view == CarView.MUSIC && nowPlaying -> nowPlaying = false
                else -> view = CarView.LAUNCHER
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        CarBackground(style)
        when {
            state.waitingForShare -> ShareWait(state.mirrorPackage, state.shareForSound)
            state.aaOverlay -> AaOverlay(aa)
            state.mirroring -> MirrorOverlay(style.corner)
            view == CarView.DRAWER -> AppDrawer(drawerGrid, pad) { view = CarView.LAUNCHER }
            view == CarView.BROWSER -> CarBrowser("https://duckduckgo.com") { view = CarView.LAUNCHER }
            view == CarView.YOUTUBE -> CarBrowser("https://m.youtube.com", "https://m.youtube.com/results?search_query=%s") { view = CarView.LAUNCHER }
            view == CarView.MUSIC -> CarMusic(pad, nowPlaying) { nowPlaying = it }
            view == CarView.VIDEOS -> CarVideos(pad)
            else -> Launcher(state, style) { v, playing ->
                nowPlaying = playing
                view = v
            }
        }
        if (state.aaOverlay || state.mirroring || view != CarView.LAUNCHER) {
            CornerPill(style.corner) {
                view = CarView.LAUNCHER
                nowPlaying = false
                CarLifeService.goHome()
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
                if (forSound) "Allow sound on your phone" else "Allow screen sharing on your phone",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                when {
                    forSound -> "The sound will play here as soon as you allow it"
                    label.isNotBlank() -> "$label will open here as soon as you allow it"
                    else -> "The app will open here as soon as you allow it"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
            Button(onClick = { CarLifeService.cancelShareWait() }) { Text(if (forSound) "Not now" else "Cancel") }
        }
    }
}

@Composable
private fun CornerPill(corner: Int, onClick: () -> Unit) {
    val align = when (corner) {
        1 -> Alignment.TopEnd
        2 -> Alignment.BottomStart
        3 -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }
    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = align) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(width = 68.dp, height = 44.dp).touchZone("pill")
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                Text("FT", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun Launcher(state: CarState, style: CarStyle, onOpen: (CarView, Boolean) -> Unit) {
    val player by CarPlayer.state.collectAsState()
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Clock(style.clock24)
            if (style.showLink) LinkChip(state)
            Spacer(Modifier.weight(1f))
            NowPlayingCard(player, onOpen = { onOpen(CarView.MUSIC, player.track != null && !player.track!!.video) })
            Button(
                onClick = { CarLifeService.startAa() },
                modifier = Modifier.fillMaxWidth().height(84.dp),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(CarIcons.Car, contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Text("Android Auto", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
        }
        TileGrid(style.tiles, Modifier.weight(0.58f).fillMaxHeight(), onOpen = onOpen)
    }
}

@Composable
private fun Clock(h24: Boolean) {
    var time by remember { mutableStateOf("") }
    var half by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(h24) {
        while (true) {
            val now = Date()
            time = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
            half = if (h24) "" else SimpleDateFormat("a", Locale.getDefault()).format(now)
            date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            delay(1000)
        }
    }
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
private fun LinkChip(state: CarState) {
    val s = state.session
    val label = when (s) {
        is CarLifeSession.State.Projecting -> "${s.via} · ${s.width}×${s.height} · ${s.fps} fps"
        is CarLifeSession.State.Negotiated -> "${s.via} · getting ready"
        is CarLifeSession.State.Linked -> "${s.via} · connected"
        else -> "not connected"
    }
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
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
        color = Color.Black.copy(alpha = 0.32f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t != null) {
                    Artwork(t.uri, if (t.video) null else albumUri(t.albumId), 72.dp, 16.dp, if (t.video) CarIcons.Movie else CarIcons.Music)
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
                        t?.title ?: "Music",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        t?.artist?.takeIf { it.isNotBlank() } ?: if (t == null) "Play something from your phone" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (t != null) {
                    MiniKey(CarIcons.Previous, 40.dp) { CarPlayer.previous() }
                    MiniKey(if (player.playing) CarIcons.Pause else CarIcons.Play, 50.dp, filled = true) { CarPlayer.toggle() }
                    MiniKey(CarIcons.Next, 40.dp) { CarPlayer.next() }
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
            if (t != null && player.durationMs > 0) {
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
private fun TileGrid(keys: List<String>, modifier: Modifier, interactive: Boolean = true, onOpen: (CarView, Boolean) -> Unit) {
    val context = LocalContext.current
    val specs = remember(keys) { keys.map { tileSpec(context, it) } }
    val columns = if (specs.size <= 3) specs.size.coerceAtLeast(1) else (specs.size + 1) / 2
    val rows = specs.chunked(columns)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { spec -> Tile(Modifier.weight(1f), spec) { if (interactive) openTile(spec.key, onOpen) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun openTile(key: String, onOpen: (CarView, Boolean) -> Unit) {
    when {
        key == "music" -> onOpen(CarView.MUSIC, false)
        key == "videos" -> onOpen(CarView.VIDEOS, false)
        key == "youtube" -> onOpen(CarView.YOUTUBE, false)
        key == "browser" -> onOpen(CarView.BROWSER, false)
        key == "apps" -> onOpen(CarView.DRAWER, false)
        key == "maps" -> CarLifeService.launchApp(FTApp.instance.prefs.mapsPackage)
        key == "aa" -> CarLifeService.startAa()
        key == "phone" -> CarLifeService.showPhone()
        key.startsWith("app:") -> CarLifeService.launchApp(key.removePrefix("app:"))
    }
}

fun tileLabel(context: android.content.Context, key: String): String = when {
    key == "music" -> "Music"
    key == "videos" -> "Videos"
    key == "youtube" -> "YouTube"
    key == "browser" -> "Browser"
    key == "apps" -> "All apps"
    key == "maps" -> "Maps"
    key == "aa" -> "Android Auto"
    key == "phone" -> "Phone screen"
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
    "aa" -> CarIcons.Car
    "phone" -> CarIcons.Phone
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
        color = Color.Black.copy(alpha = 0.3f),
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
            Text(spec.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AaOverlay(aa: app.ft.aa.AaState) {
    val prefs = FTApp.instance.prefs
    val (fullW, fullH) = app.ft.aa.AaProtocol.standardSize(prefs.aaWidth, prefs.aaHeight)
    Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(fullW, fullH)
                            AaVideoSink.attach(Surface(st), fullW, fullH)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { AaVideoSink.detach(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(fullW.toFloat() / fullH).align(Alignment.TopCenter)
        )
        AnimatedVisibility(visible = aa.phase != AaSession.Phase.STREAMING, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    Text(
                        when {
                            aa.connected -> "Connecting to ${aa.deviceName.ifBlank { "your phone" }}…"
                            aa.listening -> "Waiting for a phone on port ${aa.port}"
                            else -> "Starting Android Auto head unit…"
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
                    "Turn on FT in your phone's accessibility settings to use touch here",
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
                        PhoneKey(CarIcons.Back, "Back") { CarLifeService.navBack(); poke++ }
                        PhoneKey(Icons.Filled.Home, "Home") { CarLifeService.navHome(); poke++ }
                        PhoneKey(CarIcons.Recents, "Recent apps") { CarLifeService.navRecents(); poke++ }
                        if (rotate) PhoneKey(CarIcons.Rotate, "Rotate") { CarLifeService.rotatePhone(); poke++ }
                        PhoneKey(Icons.Filled.Notifications, "Notifications") { CarLifeService.navNotifications(); poke++ }
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
                            contentDescription = if (open) "Hide phone keys" else "Phone keys",
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
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Text("All apps", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(apps, key = { it.activityInfo.packageName }) { info -> AppCell(info, onClose) }
        }
    }
}

@Composable
private fun AppCell(info: ResolveInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val label = remember(info) { info.loadLabel(pm).toString() }
    val icon = remember(info) { runCatching { info.loadIcon(pm).toBitmap(132, 132).asImageBitmap() }.getOrNull() }
    Surface(
        onClick = { onClose(); CarLifeService.launchApp(info.activityInfo.packageName) },
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.3f),
        contentColor = Color.White,
        modifier = Modifier.height(120.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
            else Icon(CarIcons.Apps, contentDescription = null, modifier = Modifier.size(44.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CarPreview(style: CarStyle, modifier: Modifier = Modifier) {
    CarTheme(style.accentColor) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.clip(RoundedCornerShape(16.dp))) {
            CarBackground(style)
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Clock(style.clock24)
                    if (style.showLink) {
                        Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
                            Text("WiFi · 1920×720 · 30 fps", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    NowPlayingCard(CarPlayer.State(), onOpen = {}, interactive = false)
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(84.dp)) {
                        Icon(CarIcons.Car, contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Android Auto", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    }
                }
                TileGrid(style.tiles, Modifier.weight(0.58f).fillMaxHeight(), interactive = false) { _, _ -> }
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
