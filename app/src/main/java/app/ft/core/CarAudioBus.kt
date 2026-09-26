package app.ft.core

object CarAudioBus {
    @Volatile
    var sink: ((ByteArray) -> Unit)? = null

    val open: Boolean get() = sink != null

    fun play(pcm: ByteArray) {
        sink?.invoke(pcm)
    }

    fun toCarFormat(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        if (rate == 48000 && channels == 2) return pcm
        val step = 48000 / rate.coerceAtLeast(1)
        val frames = pcm.size / (2 * channels)
        val out = ByteArray(frames * step * 4)
        var o = 0
        for (f in 0 until frames) {
            val base = f * 2 * channels
            val lo = pcm[base]
            val hi = pcm[base + 1]
            val ro = if (channels > 1) pcm[base + 2] else lo
            val rh = if (channels > 1) pcm[base + 3] else hi
            for (r in 0 until step) {
                out[o++] = lo
                out[o++] = hi
                out[o++] = ro
                out[o++] = rh
            }
        }
        return out
    }
}
