package app.ft.carlife

import app.ft.core.ProtoWriter

/** Owned by one protocol incarnation; all operations run on the voice writer. */
internal class CarLifeVoice(
    private val now: () -> Long,
    private val current: () -> Boolean,
    private val navigation: (Boolean) -> Unit,
    private val send: (Int, ByteArray) -> Unit,
    private val wireless: Boolean
) {
    private var format: Pair<Int, Int>? = null
    private var endAt: Long? = null

    fun begin(rate: Int, channels: Int) {
        if (!current()) return
        tick()
        val next = (if (rate in 4000..48000) rate else 16000) to channels.coerceIn(1, 2)
        endAt = null
        if (format == next) return
        if (format != null) finish()
        if (!current()) return
        if (wireless) navigation(true)
        // A reset can release a blocked navigation write. Never start the old
        // phrase on the replacement connection after that wait.
        if (!current()) return
        send(CarLifeProtocol.TTS_INIT, ProtoWriter().int32(1, next.first)
            .int32(2, next.second).int32(3, 16).toByteArray())
        format = next
        if (!wireless && current()) navigation(true)
    }

    fun data(pcm: ByteArray) {
        if (!current() || format == null) return
        tick()
        if (format == null || !current()) return
        endAt = null
        send(CarLifeProtocol.TTS_DATA, pcm)
    }

    fun end() {
        if (!current() || format == null) return
        if (wireless) {
            if (endAt == null) endAt = now() + 1_500
        } else finish()
    }

    fun tick() {
        val deadline = endAt ?: return
        if (now() >= deadline) finish()
    }

    private fun finish() {
        endAt = null
        format = null
        if (!current()) return
        send(CarLifeProtocol.TTS_END, ByteArray(0))
        if (current()) navigation(false)
    }
}
