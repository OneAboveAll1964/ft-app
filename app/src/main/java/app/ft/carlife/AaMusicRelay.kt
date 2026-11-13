package app.ft.carlife

import app.ft.core.MusicInfo
import app.ft.core.MusicTrack
import app.ft.core.ProtoWriter

/** Coalesces AA updates without doing image conversion or transport I/O on the AA reader. */
class AaMusicRelay(
    private val dispatch: (() -> Unit) -> Unit,
    private val sendInfo: (MusicTrack) -> Unit,
    private val sendPosition: (Int) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onError: (Exception) -> Unit = {}
) {
    private val lock = Any()
    private var latest = MusicInfo()
    private var ready = false
    private var enabled = true
    private var subscribed = true
    private var closed = false
    private var draining = false
    private var dirty = false
    private var sent: MusicTrack? = null
    private var position: Int? = null
    private var positionAt = 0L

    fun update(info: MusicInfo) = change { latest = info }
    fun ready(on: Boolean) = change { ready = on; if (on) forget() }
    fun enable(on: Boolean) = change { if (enabled != on) forget(); enabled = on }
    fun subscribe(on: Boolean) = change { subscribed = on; forget() }

    fun allows(track: MusicTrack): Boolean = synchronized(lock) {
        !closed && ready && enabled && subscribed && latest.playing && latest.track == track
    }

    fun close() = synchronized(lock) {
        closed = true
        latest = MusicInfo()
        dirty = false
        forget()
    }

    private fun forget() { sent = null; position = null }

    private fun change(block: () -> Unit) {
        val start = synchronized(lock) {
            if (closed) return
            block()
            dirty = true
            if (draining) false else { draining = true; true }
        }
        if (start) try { dispatch(::drain) } catch (e: Exception) {
            synchronized(lock) { draining = false }
            onError(e)
        }
    }

    private fun drain() {
        while (true) {
            val effect: (() -> Unit)? = synchronized(lock) {
                if (closed || !dirty) { draining = false; return }
                dirty = false
                val track = latest.track
                if (!ready || !enabled || !subscribed || !latest.playing || track == null) {
                    // Re-send after resume. Sending a paused track can take over the car's radio.
                    forget()
                    null
                } else {
                    val infoChanged = track != sent
                    val p = latest.positionMs
                    val now = clock()
                    val progressChanged = p != null && (infoChanged || p != position &&
                        (position == null || p < position!! || now - positionAt >= 1000))
                    if (infoChanged) sent = track
                    if (progressChanged) { position = p; positionAt = now }
                    if (!infoChanged && !progressChanged) null else ({
                        if (infoChanged && allows(track)) sendInfo(track)
                        if (progressChanged && allows(track)) sendPosition(p!!)
                    })
                }
            }
            try { effect?.invoke() } catch (e: Exception) {
                // A subsequent update retries; never strand the worker or fail the AA session.
                synchronized(lock) { forget() }
                runCatching { onError(e) }
            }
        }
    }

    companion object {
        fun payload(track: MusicTrack, cover: ByteArray?): ByteArray = ProtoWriter()
            .string(1, "FT-AA#${track.id}")
            .string(2, track.title).string(3, track.artist).string(4, track.album)
            .bytes(5, cover ?: ByteArray(0))
            // CarLife MEDIA_INFO uses seconds; MEDIA_PROGRESS_BAR uses milliseconds.
            .int32(6, track.durationSeconds).int32(7, 0).string(8, track.id.toString())
            .int32(9, 0).toByteArray()
    }
}
