package app.ft.media

import app.ft.FTApp
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import app.ft.core.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val video: Boolean = false
)

data class Album(val id: Long, val title: String, val artist: String, val songs: Int) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id)
}

object MediaLibrary {
    private val art = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    private val loader = Executors.newFixedThreadPool(4) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "FT-Art").apply { isDaemon = true }
    }
    private val ahead = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            r.run()
        }, "FT-ArtAhead").apply { isDaemon = true }
    }
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())

    @Volatile var lastSongs: List<Track>? = null
        private set
    @Volatile var lastAlbums: List<Album>? = null
        private set
    @Volatile var lastVideos: List<Track>? = null
        private set

    private val chatFolders = listOf("whatsapp", "telegram")

    fun chatAudio(relativePath: String?): Boolean {
        val p = relativePath?.lowercase() ?: return false
        return chatFolders.any { p.contains(it) }
    }

    suspend fun songs(context: Context, hideChats: Boolean = FTApp.instance.prefs.hideChatAudio): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 5000) {
                    if (hideChats && chatAudio(c.getString(6))) continue
                    val id = c.getLong(0)
                    out += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unknown",
                        artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                        album = c.getString(3) ?: "",
                        albumId = c.getLong(4),
                        durationMs = c.getLong(5)
                    )
                }
            }
        }.onFailure { DiagLog.w("Media", "could not read music: ${it.message}") }
        lastSongs = out
        out
    }

    suspend fun albums(context: Context, keep: Set<Long>? = null): List<Album> = withContext(Dispatchers.IO) {
        val out = ArrayList<Album>()
        val cols = arrayOf(
            MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST, MediaStore.Audio.Albums.NUMBER_OF_SONGS
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 2000) {
                    if (keep != null && c.getLong(0) !in keep) continue
                    out += Album(
                        id = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                        songs = c.getInt(3)
                    )
                }
            }
        }.onFailure { DiagLog.w("Media", "could not read albums: ${it.message}") }
        if (keep != null) lastAlbums = out
        out
    }

    suspend fun videos(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 2000) {
                    val id = c.getLong(0)
                    out += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: "Video",
                        artist = "",
                        album = "",
                        albumId = 0,
                        durationMs = c.getLong(3),
                        video = true
                    )
                }
            }
        }.onFailure { DiagLog.w("Media", "could not read videos: ${it.message}") }
        lastVideos = out
        out
    }

    suspend fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? {
        val key = "$uri@$size"
        art.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val task = loader.submit {
                if (!cont.isActive) return@submit
                val b = art.get(key) ?: runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), signal) }.getOrNull()
                if (b != null) art.put(key, b) else if (!signal.isCanceled) synchronized(missing) { missing += key }
                cont.resume(b)
            }
            cont.invokeOnCancellation {
                signal.cancel()
                task.cancel(false)
            }
        }
    }

    fun prefetch(context: Context, uris: List<Uri>, size: Int) {
        for (uri in uris) {
            val key = "$uri@$size"
            if (art.get(key) != null || synchronized(missing) { key in missing } || !queued.add(key)) continue
            ahead.execute {
                try {
                    if (art.get(key) == null) {
                        val b = runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
                        if (b != null) art.put(key, b) else synchronized(missing) { missing += key }
                    }
                } finally {
                    queued.remove(key)
                }
            }
        }
    }

    fun artUri(track: Track): Uri =
        if (track.video || track.albumId <= 0) track.uri else ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, track.albumId)

    suspend fun artBytes(context: Context, track: Track, size: Int = 240): ByteArray? {
        val b = thumbnail(context, track.uri, size) ?: thumbnail(context, ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, track.albumId), size)
            ?: return null
        return withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                b.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        }
    }

    fun forget() {
        art.evictAll()
        synchronized(missing) { missing.clear() }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
