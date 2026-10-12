package app.ft.ui.car

import android.graphics.SurfaceTexture
import app.ft.ui.components.InkText
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

data class CarPane(val name: String, val packageName: String, val label: String)

@Composable
fun SuperCastScreen(
    panes: List<CarPane>,
    apps: List<Pair<String, String>>,
    pad: PaddingValues,
    ready: Boolean,
    onPick: (Int) -> Unit,
    onClose: (String) -> Unit,
    onSwap: () -> Unit
) {
    LeftToRight {
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                panes.forEachIndexed { index, pane ->
                    Pane(pane, Modifier.weight(1f).fillMaxHeight()) { onClose(pane.name) }
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
            Spacer(Modifier.height(8.dp))
            Dock(panes, apps, onPick, onSwap)
        }
    }
}

@Composable
private fun Pane(pane: CarPane, modifier: Modifier, onClose: () -> Unit) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(0 to 0) }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
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
                            open(pane, texture, width, height, density.density)
                            size = width to height
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            SuperCast.detachPane(pane.name)
                            return true
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
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp).padding(LocalPillRow.current),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill(stringResource(R.string.key_back)) { SuperCast.key(pane.name, 4) }
            Pill(stringResource(R.string.action_close), onClose)
        }
    }
}

private fun open(pane: CarPane, texture: SurfaceTexture, width: Int, height: Int, density: Float) {
    if (width <= 0 || height <= 0) return
    texture.setDefaultBufferSize(width, height)
    val surface = Surface(texture)
    val dpi = (160f * density).toInt().coerceIn(120, 640)
    val display = SuperCast.openPane(pane.name, surface, width, height, dpi, pane.packageName)
    DiagLog.i("SuperCast", "pane ${pane.name} for ${pane.packageName} is display $display")
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
private fun Dock(panes: List<CarPane>, apps: List<Pair<String, String>>, onPick: (Int) -> Unit, onSwap: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
        if (panes.size == 2) {
            Pill(stringResource(R.string.supercast_swap), onSwap)
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
