package app.ft.carlife

import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One FIFO per transport stream. Background producers wait for the actual write. */
internal class CarLifeWriter<T>(name: String, capacity: Int = 32, private val write: (T) -> Boolean) {
    private val lock = Any()
    private val slots = Semaphore(capacity, true)
    private val pending = HashSet<FutureTask<Boolean>>()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) {
        r -> Thread(r, name).apply { isDaemon = true }
    }
    private var closed = false

    fun send(packet: T, asynchronous: Boolean): Boolean {
        try {
            if (asynchronous) {
                if (!slots.tryAcquire()) return false
            } else {
                slots.acquire()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        val task = object : FutureTask<Boolean>({ write(packet) }) {
            override fun done() {
                synchronized(lock) { pending.remove(this) }
                slots.release()
            }
        }
        synchronized(lock) {
            if (closed) {
                slots.release()
                return false
            }
            pending += task
            executor.execute(task)
        }
        if (asynchronous) return true
        return try {
            task.get()
        } catch (_: InterruptedException) {
            // Release a slot only after removing a queued task. A running write
            // keeps its slot until it really finishes, even if its caller leaves.
            if (executor.remove(task)) task.cancel(false)
            Thread.currentThread().interrupt()
            false
        } catch (_: CancellationException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        }
    }

    /** Close the socket/pipe first, so an in-progress write also unblocks. */
    fun close() {
        synchronized(lock) {
            closed = true
            pending.toList().forEach { it.cancel(false) }
            executor.shutdownNow()
        }
    }
}

internal fun onCarLifeMainThread(): Boolean {
    val main = android.os.Looper.getMainLooper() ?: return false
    return android.os.Looper.myLooper() === main
}
