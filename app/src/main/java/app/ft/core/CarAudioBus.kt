package app.ft.core

import java.util.ArrayDeque

object CarAudioBus {
    const val LANE_PHONE = 0
    const val LANE_MEDIA = 4
    const val LANE_SPEECH = 5
    const val LANE_SYSTEM = 6

    private const val RATE = 48000
    private const val FRAME_MS = 20
    private const val CHUNK = RATE / 1000 * FRAME_MS * 4
    private const val CAP = CHUNK * 5

    private class Lane {
        val parts = ArrayDeque<ByteArray>()
        var head = 0
        var size = 0
        var inBytes = 0L
        var outBytes = 0L
        var dropped = 0L

        fun add(pcm: ByteArray) {
            parts.addLast(pcm)
            inBytes += pcm.size
            size += pcm.size
            while (size > CAP) {
                val first = parts.peekFirst() ?: break
                val left = first.size - head
                if (size - left < CAP) {
                    val drop = size - CAP
                    head += drop
                    size -= drop
                    dropped += drop
                    break
                }
                parts.removeFirst()
                size -= left
                dropped += left
                head = 0
            }
        }

        fun take(out: ByteArray): Int {
            var n = 0
            while (n < out.size) {
                val first = parts.peekFirst() ?: break
                val left = first.size - head
                val want = minOf(left, out.size - n)
                System.arraycopy(first, head, out, n, want)
                n += want
                head += want
                size -= want
                if (head >= first.size) {
                    parts.removeFirst()
                    head = 0
                }
            }
            outBytes += n
            return n
        }
    }

    private val lock = Object()
    private val lanes = HashMap<Int, Lane>()
    private var pump: Thread? = null

    @Volatile
    private var out: ((ByteArray) -> Unit)? = null

    var sink: ((ByteArray) -> Unit)?
        get() = out
        set(value) {
            synchronized(lock) {
                out = value
                lanes.clear()
                lock.notifyAll()
            }
            if (value == null) stop() else startPump()
        }

    val open: Boolean get() = out != null

    fun write(lane: Int, pcm: ByteArray) {
        if (out == null || pcm.isEmpty()) return
        synchronized(lock) {
            lanes.getOrPut(lane) { Lane() }.add(pcm)
            lock.notifyAll()
        }
    }

    fun clear(lane: Int) {
        synchronized(lock) { lanes.remove(lane) }
    }

    private fun stop() {
        val t = pump
        pump = null
        t?.interrupt()
    }

    private fun fullest(): Int {
        var m = 0
        for (lane in lanes.values) if (lane.size > m) m = lane.size
        return m
    }

    private fun anything(): Boolean {
        for (lane in lanes.values) if (lane.size > 0) return true
        return false
    }

    private fun startPump() {
        if (pump?.isAlive == true) return
        pump = Thread {
            val mix = ShortArray(CHUNK / 2)
            val part = ByteArray(CHUNK)
            val frame = ByteArray(CHUNK)
            var reported = System.nanoTime()
            var sent = 0L
            while (!Thread.currentThread().isInterrupted && out != null) {
                var have = false
                synchronized(lock) {
                    if (fullest() < CHUNK) {
                        try {
                            lock.wait(FRAME_MS.toLong())
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                    have = anything()
                    if (have) {
                        java.util.Arrays.fill(mix, 0)
                        for (lane in lanes.values) {
                            val n = lane.take(part)
                            if (n <= 0) continue
                            var i = 0
                            while (i + 1 < n) {
                                val s = ((part[i + 1].toInt() shl 8) or (part[i].toInt() and 0xFF)).toShort()
                                mix[i / 2] = (mix[i / 2] + s).coerceIn(-32768, 32767).toShort()
                                i += 2
                            }
                        }
                        for (i in mix.indices) {
                            frame[i * 2] = (mix[i].toInt() and 0xFF).toByte()
                            frame[i * 2 + 1] = ((mix[i].toInt() shr 8) and 0xFF).toByte()
                        }
                    }
                }
                if (have) {
                    runCatching { out?.invoke(frame.copyOf()) }
                    sent += CHUNK
                }
                val now = System.nanoTime()
                if (now - reported > 5_000_000_000L) {
                    report(now - reported, sent)
                    reported = now
                    sent = 0L
                }
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; name = "ft-car-audio"; start() }
    }

    private fun laneName(id: Int) = when (id) {
        LANE_PHONE -> "phone"
        LANE_MEDIA -> "media"
        LANE_SPEECH -> "guidance"
        LANE_SYSTEM -> "system"
        else -> "lane$id"
    }

    private fun report(elapsedNs: Long, sent: Long) {
        val secs = elapsedNs / 1_000_000_000.0
        if (secs <= 0.0) return
        val parts = ArrayList<String>()
        synchronized(lock) {
            for ((id, lane) in lanes) {
                if (lane.inBytes == 0L && lane.outBytes == 0L) continue
                parts += "${laneName(id)} in=${(lane.inBytes / secs).toInt()} out=${(lane.outBytes / secs).toInt()} late=${lane.size * 1000 / 192000}ms drop=${lane.dropped}"
                lane.inBytes = 0
                lane.outBytes = 0
                lane.dropped = 0
            }
        }
        if (parts.isEmpty()) return
        DiagLog.i("Audio", "to the car ${(sent / secs).toInt()} of 192000 B/s | " + parts.joinToString(" | "))
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
