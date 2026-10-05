package app.ft.core

import java.util.ArrayDeque

object CarAudioBus {
    const val LANE_PHONE = 0
    const val LANE_PLAYER = 1
    const val LANE_MEDIA = 4
    const val LANE_SPEECH = 5
    const val LANE_SYSTEM = 6
    const val GUIDANCE_IN_STEP = 0
    const val GUIDANCE_DIP = 1
    const val GUIDANCE_UNTOUCHED = 2

    interface Voice {
        fun begin(rate: Int, channels: Int)
        fun data(pcm: ByteArray)
        fun end()
    }

    private const val RATE = 48000
    private const val REAL_TIME = RATE * 4
    private const val MAIN_ALIVE_NS = 400_000_000L
    private const val MUSIC_HEARD_NS = 2_000_000_000L
    private const val HOLD_CAP = REAL_TIME
    private const val PRIME_BYTES = REAL_TIME * 15 / 100
    private const val PRIME_NS = 150_000_000L
    private const val VOICE_IDLE_NS = 1_500_000_000L
    private const val PHONE_QUIET_NS = 750_000_000L
    private const val SILENT = 300
    private const val VOICE_TARGET = 29000
    private const val BLEND_TARGET = 20000
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
    private var heardAt = 0L
    private var ownAt = 0L
    private var shadowed = 0L
    private var phoneLoudAt = 0L
    private var phoneQuiet = 0L
    private var voiceOpen = false
    private var voiceLane = -1
    private var voiceLoudAt = 0L
    private var voiceGain = 1f
    private var voiceSent = 0L
    private var speaking = false
    private var blending = false
    private var blendLane = -1
    private var blendStartAt = 0L
    private var blendLoudAt = 0L
    private var blendGain = 1f
    private var primed = true
    private var voiceInMusic = false
    private var duckGain = 1f
    private var mixedIn = 0L
    private val recentLevels = ArrayDeque<DoubleArray>()
    private var recentBytes = 0
    private var measuring = false
    private var beforeSq = 0.0
    private var beforeN = 0.0
    private var promptSq = 0.0
    private var promptN = 0.0
    private var promptSkip = 0

    @Volatile
    var mixTogether = true

    @Volatile
    var guidance = GUIDANCE_UNTOUCHED

    @Volatile
    var voice: Voice? = null

    @Volatile
    var inCall = false
        set(value) {
            if (field == value) return
            field = value
            if (value) synchronized(lock) {
                if (voiceOpen) closeVoice()
                if (blending) finishBlend()
            }
        }

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
                heardAt = 0
                ownAt = 0
                phoneLoudAt = 0
                voiceOpen = false
                voiceLane = -1
                speaking = false
                blending = false
                blendLane = -1
                primed = true
                voiceInMusic = false
                duckGain = 1f
                recentLevels.clear()
                recentBytes = 0
                measuring = false
            }
        }

    @Volatile var onBreak: ((String) -> Unit)? = null

    val open: Boolean get() = out != null

    fun clear(lane: Int) {
        synchronized(lock) { perLane.remove(lane) }
        if (lane == LANE_SPEECH || lane == LANE_SYSTEM) {
            end(lane)
            synchronized(lock) {
                if (!blending) {
                    waiting.clear()
                    held = 0
                    offset = 0
                }
            }
        }
    }

    fun begin(lane: Int) {
        if (lane == LANE_SPEECH) synchronized(lock) { speaking = true }
    }

    fun end(lane: Int) {
        if (lane == LANE_MEDIA || lane == LANE_PLAYER) onBreak?.invoke("the music stopping")
        synchronized(lock) {
            if (lane == LANE_SPEECH) speaking = false
            if (blending && blendLane == lane) finishBlend()
            if (!voiceOpen || voiceLane != lane) return
            closeVoice()
        }
    }

    fun tick() {
        synchronized(lock) {
            val now = System.nanoTime()
            if (voiceOpen && now - voiceLoudAt > VOICE_IDLE_NS) closeVoice()
            if (blending && now - blendLoudAt > VOICE_IDLE_NS) finishBlend()
        }
    }

    private fun closeVoice() {
        voiceOpen = false
        voiceLane = -1
        runCatching { voice?.end() }
        if (!blending) endMeasure()
    }

    private fun finishBlend() {
        blending = false
        blendLane = -1
        primed = true
        if (!voiceOpen) endMeasure()
    }

    private fun startMeasure() {
        if (measuring || System.nanoTime() - mainAt > MAIN_ALIVE_NS) return
        measuring = true
        beforeSq = recentLevels.sumOf { it[0] }
        beforeN = recentLevels.sumOf { it[1] }
        promptSq = 0.0
        promptN = 0.0
        promptSkip = REAL_TIME / 4
    }

    private fun endMeasure() {
        if (!measuring) return
        measuring = false
        if (beforeN <= 0 || promptN <= 0) return
        val before = Math.sqrt(beforeSq / beforeN)
        if (before < SILENT) return
        val during = Math.sqrt(promptSq / promptN)
        val db = 20 * Math.log10(maxOf(during, 1.0) / before)
        DiagLog.i("Audio", "music coming in under the directions: %+.1f dB against just before".format(java.util.Locale.US, db))
    }

    private fun level(pcm: ByteArray) {
        var sq = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sq += v * v
            i += 2
        }
        val n = (pcm.size / 2).toDouble()
        if (measuring) {
            if (promptSkip > 0) promptSkip -= pcm.size
            else {
                promptSq += sq
                promptN += n
            }
            return
        }
        recentLevels.addLast(doubleArrayOf(sq, n, pcm.size.toDouble()))
        recentBytes += pcm.size
        while (recentBytes > REAL_TIME && recentLevels.size > 1) recentBytes -= recentLevels.removeFirst()[2].toInt()
    }

    fun play(lane: Int, pcm: ByteArray, rate: Int, channels: Int) {
        if (pcm.isEmpty() || out == null) return
        if (lane != LANE_SPEECH && lane != LANE_SYSTEM) {
            write(lane, toCarFormat(pcm, rate, channels))
            return
        }
        val v = voice
        val now = System.nanoTime()
        val top = peak(pcm)
        val loud = top > SILENT
        var mix: ByteArray? = null
        var started = false
        synchronized(lock) {
            if (inCall) {
                perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
                if (voiceOpen) closeVoice()
                if (blending) finishBlend()
                return
            }
            if (!blending && !voiceOpen) {
                val musicOn = now - mainAt < MAIN_ALIVE_NS && now - heardAt < MUSIC_HEARD_NS
                if (v == null || (guidance == GUIDANCE_IN_STEP && musicOn)) {
                    if (!loud) {
                        perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
                        return
                    }
                    blending = true
                    blendLane = lane
                    blendStartAt = now
                    blendLoudAt = now
                    blendGain = gainFor(top, BLEND_TARGET)
                    primed = false
                    started = true
                    startMeasure()
                }
            }
            if (blending) {
                if (lane == LANE_SYSTEM && speaking && blendLane == LANE_SPEECH) {
                    perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
                    return
                }
                if (loud) blendLoudAt = now
                if (!started) {
                    val want = gainFor(top, BLEND_TARGET)
                    blendGain = if (want < blendGain) want else blendGain + (want - blendGain) * 0.1f
                }
                mix = toCarFormat(if (blendGain > 1.01f) louder(pcm, blendGain) else pcm, rate, channels)
            }
        }
        if (started) DiagLog.i("Audio", "directions mixed into the music, which dips in step with them")
        val m = mix
        if (m != null) {
            write(lane, m)
            return
        }
        if (v != null) speak(v, lane, pcm, rate, channels)
    }

    private fun gainFor(top: Int, target: Int) = (target.toFloat() / maxOf(top, 1)).coerceIn(1f, VOICE_MAX_GAIN)

    private fun speak(v: Voice, lane: Int, pcm: ByteArray, rate: Int, channels: Int) {
        val now = System.nanoTime()
        val top = peak(pcm)
        val loud = top > SILENT
        synchronized(lock) {
            perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
            if (inCall) {
                if (voiceOpen) closeVoice()
                return
            }
            if (lane == LANE_SYSTEM && speaking && voiceLane == LANE_SPEECH) return
            if (loud) voiceLoudAt = now
            val want = gainFor(top, VOICE_TARGET)
            if (!voiceOpen) {
                if (!loud) return
                voiceOpen = true
                voiceGain = want
                startMeasure()
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
        if (voiceInMusic && !blending && held == 0) voiceInMusic = false
        val target = if (voiceInMusic || (guidance == GUIDANCE_DIP && voiceOpen)) DUCK_LEVEL else 1f
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
        val main = lane == LANE_MEDIA || lane == LANE_PLAYER || lane == LANE_PHONE
        val audible = main && peak(pcm) > SILENT
        val send = ArrayList<ByteArray>(3)
        synchronized(lock) {
            perLane[lane] = (perLane[lane] ?: 0L) + pcm.size
            if (lane == LANE_PHONE && ownAt != 0L && now - ownAt < MAIN_ALIVE_NS) {
                shadowed += pcm.size
                return
            }
            if (lane == LANE_PHONE) {
                if (audible) phoneLoudAt = now
                else if (phoneLoudAt == 0L || now - phoneLoudAt > PHONE_QUIET_NS) {
                    phoneQuiet += pcm.size
                    return
                }
            }
            if (blending && now - blendLoudAt > VOICE_IDLE_NS) finishBlend()
            if (held > 0 && now - mainAt >= MAIN_ALIVE_NS) {
                while (true) {
                    val first = waiting.pollFirst() ?: break
                    send.add(if (offset > 0) first.copyOfRange(offset, first.size) else first)
                    offset = 0
                }
                held = 0
            }
            if (main) {
                mainAt = now
                if (audible) heardAt = now
                if (lane != LANE_PHONE) ownAt = now
                level(pcm)
                if (!primed && (held >= PRIME_BYTES || now - blendStartAt >= PRIME_NS)) primed = true
                if (primed && blending && held > 0) voiceInMusic = true
                send.add(blend(duck(pcm)))
            } else if (mixTogether && now - mainAt < MAIN_ALIVE_NS) {
                hold(pcm)
                mixedIn += pcm.size
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
            val gone = first.size - offset
            offset = 0
            held -= gone
            dropped += gone
        }
    }

    private fun blend(media: ByteArray): ByteArray {
        if (held == 0 || !primed) return media
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
        LANE_PLAYER -> "player"
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
            val mixed = if (mixedIn > 0) ", mixed into the music ${(mixedIn / secs).toInt()} B/s" else ""
            val muted = if (shadowed > 0) ", phone sound held back ${(shadowed / secs).toInt()} B/s" else ""
            val hush = if (phoneQuiet > 0) ", phone silence kept back ${(phoneQuiet / secs).toInt()} B/s" else ""
            line = "to the car ${(sent / secs).toInt()} of $REAL_TIME B/s, dropped ${(dropped / secs).toInt()}$spoken$mixed$muted$hush | $lanes"
            sent = 0
            dropped = 0
            shadowed = 0
            phoneQuiet = 0
            voiceSent = 0
            mixedIn = 0
            perLane.clear()
        }
        DiagLog.i("Audio", line)
    }

    fun toCarFormat(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        if (rate == RATE && channels == 2) return pcm
        val ch = channels.coerceAtLeast(1)
        val r = rate.coerceAtLeast(1)
        val frames = pcm.size / (2 * ch)
        if (frames == 0) return ByteArray(0)
        if (RATE % r == 0) {
            val step = RATE / r
            val result = ByteArray(frames * step * 4)
            var o = 0
            for (f in 0 until frames) {
                val base = f * 2 * ch
                val lo = pcm[base]
                val hi = pcm[base + 1]
                val ro = if (ch > 1) pcm[base + 2] else lo
                val rh = if (ch > 1) pcm[base + 3] else hi
                for (k in 0 until step) {
                    result[o++] = lo
                    result[o++] = hi
                    result[o++] = ro
                    result[o++] = rh
                }
            }
            return result
        }
        val outFrames = (frames.toLong() * RATE / r).toInt()
        val result = ByteArray(outFrames * 4)
        for (o in 0 until outFrames) {
            val pos = o.toDouble() * r / RATE
            val i = pos.toInt().coerceAtMost(frames - 1)
            val j = (i + 1).coerceAtMost(frames - 1)
            val frac = pos - i
            val left = mixAt(pcm, i, j, 0, ch, frac)
            val right = if (ch > 1) mixAt(pcm, i, j, 1, ch, frac) else left
            result[o * 4] = (left and 0xFF).toByte()
            result[o * 4 + 1] = ((left shr 8) and 0xFF).toByte()
            result[o * 4 + 2] = (right and 0xFF).toByte()
            result[o * 4 + 3] = ((right shr 8) and 0xFF).toByte()
        }
        return result
    }

    private fun mixAt(pcm: ByteArray, i: Int, j: Int, c: Int, ch: Int, frac: Double): Int {
        val a = sample(pcm, (i * ch + c) * 2)
        val b = sample(pcm, (j * ch + c) * 2)
        return (a + (b - a) * frac).toInt().coerceIn(-32768, 32767)
    }

    private fun sample(pcm: ByteArray, at: Int): Int = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort().toInt()
}
