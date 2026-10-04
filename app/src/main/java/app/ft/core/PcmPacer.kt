package app.ft.core

class PcmPacer(
    private val bytesPerSecond: Int,
    leadMs: Int,
    maxQueueMs: Int,
    private val chunk: Int,
    private val name: String,
    private val threaded: Boolean = true,
    private val out: (ByteArray) -> Unit
) {
    private val lead = maxOf(bytesPerSecond.toDouble() * leadMs / 1000, chunk.toDouble())
    private val cap = bytesPerSecond.toLong() * maxQueueMs / 1000
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queued = 0L
    private var tokens = 0.0
    private var lastNs = 0L
    private var dropped = 0L
    private var held = false
    @Volatile private var thread: Thread? = null

    val backlog: Long get() = synchronized(lock) { queued }

    fun add(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            var at = 0
            while (at < pcm.size) {
                val n = minOf(chunk, pcm.size - at)
                queue.addLast(if (at == 0 && n == pcm.size) pcm else pcm.copyOfRange(at, at + n))
                queued += n
                at += n
            }
            while (queued > cap) {
                val first = queue.removeFirst()
                queued -= first.size
                dropped += first.size
            }
            lock.notifyAll()
        }
        if (threaded) start()
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        queued = 0
    }

    fun hold(on: Boolean) = synchronized(lock) {
        held = on
        lock.notifyAll()
    }

    fun takeDropped(): Long = synchronized(lock) {
        val n = dropped
        dropped = 0
        n
    }

    fun due(nowNs: Long): List<ByteArray> = synchronized(lock) {
        refill(nowNs)
        if (held) return emptyList()
        val ready = ArrayList<ByteArray>(4)
        while (true) {
            val first = queue.firstOrNull() ?: break
            if (tokens < first.size) break
            queue.removeFirst()
            queued -= first.size
            tokens -= first.size
            ready += first
        }
        ready
    }

    fun waitNs(nowNs: Long): Long = synchronized(lock) {
        refill(nowNs)
        val first = queue.firstOrNull()
        if (held || first == null) return IDLE_NS
        val missing = first.size - tokens
        if (missing <= 0) 0L else (missing * 1_000_000_000.0 / bytesPerSecond).toLong().coerceAtLeast(MIN_WAIT_NS)
    }

    private fun refill(nowNs: Long) {
        if (lastNs == 0L) {
            tokens = lead
            lastNs = nowNs
            return
        }
        val elapsed = (nowNs - lastNs).coerceAtLeast(0)
        lastNs = nowNs
        tokens = minOf(lead, tokens + elapsed * bytesPerSecond / 1_000_000_000.0)
    }

    private fun start() {
        if (thread?.isAlive == true) return
        synchronized(lock) {
            if (thread?.isAlive == true) return
            thread = Thread {
                try {
                    while (true) {
                        due(System.nanoTime()).forEach { runCatching { out(it) } }
                        synchronized(lock) {
                            val w = waitNs(System.nanoTime())
                            if (w > 0) lock.wait(w / 1_000_000, (w % 1_000_000).toInt())
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; this.name = "ft-$name-pacer"; start() }
        }
    }

    companion object {
        private const val IDLE_NS = 250_000_000L
        private const val MIN_WAIT_NS = 1_000_000L
    }
}
