package app.ft.projection

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CarDisplay(private val context: Context) {
    private val tag = "Display"
    private val main = Handler(Looper.getMainLooper())
    private var encoder: SurfaceEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var downTime = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val p = presentation ?: return
            p.window?.decorView?.invalidate()
            main.postDelayed(this, 100)
        }
    }
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    var width = 0; private set
    var height = 0; private set

    fun start(
        width: Int,
        height: Int,
        fps: Int,
        onConfig: (ByteArray) -> Unit,
        onFrame: (ByteArray, Boolean) -> Unit,
        content: @Composable () -> Unit
    ) {
        stop()
        this.width = width
        this.height = height
        val enc = SurfaceEncoder(width, height, fps, onConfig, onFrame)
        enc.start()
        encoder = enc
        val surface = enc.surface ?: throw IllegalStateException("no encoder surface")
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
                main.postDelayed(ticker, 100)
                DiagLog.i(tag, "presentation shown")
            } catch (t: Throwable) {
                DiagLog.e(tag, "presentation failed", t)
            }
        }
    }

    fun requestKeyFrame() = encoder?.requestKeyFrame()

    fun currentConfig(): ByteArray = encoder?.currentConfig() ?: ByteArray(0)

    fun dispatchTouch(action: Int, x: Int, y: Int) {
        main.post {
            val v = presentation?.window?.decorView ?: return@post
            val now = SystemClock.uptimeMillis()
            val ma = when (action) {
                0 -> { downTime = now; MotionEvent.ACTION_DOWN }
                1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            if (ma != MotionEvent.ACTION_DOWN && downTime == 0L) downTime = now
            val ev = MotionEvent.obtain(downTime, now, ma, x.toFloat(), y.toFloat(), 0)
            ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    fun stop() {
        main.removeCallbacks(ticker)
        val p = presentation
        presentation = null
        if (p != null) main.post { runCatching { p.destroy() } }
        _active.value = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.stop() }
        encoder = null
    }
}
