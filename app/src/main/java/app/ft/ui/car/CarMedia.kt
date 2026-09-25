package app.ft.ui.car

import android.content.ContentUris
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.core.DiagLog

data class MediaItem(val id: Long, val title: String, val uri: Uri, val video: Boolean)

@Composable
fun CarMedia(onExit: () -> Unit) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<MediaItem?>(null) }
    val listState = rememberLazyListState()

    val items = remember(videos) {
        val out = ArrayList<MediaItem>()
        val collection = if (videos) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val idCol = MediaStore.MediaColumns._ID
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        runCatching {
            context.contentResolver.query(collection, arrayOf(idCol, nameCol), null, null, "$nameCol ASC")?.use { c ->
                val i = c.getColumnIndexOrThrow(idCol)
                val n = c.getColumnIndexOrThrow(nameCol)
                while (c.moveToNext() && out.size < 500) {
                    val id = c.getLong(i)
                    out += MediaItem(id, c.getString(n) ?: "item $id", ContentUris.withAppendedId(collection, id), videos)
                }
            }
        }.onFailure { DiagLog.w("Media", "could not read media: ${it.message}") }
        out
    }

    val current = playing
    if (current != null) {
        Box(Modifier.fillMaxSize()) {
            if (current.video) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(current.uri)
                            setOnPreparedListener { it.isLooping = false; start() }
                            setOnCompletionListener { playing = null }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AudioNowPlaying(current) { playing = null }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 110.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Media", style = MaterialTheme.typography.headlineMedium)
            FilterChip(selected = videos, onClick = { videos = true }, label = { Text("Video") })
            FilterChip(selected = !videos, onClick = { videos = false }, label = { Text("Music") })
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing found on the phone", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.uri.toString() }) { item ->
                    ElevatedCard(onClick = { playing = item }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioNowPlaying(item: MediaItem, onDone: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(item.uri) {
        val mp = runCatching {
            MediaPlayer().apply {
                setDataSource(context, item.uri)
                setOnCompletionListener { onDone() }
                prepare()
                start()
            }
        }.getOrNull()
        onDispose {
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.height(72.dp))
            Text(item.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
