package app.ft.core

import java.util.ArrayDeque

object CarAudioBus {
    const val LANE_PHONE = 0
    const val LANE_MEDIA = 4
    const val LANE_SPEECH = 5
    const val LANE_SYSTEM = 6

    interface Voice {
        fun begin(rate: Int, channels: Int)
        fun data(pcm: ByteArray)
        fun end()
    }

    private const val RATE = 48000
    private const val REAL_TIME = RATE * 4
    private const val MAIN_ALIVE_NS = 400_000_000L
    private const val HOLD_NS = 40_000_000L
    private const val HOLD_CAP = REAL_TIME / 4
    private const val VOICE_IDLE_NS = 1_500_000_000L
    private const val SILENT = 300
    private const val VOICE_TARGET = 29000
    private const val VOICE_MAX_GAIN = 4f
    private const val DUCK_LEVEL = 0.35f
    private const val DUCK_DOWN_FRAMES = RATE * 8 / 100
    private const val DUCK_UP_FRAMES = RATE * 40 / 100

    private val lock = Any()
    private var sent = 0L
    private var dropped = 0L
    private val perLane = HashMap<Int, Long>()
    private var reported = System.nanoTime()
    private val waiting = ArrayDeque<ByteArray>()
    private var held = 0
    private var offset = 0
    private var mainAt = 0L
    private var heldAt = 0L
    private var voiceOpen = false
    private var voiceLane = -1
    private var voiceLoudAt = 0L
    private var voiceGain = 1f
    private var duckGain = 1f
    private var voiceSent = 0L
    private var speaking = false

    @Volatile
    var mixTogether = true

    @Volatile
    var voiceChannel = false

    @Volatile
    var voice: Voice? = null

    @Volatile
    private var out: ((ByteArray) -> Unit)? = null

    var sink: ((ByteArray) -> Unit)?
        get() = out
        set(value) {
            out = value
            synchronized(lock) {
                perLane.clear()
                waiting.clear()
                held = 0
                offset = 0
                mainAt = 0
                heldAt = 0
                voiceOpen = false
                voiceLane = -1
                speaking = false
                duckGain = 1f
            }
        }

    val open: Boolean get() = out != null

    fun clear(lane: Int) {
        synchronized(lock) { perLane.remove(lane) }
        if (lane == LANE_SPEECH || lane == LANE_SYSTEM) end(lane)
    }

    fun begin(lane: Int) {
        if (lane == LANE_SPEECH) synchronized(lock) { speaking = true }
    }

    fun end(lane: Int) {
        synchronized(lock) {
            if (lane == LANE_SPEECH) speaking = false
            if (!voiceOpen || voiceLane != lane) return
            closeVoice()
        }
    }

    fun tick() {
        synchronized(lock) {
            if (voiceOpen && System.nanoTime() - voiceLoudAt > VOICE_IDLE_NS) closeVoice()
        }
    }

    private fun closeVoice() {
        voiceOpen = false
        voiceLane = -1
        runCatching { voice?.end() }
    }

    fun play(lane: Int, pcm: ByteArray, rate: Int, channels: Int) {
        if (pcm.isEmpty() || out == null) return
        val v = voice
        if ((lane == LANE_SPEECH || lane == LANE_SYSTEM) && voiceChannel && v != null) {
            speak(v, lane, pcm, rate, channels)
            return
        }
        write(lane, toCarFormat(pcm, rate, channels))
    }

    private fun speak(v: Voice, lane: Int, pcm: ByteArray, rate: Int, channels: Int) {
        val now = System.nanoTime()
        val top = peak(pcm)
        val loud = top > SILENT
        synchronized(lock) {
            perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
            if (lane == LANE_SYSTEM && speaking && voiceLane == LANE_SPEECH) return
            if (loud) voiceLoudAt = now
            val want = (VOICE_TARGET.toFloat() / maxOf(top, 1)).coerceIn(1f, VOICE_MAX_GAIN)
            if (!voiceOpen) {
                if (!loud) return
                voiceOpen = true
                voiceGain = want
                runCatching { v.begin(rate, channels) }
            } else if (now - voiceLoudAt > VOICE_IDLE_NS) {
                closeVoice()
                return
            } else {
                voiceGain = if (want < voiceGain) want else voiceGain + (want - voiceGain) * 0.1f
            }
            voiceLane = lane
            voiceSent += pcm.size
            runCatching { v.data(if (voiceGain > 1.01f) louder(pcm, voiceGain) else pcm) }
        }
        report(now)
    }

    private fun louder(pcm: ByteArray, gain: Float): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val s = (v * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun duck(media: ByteArray): ByteArray {
        val target = if (voiceChannel && voiceOpen) DUCK_LEVEL else 1f
        if (duckGain == 1f && target == 1f) return media
        val down = (1f - DUCK_LEVEL) / DUCK_DOWN_FRAMES
        val up = (1f - DUCK_LEVEL) / DUCK_UP_FRAMES
        val out = media.copyOf()
        var i = 0
        while (i + 3 < out.size) {
            duckGain = if (duckGain > target) maxOf(target, duckGain - down) else minOf(target, duckGain + up)
            for (j in 0..2 step 2) {
                val v = ((out[i + j + 1].toInt() shl 8) or (out[i + j].toInt() and 0xFF)).toShort().toInt()
                val s = (v * duckGain).toInt()
                out[i + j] = (s and 0xFF).toByte()
                out[i + j + 1] = ((s shr 8) and 0xFF).toByte()
            }
            i += 4
        }
        return out
    }

    private fun peak(pcm: ByteArray): Int {
        var top = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > top) top = a
            i += 2
        }
        return top
    }

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
                send.add(blend(duck(pcm)))
            } else if (mixTogether && now - mainAt < MAIN_ALIVE_NS) {
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
            val spoken = if (voiceSent > 0) ", voice channel ${(voiceSent / secs).toInt()} B/s" else ""
            line = "to the car ${(sent / secs).toInt()} of $REAL_TIME B/s, dropped ${(dropped / secs).toInt()}$spoken | $lanes"
            sent = 0
            dropped = 0
            voiceSent = 0
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
