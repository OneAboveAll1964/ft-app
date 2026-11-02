package app.ft.iccoa

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * One bounded queue and one writer for both TS streams. Resume starts at a fresh video keyframe.
 * Enqueue transfers ownership of the encoded array; producers must not modify or reuse it.
 */
internal class IccoaStreamQueue {
    data class Frame(val data: ByteArray, val pts: Long, val video: Boolean, val key: Boolean = false, val epoch: Long = 0)
    private val frames = ArrayDeque<Frame>()
    private var videoCount = 0
    private var audioCount = 0
    private var paused = true
    private var needsKey = true
    private var minimumPts = Long.MIN_VALUE
    private var epoch = 0L
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun signal() { (this as java.lang.Object).notifyAll() }

    @Synchronized fun pause() {
        paused = true
        epoch++
        clear()
        needsKey = true
        signal()
    }

    @Synchronized fun resume(nowUs: Long) {
        if (!paused) return
        clear()
        minimumPts = nowUs
        needsKey = true
        paused = false
        signal()
    }

    /** True asks the encoder for an IDR after dropping a broken GOP. */
    @Synchronized fun video(data: ByteArray, key: Boolean, pts: Long): Boolean {
        if (paused || pts < minimumPts || data.isEmpty() || data.size > 4 * 1024 * 1024) return false
        if (needsKey && !key) return false
        if (videoCount >= 8) {
            clear()
            epoch++
            needsKey = true
            minimumPts = pts
            if (!key) return true
        }
        if (key) needsKey = false
        frames.addLast(Frame(data, pts, true, key, epoch))
        videoCount++
        signal()
        return false
    }

    @Synchronized fun audio(data: ByteArray, pts: Long, generation: Long) {
        if (paused || needsKey || generation != epoch || pts < minimumPts || data.size !in 1..8184) return
        // Drop old audio alone when the encoder gets ahead; preserve queued video dependencies.
        if (audioCount >= 32) {
            val iterator = frames.iterator()
            while (iterator.hasNext()) if (!iterator.next().video) { iterator.remove(); audioCount--; break }
        }
        frames.addLast(Frame(data, pts, false, epoch = epoch))
        audioCount++
        signal()
    }

    private fun clear() {
        frames.clear()
        videoCount = 0
        audioCount = 0
    }

    private fun removeFirst(): Frame? = frames.pollFirst()?.also {
        if (it.video) videoCount-- else audioCount--
    }

    @Synchronized fun audioEpoch(): Long? = if (paused) null else epoch
    @Synchronized fun poll(): Frame? = removeFirst()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Synchronized fun poll(timeoutMs: Long): Frame? {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            removeFirst()?.let { return it }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            TimeUnit.NANOSECONDS.timedWait(this as java.lang.Object, remaining)
        }
    }
    @Synchronized fun current(frame: Frame): Boolean = !paused && frame.epoch == epoch && frame.pts >= minimumPts
}
