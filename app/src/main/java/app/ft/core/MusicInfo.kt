package app.ft.core

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val art: ByteArray? = null
)

/** Metadata and status arrive independently; a position is unknown until AA supplies it. */
data class MusicInfo(
    val track: MusicTrack? = null,
    val playing: Boolean = false,
    val positionMs: Int? = null
)
