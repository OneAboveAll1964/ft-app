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
    private const val CAP = CHUNK * 20

    private class Lane {
        val parts = ArrayDeque<ByteArray>()
        var head = 0
        var size = 0

        fun add(pcm: ByteArray) {
            parts.addLast(pcm)
            size += pcm.size
            while (size > CAP) {
                val first = parts.peekFirst() ?: break
                val left = first.size - head
                if (size - left < CAP) {
                    val drop = size - CAP
                    head += drop
                    size -= drop
                    break
                }
                parts.removeFirst()
                size -= left
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
            return n
        }
    }

    private val lock = Any()
    private val lanes = HashMap<Int, Lane>()
    private var pump: Thread? = null

    @Volatile
    private var out: ((ByteArray) -> Unit)? = null

    var sink: ((ByteArray) -> Unit)?
        get() = out
        set(value) {
            out = value
            if (value == null) stop() else startPump()
        }

    val open: Boolean get() = out != null

    fun write(lane: Int, pcm: ByteArray) {
        if (out == null || pcm.isEmpty()) return
        synchronized(lock) { lanes.getOrPut(lane) { Lane() }.add(pcm) }
    }

    private fun stop() {
        pump?.interrupt()
        pump = null
        synchronized(lock) { lanes.clear() }
    }

    private fun startPump() {
        if (pump?.isAlive == true) return
        pump = Thread {
            val mix = ShortArray(CHUNK / 2)
            val part = ByteArray(CHUNK)
            val frame = ByteArray(CHUNK)
            var next = System.nanoTime()
            while (!Thread.currentThread().isInterrupted && out != null) {
                next += FRAME_MS * 1_000_000L
                java.util.Arrays.fill(mix, 0)
                var any = false
                synchronized(lock) {
                    val it = lanes.entries.iterator()
                    while (it.hasNext()) {
                        val lane = it.next().value
                        val n = lane.take(part)
                        if (n <= 0) continue
                        any = true
                        var i = 0
                        while (i + 1 < n) {
                            val s = ((part[i + 1].toInt() shl 8) or (part[i].toInt() and 0xFF)).toShort()
                            val sum = mix[i / 2] + s
                            mix[i / 2] = sum.coerceIn(-32768, 32767).toShort()
                            i += 2
                        }
                    }
                }
                if (any) {
                    for (i in mix.indices) {
                        frame[i * 2] = (mix[i].toInt() and 0xFF).toByte()
                        frame[i * 2 + 1] = ((mix[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    runCatching { out?.invoke(frame.copyOf()) }
                }
                val sleep = next - System.nanoTime()
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (sleep % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                } else next = System.nanoTime()
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
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
