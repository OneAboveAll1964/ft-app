package app.ft.ui.car

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.media.CarPlayer
import app.ft.media.MediaLibrary
import app.ft.media.Track
import app.ft.media.formatTime
import kotlinx.coroutines.delay

@Composable
fun CarVideos(pad: PaddingValues) {
    val context = LocalContext.current
    val allowed = remember { canReadVideos(context) }
    val videos by produceState(MediaLibrary.lastVideos) { value = MediaLibrary.videos(context) }
    val grid = rememberLazyGridState()
    LaunchedEffect(videos, grid) {
        val list = videos ?: return@LaunchedEffect
        snapshotFlow { grid.isScrollInProgress to (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .collect { (moving, last) ->
                if (moving || last < 0 || last + 1 >= list.size) return@collect
                MediaLibrary.prefetch(context, list.subList(last + 1, minOf(list.size, last + 9)).map { it.uri }, 384)
            }
    }
    var watching by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { CarPlayer.stopVideo() } }

    if (watching) {
        VideoPlayer { watching = false; CarPlayer.stopVideo() }
        return
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Text("Videos", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
        val list = videos
        when {
            !allowed -> Empty("Allow FT to see the videos on your phone", CarIcons.Movie)
            list == null -> Unit
            list.isEmpty() -> Empty("No videos on this phone", CarIcons.Movie)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(list, key = { _, v -> v.id }) { i, v ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                CarPlayer.play(context, list, i)
                                watching = true
                            }
                            .padding(6.dp)
                    ) {
                        Box {
                            VideoThumb(v, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            Text(
                                formatTime(v.durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            v.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoThumb(v: Track, modifier: Modifier) {
    val art = rememberArt(v.uri, null, 384)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(CarIcons.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun VideoPlayer(onClose: () -> Unit) {
    val player by CarPlayer.state.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(controls, poke, player.playing) {
        if (controls && player.playing) {
            delay(3500)
            controls = false
        }
    }
    val ratio = if (player.videoWidth > 0 && player.videoHeight > 0) player.videoWidth.toFloat() / player.videoHeight else 16f / 9f
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controls = !controls
                poke++
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    var surface: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val s = Surface(st)
                            surface = s
                            CarPlayer.setVideoSurface(s)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface?.let { CarPlayer.clearVideoSurface(it) }
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.aspectRatio(ratio)
        )
        AnimatedVisibility(visible = controls || !player.playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 110.dp, end = 24.dp, top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(onClick = onClose, shape = CircleShape, color = Color.White.copy(alpha = 0.16f), contentColor = Color.White, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(CarIcons.Back, contentDescription = "Back", modifier = Modifier.size(24.dp)) }
                    }
                    Text(
                        player.track?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 14.dp)) {
                    SeekBar(player)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        RoundKey(CarIcons.Previous, 56.dp) { CarPlayer.previous(); poke++ }
                        Box(Modifier.padding(horizontal = 28.dp)) {
                            RoundKey(if (player.playing) CarIcons.Pause else CarIcons.Play, 76.dp, filled = true) { CarPlayer.toggle(); poke++ }
                        }
                        RoundKey(CarIcons.Next, 56.dp) { CarPlayer.next(); poke++ }
                    }
                }
            }
        }
    }
}
