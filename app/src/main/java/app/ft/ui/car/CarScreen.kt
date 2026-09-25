package app.ft.ui.car

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.FTApp
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.aa.AaVideoSink
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.projection.MirrorSink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CarScreen() {
    val state by CarLifeService.state.collectAsState()
    val aa by AaHeadUnitService.state.collectAsState()
    var drawer by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
    ) {
        when {
            state.aaOverlay -> AaOverlay(aa)
            state.mirroring -> MirrorOverlay(state.mirrorPackage)
            drawer -> AppDrawer(onClose = { drawer = false })
            else -> Launcher(state, aa, onDrawer = { drawer = true })
        }
        if (state.aaOverlay || state.mirroring || drawer) {
            CornerPill(onClick = { drawer = false; CarLifeService.goHome() })
        }
    }
}

@Composable
private fun CornerPill(onClick: () -> Unit) {
    Box(
        Modifier
            .size(96.dp)
            .padding(14.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(width = 68.dp, height = 44.dp)
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                Text("FT", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun Launcher(state: app.ft.carlife.CarState, aa: app.ft.aa.AaState, onDrawer: () -> Unit) {
    val prefs = FTApp.instance.prefs
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(clock, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("FT", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val s = state.session
                    val label = when (s) {
                        is CarLifeSession.State.Projecting -> "${s.via} · ${s.width}×${s.height} · ${s.fps} fps"
                        is CarLifeSession.State.Negotiated -> "${s.via} · negotiated"
                        is CarLifeSession.State.Linked -> "${s.via} · linked"
                        else -> "not connected"
                    }
                    AssistChip(onClick = {}, label = { Text(label) })
                }
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                aa.connected && aa.phase == AaSession.Phase.STREAMING -> "Android Auto · ${aa.deviceName.ifBlank { "phone" }} streaming"
                                aa.connected -> "Android Auto · ${aa.phase?.name?.lowercase() ?: "connecting"}"
                                aa.listening -> "Android Auto · waiting on :${aa.port}"
                                else -> "Android Auto · off"
                            }
                        )
                    }
                )
            }
            Button(
                onClick = { CarLifeService.setAaOverlay(true) },
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text("Android Auto", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile(Modifier.weight(1f), "Maps", Icons.Filled.Place) { CarLifeService.launchApp(prefs.mapsPackage) }
                Tile(Modifier.weight(1f), "Music", Icons.Filled.Star) { CarLifeService.launchApp(prefs.musicPackage) }
                Tile(Modifier.weight(1f), "Video", Icons.Filled.PlayArrow) { CarLifeService.launchApp(prefs.videoPackage) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile(Modifier.weight(1f), "Phone", Icons.Filled.Call) { CarLifeService.launchApp(prefs.phonePackage) }
                Tile(Modifier.weight(1f), "All apps", Icons.Filled.List, onClick = onDrawer)
                Tile(Modifier.weight(1f), "Home", Icons.Filled.Home) { CarLifeService.goHome() }
            }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, icon: ImageVector, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AaOverlay(aa: app.ft.aa.AaState) {
    val prefs = FTApp.instance.prefs
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(prefs.aaWidth, prefs.aaHeight)
                            AaVideoSink.attach(Surface(st), prefs.aaWidth, prefs.aaHeight)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { AaVideoSink.detach(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
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
private fun MirrorOverlay(pkg: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { MirrorSink.attach(Surface(st)) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { MirrorSink.detach(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Text(pkg.substringAfterLast('.'), color = Color.White.copy(alpha = 0.35f), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
private fun AppDrawer(onClose: () -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }
    Column(Modifier.fillMaxSize().padding(start = 110.dp, end = 28.dp, top = 24.dp, bottom = 24.dp)) {
        Text("All apps", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(apps, key = { it.activityInfo.packageName }) { info -> AppCell(info, onClose) }
        }
    }
}

@Composable
private fun AppCell(info: ResolveInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val label = remember(info) { info.loadLabel(pm).toString() }
    val icon = remember(info) { info.loadIcon(pm) }
    ElevatedCard(
        onClick = { onClose(); CarLifeService.launchApp(info.activityInfo.packageName) },
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.height(120.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            AndroidView(factory = { ctx -> ImageView(ctx).apply { setImageDrawable(icon) } }, modifier = Modifier.size(44.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}
