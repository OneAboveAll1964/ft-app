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
    private const val MAIN_ALIVE_NS = 400_000_000L
    private const val HOLD_NS = 40_000_000L
    private const val HOLD_CAP = REAL_TIME / 4

    private val lock = Any()
    private val recent = ArrayDeque<LongArray>()
    private var inWindow = 0L
    private var sent = 0L
    private var dropped = 0L
    private val perLane = HashMap<Int, Long>()
    private var reported = System.nanoTime()
    private val waiting = ArrayDeque<ByteArray>()
    private var held = 0
    private var offset = 0
    private var mainAt = 0L
    private var heldAt = 0L

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
                waiting.clear()
                held = 0
                offset = 0
                mainAt = 0
                heldAt = 0
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
        val send = ArrayList<ByteArray>(3)
        synchronized(lock) {
            perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
            if (held > 0 && now - heldAt > HOLD_NS) {
                while (true) {
                    val first = waiting.pollFirst() ?: break
                    val part = if (offset > 0) first.copyOfRange(offset, first.size) else first
                    offset = 0
                    held -= part.size
                    send.add(part)
                }
                held = 0
            }
            if (lane == LANE_MEDIA || lane == LANE_PHONE) {
                mainAt = now
                send.add(blend(pcm))
            } else if (now - mainAt < MAIN_ALIVE_NS) {
                if (held == 0) heldAt = now
                hold(pcm)
            } else {
                send.add(pcm)
            }
            send.forEach { sent += it.size }
        }
        send.forEach { runCatching { target(it) } }
        report(now)
    }

    private fun hold(pcm: ByteArray) {
        waiting.addLast(pcm)
        held += pcm.size
        while (held > HOLD_CAP) {
            val first = waiting.pollFirst() ?: break
            held -= first.size
            dropped += first.size
        }
    }

    private fun blend(media: ByteArray): ByteArray {
        if (held == 0) return media
        val out = media.copyOf()
        var at = 0
        while (at + 1 < out.size && held > 0) {
            val first = waiting.peekFirst() ?: break
            val take = minOf(first.size - offset, out.size - at)
            var i = 0
            while (i + 1 < take) {
                val a = ((out[at + i + 1].toInt() shl 8) or (out[at + i].toInt() and 0xFF)).toShort()
                val b = ((first[offset + i + 1].toInt() shl 8) or (first[offset + i].toInt() and 0xFF)).toShort()
                val sum = (a + b).coerceIn(-32768, 32767)
                out[at + i] = (sum and 0xFF).toByte()
                out[at + i + 1] = ((sum shr 8) and 0xFF).toByte()
                i += 2
            }
            at += take
            offset += take
            held -= take
            if (offset >= first.size) {
                waiting.pollFirst()
                offset = 0
            }
        }
        return out
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
