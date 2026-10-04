package app.ft.ui.car

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.SliderDefaults
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.ft.media.Album
import app.ft.media.CarPlayer
import app.ft.media.MediaLibrary
import app.ft.media.Track
import app.ft.media.formatTime

private enum class MusicTab { SONGS, ALBUMS }

@Composable
fun CarMusic(pad: PaddingValues, showPlaying: Boolean, onPlaying: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player by CarPlayer.state.collectAsState()
    var tab by remember { mutableStateOf(MusicTab.SONGS) }
    var album by remember { mutableStateOf<Album?>(null) }
    val allowed = remember { canReadMusic(context) }
    val songs by produceState<List<Track>?>(null) { value = MediaLibrary.songs(context) }
    val albums by produceState<List<Album>?>(null, songs) {
        value = songs?.let { list -> MediaLibrary.albums(context, list.map { it.albumId }.toSet()) }
    }

    if (showPlaying && player.track != null) {
        NowPlaying(pad, player) { onPlaying(false) }
        return
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Music", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = tab == MusicTab.SONGS && album == null, onClick = { tab = MusicTab.SONGS; album = null }, label = { Text("Songs") })
            FilterChip(selected = tab == MusicTab.ALBUMS || album != null, onClick = { tab = MusicTab.ALBUMS; album = null }, label = { Text("Albums") })
            Spacer(Modifier.weight(1f))
            val list = songs.orEmpty()
            if (list.isNotEmpty()) {
                FilledTonalButton(onClick = {
                    CarPlayer.play(context, list.shuffled(), 0)
                    CarPlayer.setShuffle(true)
                    onPlaying(true)
                }) {
                    Icon(CarIcons.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Shuffle all", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (player.track != null) {
                FilledTonalButton(onClick = { onPlaying(true) }) {
                    Icon(CarIcons.Music, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Playing", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            !allowed -> Empty("Allow FT to see the music on your phone", CarIcons.Music)
            songs == null -> Unit
            album != null -> {
                val a = album!!
                val inAlbum = songs.orEmpty().filter { it.albumId == a.id }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Surface(onClick = { album = null }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(CarIcons.Back, contentDescription = "Back", modifier = Modifier.size(22.dp)) }
                    }
                    Artwork(a.uri, null, 56.dp, 12.dp, CarIcons.Album, Modifier.padding(start = 12.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(a.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                SongList(inAlbum, player.track) { i -> CarPlayer.play(context, inAlbum, i); onPlaying(true) }
            }
            tab == MusicTab.ALBUMS -> {
                val list = albums.orEmpty()
                if (list.isEmpty()) Empty("No albums on this phone", CarIcons.Album) else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(list, key = { it.id }) { a ->
                            Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { album = a }.padding(6.dp)) {
                                Artwork(a.uri, null, null, 16.dp, CarIcons.Album, Modifier.fillMaxWidth().aspectRatio(1f))
                                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text(a.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> {
                val list = songs.orEmpty()
                if (list.isEmpty()) Empty("No music on this phone", CarIcons.Music)
                else SongList(list, player.track) { i -> CarPlayer.play(context, list, i); onPlaying(true) }
            }
        }
    }
}

@Composable
private fun SongList(list: List<Track>, current: Track?, onPlay: (Int) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            val now = current?.id == t.id && !current.video
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (now) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable { onPlay(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(t.uri, albumUri(t.albumId), 52.dp, 10.dp, CarIcons.Music)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NowPlaying(pad: PaddingValues, player: CarPlayer.State, onClose: () -> Unit) {
    val t = player.track ?: return
    val art = rememberArt(t.uri, albumUri(t.albumId), 512)
    Box(Modifier.fillMaxSize()) {
        art?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.45f))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
        val side = minOf(maxHeight - 16.dp, maxWidth * 0.42f)
        val gap = if (maxWidth < 700.dp) 20.dp else 36.dp
        val big = minOf(92.dp, (maxWidth - side - gap) * 0.24f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(side).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(CarIcons.Music, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(96.dp))
            }
            Column(Modifier.weight(1f).padding(start = gap), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    onClick = onClose,
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CarIcons.Back, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text("All songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(t.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                Text(t.artist, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.8f))
                if (t.album.isNotBlank()) Text(t.album, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))
                SeekBar(player)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    RoundKey(CarIcons.Shuffle, big * 0.6f, active = player.shuffle) { CarPlayer.setShuffle(!player.shuffle) }
                    RoundKey(CarIcons.Previous, big * 0.7f) { CarPlayer.previous() }
                    RoundKey(if (player.playing) CarIcons.Pause else CarIcons.Play, big, filled = true) { CarPlayer.toggle() }
                    RoundKey(CarIcons.Next, big * 0.7f) { CarPlayer.next() }
                    RoundKey(
                        if (player.repeat == Player.REPEAT_MODE_ONE) CarIcons.RepeatOne else CarIcons.Repeat,
                        big * 0.6f,
                        active = player.repeat != Player.REPEAT_MODE_OFF
                    ) { CarPlayer.cycleRepeat() }
                }
            }
        }
        }
    }
}

@Composable
fun SeekBar(player: CarPlayer.State, light: Boolean = true) {
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val dur = player.durationMs.coerceAtLeast(1)
    val shown = if (dragging) drag else (player.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val text = if (light) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Slider(
            value = shown,
            onValueChange = { dragging = true; drag = it },
            onValueChangeFinished = { CarPlayer.seekTo((drag * dur).toLong()); dragging = false },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((shown * dur).toLong()), style = MaterialTheme.typography.labelLarge, color = text)
            Text(formatTime(player.durationMs), style = MaterialTheme.typography.labelLarge, color = text)
        }
    }
}

@Composable
fun RoundKey(icon: ImageVector, size: Dp, filled: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = when {
            filled -> scheme.primary
            active -> scheme.primaryContainer
            else -> Color.White.copy(alpha = 0.12f)
        },
        contentColor = when {
            filled -> scheme.onPrimary
            active -> scheme.onPrimaryContainer
            else -> Color.White
        },
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.48f)) }
    }
}

@Composable
fun Artwork(uri: Uri?, fallback: Uri?, size: Dp?, corner: Dp, icon: ImageVector, modifier: Modifier = Modifier) {
    val art = rememberArt(uri, fallback, if (size != null && size > 120.dp) 512 else 192)
    val scheme = MaterialTheme.colorScheme
    Box(
        (if (size != null) modifier.size(size) else modifier).clip(RoundedCornerShape(corner)).background(scheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
fun rememberArt(uri: Uri?, fallback: Uri?, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, uri, fallback, px) {
        value = (uri?.let { MediaLibrary.thumbnail(context, it, px) } ?: fallback?.let { MediaLibrary.thumbnail(context, it, px) })?.asImageBitmap()
    }
    return art
}

@Composable
fun Empty(text: String, icon: ImageVector) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
    }
}

fun albumUri(albumId: Long): Uri? =
    if (albumId > 0) ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId) else null

private fun canReadMusic(context: android.content.Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    return context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}

fun canReadVideos(context: android.content.Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    return context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
