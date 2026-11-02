package app.ft.aa

import app.ft.core.MusicInfo
import app.ft.core.MusicTrack
import app.ft.core.ProtoReader
import java.util.concurrent.atomic.AtomicLong

/** Decoder for AA's playback service, independent of the PCM audio channels. */
class AaMusic {
    private var latest = MusicInfo()

    fun reset(): MusicInfo = MusicInfo().also { latest = it }

    fun accept(id: Int, payload: ByteArray): MusicInfo? {
        val r = ProtoReader(payload)
        val next = when (id) {
            AaProtocol.MEDIA_PLAYBACK_STATUS -> {
                if (!r.has(1) && !r.has(3)) return null
                val state = r.int(1, -1)
                val position = if (r.has(3)) r.long(3, -1).takeIf { it >= 0 }
                    ?.coerceAtMost(Int.MAX_VALUE / 1000L)?.times(1000)?.toInt() else latest.positionMs
                latest.copy(playing = when (state) {
                    1, 3 -> false
                    2 -> true
                    else -> latest.playing
                }, positionMs = position)
            }
            AaProtocol.MEDIA_PLAYBACK_METADATA -> {
                // Metadata is a complete track description, not a patch to the previous track.
                if ((1..3).none { r.has(it) }) return null
                val title = r.string(1).orEmpty().take(256)
                val artist = r.string(2).orEmpty().take(256)
                val album = r.string(3).orEmpty().take(256)
                val previous = latest.track
                val sameTrack = previous != null && previous.title == title &&
                    previous.artist == artist && previous.album == album
                if (title.isBlank() && artist.isBlank() && album.isBlank()) {
                    latest.copy(track = null, positionMs = null)
                } else {
                    val art = r.bytes(4)?.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }
                    val duration = r.long(6).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    if (sameTrack && previous!!.durationSeconds == duration &&
                        previous.art.contentEquals(art)) return null
                    latest.copy(track = MusicTrack(
                        if (sameTrack) previous!!.id else trackIds.incrementAndGet(),
                        title, artist, album, duration, art
                    ), positionMs = if (sameTrack || previous == null) latest.positionMs else null)
                }
            }
            else -> return null
        }
        if (next == latest) return null
        latest = next
        return next
    }

    companion object {
        private val trackIds = AtomicLong()
    }
}
