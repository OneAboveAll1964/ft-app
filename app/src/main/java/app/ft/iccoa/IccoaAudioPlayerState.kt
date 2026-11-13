package app.ft.iccoa

import app.ft.core.ProtoWriter

/** Main-thread state. An unavailable control lane retains only the latest desired state. */
internal class IccoaAudioPlayerState {
    private var playing: Boolean? = null
    private var queuedPlaying: Boolean? = null
    val pending: Boolean get() = playing != null && playing != queuedPlaying

    fun play() { playing = true }
    fun pause() { if (playing != null) playing = false }
    fun reset() { playing = null; queuedPlaying = null }

    /** Returns the state accepted by the sender, not a receiver acknowledgement. */
    fun flush(send: (ByteArray) -> Boolean): Int? {
        if (!pending) return null
        val desired = playing!!
        val state = if (!desired) 2 else if (queuedPlaying == null) 0 else 3
        val body = ProtoWriter().int32(1, 16).int32(2, state).int32(3, 48000)
            .int32(4, 2).int32(5, 2).string(6, "audio/mp4a-latm").toByteArray()
        if (!send(body)) return null
        queuedPlaying = desired
        return state
    }
}
