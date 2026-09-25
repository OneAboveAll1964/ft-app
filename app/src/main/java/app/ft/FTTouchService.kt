package app.ft

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.ft.core.DiagLog

class FTTouchService : AccessibilityService() {
    companion object {
        @Volatile var instance: FTTouchService? = null
            private set
        val enabled: Boolean get() = instance != null
        private const val SEGMENT_MS = 32L
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var target = 0
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

    fun inject(action: Int, x: Float, y: Float, displayId: Int = 0) {
        target = displayId
        when (action) {
            0 -> {
                lastX = x
                lastY = y
                downAt = SystemClock.uptimeMillis()
                val p = Path().apply { moveTo(x, y); lineTo(x, y) }
                stroke = GestureDescription.StrokeDescription(p, 0L, SEGMENT_MS, true).also { dispatch(it) }
            }
            2 -> {
                val previous = stroke ?: return
                if (lastX == x && lastY == y) return
                val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
                lastX = x
                lastY = y
                stroke = runCatching { previous.continueStroke(p, 0L, SEGMENT_MS, true) }
                    .getOrNull()?.also { dispatch(it) }
            }
            else -> {
                val previous = stroke
                val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
                val last = if (previous != null) {
                    runCatching { previous.continueStroke(p, 0L, SEGMENT_MS, false) }.getOrNull()
                } else {
                    GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x, y) }, 0L, SEGMENT_MS, false)
                }
                stroke = null
                lastX = x
                lastY = y
                last?.let { dispatch(it) }
            }
        }
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        val ok = runCatching {
            val b = GestureDescription.Builder().addStroke(s)
            if (target > 0 && Build.VERSION.SDK_INT >= 30) b.setDisplayId(target)
            dispatchGesture(b.build(), null, null)
        }.getOrDefault(false)
        if (!ok) stroke = null
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}
