package app.ft.ui.car

import android.graphics.SurfaceTexture
import app.ft.ui.components.InkText
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.R
import app.ft.core.DiagLog
import app.ft.supercast.SuperCast
import app.ft.ui.theme.LeftToRight
import app.ft.ui.theme.ownDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class CarPane(val name: String, val packageName: String, val label: String)

@Composable
fun SuperCastScreen(
    panes: List<CarPane>,
    apps: List<Pair<String, String>>,
    pad: PaddingValues,
    ready: Boolean,
    full: Boolean,
    dockHidden: Boolean,
    corner: Int,
    split: () -> Float,
    onSplit: (Float) -> Unit,
    onPick: (Int) -> Unit,
    onClose: (String) -> Unit,
    onSwap: () -> Unit,
    onFull: (Boolean) -> Unit,
    onDock: (Boolean) -> Unit
) {
    val tucked = dockHidden && panes.isNotEmpty()
    val layoutDirection = LocalLayoutDirection.current
    val padding = when {
        full -> PaddingValues(0.dp)
        tucked -> PaddingValues(
            start = pad.calculateStartPadding(layoutDirection),
            end = pad.calculateEndPadding(layoutDirection),
            top = pad.calculateTopPadding(),
            bottom = 4.dp
        )
        else -> pad
    }
    LeftToRight {
        Box(Modifier.fillMaxSize().background(if (full) Color.Black else Color.Transparent)) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                var live by remember { mutableStateOf<Float?>(null) }
                val shown = if (panes.size == 2) live ?: split() else 1f
                val gap = if (full) 2.dp else 10.dp
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().splitByDragging(panes.size == 2, gap, split, onLive = { live = it }, onSplit = onSplit)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    panes.forEachIndexed { index, pane ->
                        key(pane.name, pane.packageName) {
                            Pane(pane, full, live != null, Modifier.weight(if (index == 0) shown else 1f - shown).fillMaxHeight()) { onClose(pane.name) }
                        }
                    }
                    if (panes.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(if (ready) R.string.supercast_pick else R.string.supercast_not_set_up),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                if (panes.size == 2) {
                    Handle(full, gap, maxWidth, shown, live != null)
                }
                }
                if (!full) {
                    if (tucked) {
                        DockHandle { onDock(false) }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Dock(panes, apps, onPick, onSwap, onFull = { onFull(true) }, onHide = { onDock(true) })
                    }
                }
            }
            if (full) ExitFullScreen(corner) { onFull(false) }
        }
    }
}

const val SPLIT_MIN = 0.25f
const val SPLIT_MAX = 0.75f

fun settleSplit(value: Float): Float {
    val kept = value.coerceIn(SPLIT_MIN, SPLIT_MAX)
    return if (kotlin.math.abs(kept - 0.5f) < 0.04f) 0.5f else kept
}

private val GRAB = 32.dp

@Composable
private fun Modifier.splitByDragging(
    two: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    split: () -> Float,
    onLive: (Float?) -> Unit,
    onSplit: (Float) -> Unit
): Modifier {
    val density = LocalDensity.current
    val twoNow by rememberUpdatedState(two)
    val gapPx by rememberUpdatedState(with(density) { gap.toPx() })
    val splitNow by rememberUpdatedState(split)
    val live by rememberUpdatedState(onLive)
    val save by rememberUpdatedState(onSplit)
    var width by remember { mutableIntStateOf(0) }
    val lastTap = remember { longArrayOf(0L) }
    return this
        .onSizeChanged { width = it.width }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val room = width - gapPx
                if (!twoNow || room <= 0f) return@awaitEachGesture
                val from = splitNow()
                val away = abs(down.position.x - (room * from + gapPx / 2f))
                if (away > GRAB.toPx() / 2f) return@awaitEachGesture
                val onGap = away <= gapPx / 2f + 6.dp.toPx()
                var dragging = false
                var moved = from
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging) {
                            if (abs(dx) > viewConfiguration.touchSlop && abs(dx) >= abs(dy)) {
                                dragging = true
                            } else if (abs(dy) > viewConfiguration.touchSlop) {
                                break
                            }
                        }
                        if (dragging) {
                            change.consume()
                            moved = (from + dx / room).coerceIn(SPLIT_MIN, SPLIT_MAX)
                            live(moved)
                        }
                        if (!change.pressed) {
                            if (!dragging && onGap) {
                                if (change.uptimeMillis - lastTap[0] < 400L) {
                                    lastTap[0] = 0L
                                    save(0.5f)
                                } else {
                                    lastTap[0] = change.uptimeMillis
                                }
                            }
                            break
                        }
                    }
                } finally {
                    if (dragging) {
                        live(null)
                        save(settleSplit(moved))
                    }
                }
            }
        }
}

@Composable
private fun Handle(full: Boolean, gap: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, shown: Float, lit: Boolean) {
    val density = LocalDensity.current
    val center = with(density) { (width - gap).toPx() * shown + gap.toPx() / 2f }
    Box(
        Modifier
            .offset { IntOffset((center - GRAB.toPx() / 2f).roundToInt(), 0) }
            .width(GRAB)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(if (lit) 8.dp else if (full) 4.dp else 6.dp)
                .height(if (lit) 96.dp else 72.dp)
                .clip(RoundedCornerShape(50))
                .background(if (lit) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (full) 0.6f else 0.7f))
                .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
        )
    }
}

@Composable
private fun Pane(pane: CarPane, full: Boolean, resizing: Boolean, modifier: Modifier, onClose: () -> Unit) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(0 to 0) }
    val resizingNow by rememberUpdatedState(resizing)
    var waiting by remember { mutableStateOf<Triple<SurfaceTexture, Int, Int>?>(null) }
    LaunchedEffect(resizing) {
        if (!resizing) {
            waiting?.let { (texture, width, height) ->
                waiting = null
                open(pane, texture, width, height, density.density)
            }
        }
    }
    val veil by animateFloatAsState(
        if (resizing) 1f else 0f,
        if (resizing) tween(120) else tween(350, delayMillis = 300),
        label = "veil"
    )
    Box(modifier.clip(RoundedCornerShape(if (full) 0.dp else 18.dp)).background(Color.Black)) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            open(pane, texture, width, height, density.density)
                            size = width to height
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            if (resizingNow) {
                                waiting = Triple(texture, width, height)
                            } else {
                                waiting = null
                                open(pane, texture, width, height, density.density)
                            }
                            size = width to height
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            waiting = null
                            SuperCast.later {
                                SuperCast.detachPane(pane.name)
                                texture.release()
                            }
                            return false
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize().pointerInteropFilter { event ->
                val (width, height) = size
                if (width > 0 && height > 0) {
                    SuperCast.touch(pane.name, event.actionMasked, event.x, event.y, width, height)
                }
                true
            }
        )
        if (veil > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color(0xFF111A1F)).pointerInteropFilter { true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    pane.label,
                    style = MaterialTheme.typography.titleLarge.ownDirection(),
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        if (!full) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp).padding(LocalPillRow.current),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Pill(stringResource(R.string.key_back)) { SuperCast.later { SuperCast.key(pane.name, 4) } }
                Pill(stringResource(R.string.action_close), onClose)
            }
        }
    }
}

@Composable
private fun ExitFullScreen(corner: Int, onClick: () -> Unit) {
    val align = when (corner) {
        1 -> AbsoluteAlignment.TopRight
        2 -> AbsoluteAlignment.BottomLeft
        3 -> AbsoluteAlignment.BottomRight
        else -> AbsoluteAlignment.TopLeft
    }
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = align) {
        Card(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(CarIcons.FullscreenExit, contentDescription = stringResource(R.string.supercast_full_exit), modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun DockHandle(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = LocalCarCard.current,
            contentColor = Color.White
        ) {
            Row(Modifier.height(34.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CarIcons.ArrowUp, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                InkText(stringResource(R.string.supercast_show_dock), MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun open(pane: CarPane, texture: SurfaceTexture, width: Int, height: Int, density: Float) {
    if (width <= 0 || height <= 0) return
    texture.setDefaultBufferSize(width, height)
    val surface = Surface(texture)
    val dpi = (160f * density).toInt().coerceIn(120, 640)
    SuperCast.later {
        val display = try {
            SuperCast.openPane(pane.name, surface, width, height, dpi, pane.packageName)
        } finally {
            surface.release()
        }
        DiagLog.i("SuperCast", "pane ${pane.name} for ${pane.packageName} is display $display at ${width}x$height")
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White
    ) {
        InkText(text, MaterialTheme.typography.labelMedium, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun Dock(
    panes: List<CarPane>,
    apps: List<Pair<String, String>>,
    onPick: (Int) -> Unit,
    onSwap: () -> Unit,
    onFull: () -> Unit,
    onHide: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
        if (panes.isNotEmpty()) {
            DockButton(CarIcons.Fullscreen, stringResource(R.string.supercast_full), onFull)
            Spacer(Modifier.width(10.dp))
            DockButton(CarIcons.ArrowDown, stringResource(R.string.supercast_hide_dock), onHide)
            Spacer(Modifier.width(10.dp))
        }
        if (panes.size == 2) {
            DockButton(CarIcons.Swap, stringResource(R.string.supercast_swap), onSwap)
            Spacer(Modifier.width(10.dp))
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.first }) { (packageName, label) ->
                val running = panes.any { it.packageName == packageName }
                Card(
                    onClick = { onPick(apps.indexOfFirst { it.first == packageName }) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else LocalCarCard.current,
                    contentColor = Color.White
                ) {
                    Box(Modifier.width(150.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall.ownDirection(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = LocalCarCard.current,
        contentColor = Color.White
    ) {
        Column(
            Modifier.width(92.dp).fillMaxHeight().padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
