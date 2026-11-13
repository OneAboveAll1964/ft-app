package app.ft.projection

import kotlin.math.abs

/**
 * A short single-finger press is one complete accessibility gesture, not
 * asynchronous DOWN and UP continuations that can cancel each other.
 * The first real drag still generates DOWN/MOVE, then MOVE/UP.
 */
internal class MirrorTouchSequence {
    data class Command(val action: Int, val x: Float, val y: Float, val durationMs: Long = 0)
    companion object {
        const val TAP = 3
        private const val THRESHOLD_PX = 10f
    }

    private var origin: Command? = null
    private var downAtMs = 0L
    private var dragging = false

    fun reset() {
        origin = null
        downAtMs = 0L
        dragging = false
    }

    fun accept(action: Int, x: Float, y: Float, nowMs: Long): List<Command> = when (action) {
        CarTouch.DOWN -> {
            reset()
            origin = Command(CarTouch.DOWN, x, y)
            downAtMs = nowMs
            emptyList()
        }
        CarTouch.MOVE -> {
            val start = origin
            if (start == null) emptyList()
            else if (!dragging && (abs(start.x - x) > THRESHOLD_PX || abs(start.y - y) > THRESHOLD_PX)) {
                dragging = true
                listOf(start, Command(CarTouch.MOVE, x, y))
            } else if (dragging) listOf(Command(CarTouch.MOVE, x, y)) else emptyList()
        }
        CarTouch.UP -> {
            val start = origin
            val wasDragging = dragging
            val duration = (nowMs - downAtMs).coerceIn(60L, 1_200L)
            reset()
            if (start == null) emptyList()
            else if (wasDragging) listOf(Command(CarTouch.UP, x, y))
            else listOf(Command(TAP, x, y, duration))
        }
        else -> emptyList()
    }
}
