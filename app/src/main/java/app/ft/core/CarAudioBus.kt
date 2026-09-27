package app.ft.core

import java.util.ArrayDeque

object CarAudioBus {
    const val LANE_PHONE = 0
    const val LANE_MEDIA = 4
    const val LANE_SPEECH = 5
    const val LANE_SYSTEM = 6

    private const val RATE = 48000
    private const val REAL_TIME = RATE * 4
    private const val CEILING = REAL_TIME * 102 / 100
    private const val WINDOW_NS = 1_000_000_000L

    private val lock = Any()
    private val recent = ArrayDeque<LongArray>()
    private var inWindow = 0L
    private var sent = 0L
    private var dropped = 0L
    private val perLane = HashMap<Int, Long>()
    private var reported = System.nanoTime()

    @Volatile
    private var out: ((ByteArray) -> Unit)? = null

    var sink: ((ByteArray) -> Unit)?
        get() = out
        set(value) {
            out = value
            synchronized(lock) {
                recent.clear()
                inWindow = 0
                perLane.clear()
            }
        }

    val open: Boolean get() = out != null

    fun clear(lane: Int) {
        synchronized(lock) { perLane.remove(lane) }
    }

    fun hasRoom(lane: Int): Boolean = true

    fun onRoom(lane: Int, callback: (() -> Unit)?) = Unit

    fun write(lane: Int, pcm: ByteArray) {
        val target = out ?: return
        if (pcm.isEmpty()) return
        val now = System.nanoTime()
        synchronized(lock) {
            perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
            sent += pcm.size
        }
        runCatching { target(pcm) }
        report(now)
    }

    private fun laneName(id: Int) = when (id) {
        LANE_PHONE -> "phone"
        LANE_MEDIA -> "media"
        LANE_SPEECH -> "guidance"
        LANE_SYSTEM -> "system"
        else -> "lane$id"
    }

    private fun report(now: Long) {
        val line: String
        synchronized(lock) {
            if (now - reported < 5_000_000_000L) return
            val secs = (now - reported) / 1_000_000_000.0
            reported = now
            val lanes = perLane.entries.joinToString(" | ") { "${laneName(it.key)} ${(it.value / secs).toInt()}" }
            line = "to the car ${(sent / secs).toInt()} of $REAL_TIME B/s, dropped ${(dropped / secs).toInt()} | $lanes"
            sent = 0
            dropped = 0
            perLane.clear()
        }
        DiagLog.i("Audio", line)
    }

    fun toCarFormat(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        if (rate == RATE && channels == 2) return pcm
        val step = RATE / rate.coerceAtLeast(1)
        val frames = pcm.size / (2 * channels)
        val result = ByteArray(frames * step * 4)
        var o = 0
        for (f in 0 until frames) {
            val base = f * 2 * channels
            val lo = pcm[base]
            val hi = pcm[base + 1]
            val ro = if (channels > 1) pcm[base + 2] else lo
            val rh = if (channels > 1) pcm[base + 3] else hi
            for (r in 0 until step) {
                result[o++] = lo
                result[o++] = hi
                result[o++] = ro
                result[o++] = rh
            }
        }
        return result
    }
}
