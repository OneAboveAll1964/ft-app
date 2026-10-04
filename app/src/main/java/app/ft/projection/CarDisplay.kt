package app.ft.projection

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import app.ft.carlife.CarTouch
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CarDisplay(private val context: Context) {
    private val tag = "Display"
    private val main = Handler(Looper.getMainLooper())
    private var encoder: SurfaceEncoder? = null
    private var gate: FrameGate? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var downTime = 0L
    private var fingers = 0
    private var firstId = 0
    private var second: Pair<Float, Float>? = null
    private val moveLock = Any()
    private var pendingMove: CarTouch? = null
    private val flushMove = Runnable {
        val t = synchronized(moveLock) { pendingMove.also { pendingMove = null } }
        if (t != null) deliver(t)
    }
    @Volatile private var frameIntervalMs = 33L
    private val ticker = object : Runnable {
        override fun run() {
            val p = presentation ?: return
            p.window?.decorView?.invalidate()
            main.postDelayed(this, frameIntervalMs)
        }
    }
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    var width = 0; private set
    var height = 0; private set
    var streamWidth = 0; private set
    var streamHeight = 0; private set

    fun start(
        plan: VideoPlan,
        onConfig: (ByteArray) -> Unit,
        onFrame: (ByteArray, Boolean) -> Unit,
        content: @Composable () -> Unit
    ) {
        stop()
        val fps = plan.fps
        width = plan.contentWidth
        height = plan.contentHeight
        streamWidth = plan.streamWidth
        streamHeight = plan.streamHeight
        frameIntervalMs = (1000L / fps.coerceIn(1, 120)).coerceAtLeast(8L)
        var enc = SurfaceEncoder(streamWidth, streamHeight, fps, plan.bitrate, plan.qpFloor, onConfig, onFrame, gated = true)
        enc.start()
        val g = FrameGate(width, height, streamWidth, streamHeight, enc.surface ?: throw IllegalStateException("no encoder surface"))
        val surface = if (g.start(fps)) {
            gate = g
            g.input ?: throw IllegalStateException("no frame gate surface")
        } else {
            enc.stop()
            width = streamWidth
            height = streamHeight
            DiagLog.w(tag, "drawing straight at ${streamWidth}x$streamHeight, the car stretches it to fill its screen")
            enc = SurfaceEncoder(streamWidth, streamHeight, fps, plan.bitrate, plan.qpFloor, onConfig, onFrame)
            enc.start()
            enc.surface ?: throw IllegalStateException("no encoder surface")
        }
        encoder = enc
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val density = context.resources.displayMetrics.densityDpi.coerceIn(120, 240)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val vd = dm.createVirtualDisplay("FT", width, height, density, surface, flags)
        virtualDisplay = vd
        DiagLog.i(tag, "virtual display ${width}x${height} dpi=$density id=${vd.display.displayId}")
        main.post {
            try {
                val p = CarPresentation(context, vd.display, content)
                p.show()
                presentation = p
                _active.value = true
                if (gate == null) {
                    main.postDelayed(ticker, frameIntervalMs)
                    DiagLog.i(tag, "presentation shown, redraw every ${frameIntervalMs}ms")
                } else {
                    DiagLog.i(tag, "presentation shown, at most ${1000L / frameIntervalMs} frames a second to the car")
                }
            } catch (t: Throwable) {
                DiagLog.e(tag, "presentation failed", t)
            }
        }
    }

    fun setFrameRate(fps: Int) {
        val f = fps.coerceIn(1, 120)
        val interval = (1000L / f).coerceAtLeast(8L)
        if (interval == frameIntervalMs) return
        frameIntervalMs = interval
        val g = gate
        if (g != null) {
            g.setRate(f)
            DiagLog.i(tag, "head unit asked for $f fps, sending at most $f frames a second")
        } else {
            DiagLog.i(tag, "head unit asked for $f fps, redrawing every ${interval}ms")
        }
    }

    fun toContent(x: Int, y: Int): Pair<Int, Int> {
        val sw = streamWidth.coerceAtLeast(1)
        val sh = streamHeight.coerceAtLeast(1)
        if (sw == width && sh == height) return x to y
        return (x.toLong() * width / sw).toInt() to (y.toLong() * height / sh).toInt()
    }

    fun dispatchTouch(t: CarTouch) {
        if (t.action == CarTouch.MOVE) {
            val first = synchronized(moveLock) {
                val none = pendingMove == null
                pendingMove = t
                none
            }
            if (first) main.post(flushMove)
            return
        }
        synchronized(moveLock) { pendingMove = null }
        main.removeCallbacks(flushMove)
        main.post { deliver(t) }
    }

    private fun deliver(t: CarTouch) {
        val v = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        when (t.action) {
            CarTouch.DOWN -> {
                downTime = now
                fingers = 1
                firstId = 0
                second = null
            }
            CarTouch.POINTER_DOWN -> if (fingers == 1) {
                fingers = 2
                second = (if (t.x2 >= 0) t.x2 else t.x).toFloat() to (if (t.y2 >= 0) t.y2 else t.y).toFloat()
            } else return
            else -> if (downTime == 0L) {
                downTime = now
                fingers = 1
            }
        }
        if (t.x2 >= 0 && t.y2 >= 0 && fingers == 2 && t.action == CarTouch.MOVE) second = t.x2.toFloat() to t.y2.toFloat()
        val ev = if (fingers == 2) {
            val s2 = second ?: (t.x.toFloat() to t.y.toFloat())
            val action = when (t.action) {
                CarTouch.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                CarTouch.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (t.index.coerceIn(0, 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                CarTouch.UP -> MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> MotionEvent.ACTION_MOVE
            }
            twoFingers(now, action, t.x.toFloat(), t.y.toFloat(), s2.first, s2.second)
        } else {
            val action = when (t.action) {
                CarTouch.DOWN -> MotionEvent.ACTION_DOWN
                CarTouch.UP -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            oneFinger(now, action, firstId, t.x.toFloat(), t.y.toFloat())
        }
        v.dispatchTouchEvent(ev)
        ev.recycle()
        when (t.action) {
            CarTouch.POINTER_UP -> {
                if (t.index == 0) firstId = 1
                fingers = 1
                second = null
            }
            CarTouch.UP -> if (fingers == 2) {
                fingers = 1
                second = null
                val up = oneFinger(now, MotionEvent.ACTION_UP, firstId, t.x.toFloat(), t.y.toFloat())
                v.dispatchTouchEvent(up)
                up.recycle()
                downTime = 0L
            } else {
                downTime = 0L
            }
            else -> Unit
        }
    }

    private fun oneFinger(now: Long, action: Int, id: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun twoFingers(now: Long, action: Int, x: Float, y: Float, x2: Float, y2: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { this.x = x2; this.y = y2; pressure = 1f; size = 1f }
        )
        return MotionEvent.obtain(downTime, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    fun stop() {
        main.removeCallbacks(ticker)
        val p = presentation
        presentation = null
        if (p != null) main.post { runCatching { p.destroy() } }
        _active.value = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { gate?.stop() }
        gate = null
        runCatching { encoder?.stop() }
        encoder = null
    }
}
