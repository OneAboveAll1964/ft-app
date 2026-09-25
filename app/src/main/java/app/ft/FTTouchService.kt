package app.ft

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.ft.core.DiagLog

class FTTouchService : AccessibilityService() {
    companion object {
        @Volatile var instance: FTTouchService? = null
            private set
        val enabled: Boolean get() = instance != null
    }

    private var path: Path? = null
    private var downAt = 0L
    private var lastX = 0f
    private var lastY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DiagLog.i("Touch", "accessibility touch injector connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun inject(action: Int, x: Float, y: Float) {
        when (action) {
            0 -> {
                path = Path().apply { moveTo(x, y) }
                downAt = SystemClock.uptimeMillis()
                lastX = x
                lastY = y
            }
            2 -> {
                path?.lineTo(x, y)
                lastX = x
                lastY = y
            }
            else -> {
                val p = path ?: Path().apply { moveTo(x, y) }
                if (lastX != x || lastY != y) p.lineTo(x, y)
                val duration = (SystemClock.uptimeMillis() - downAt).coerceIn(40L, 4000L)
                val stroke = GestureDescription.StrokeDescription(p, 0, duration)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                path = null
            }
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}
