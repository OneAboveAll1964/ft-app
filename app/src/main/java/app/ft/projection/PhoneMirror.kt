package app.ft.projection

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MirrorSink {
    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface
    fun attach(surface: Surface) { _surface.value = surface }
    fun detach() { _surface.value = null }
}

class PhoneMirror {
    private val tag = "Mirror"
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var shown = false
    var width = 0; private set
    var height = 0; private set

    val ready: Boolean get() = display != null
    val active: Boolean get() = shown && display != null

    fun open(projection: MediaProjection, width: Int, height: Int, dpi: Int, onStopped: () -> Unit) {
        close()
        this.projection = projection
        this.width = width
        this.height = height
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DiagLog.i(tag, "projection stopped")
                runCatching { display?.release() }
                display = null
                shown = false
                this@PhoneMirror.projection = null
                onStopped()
            }
        }, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay("FT-Mirror", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null)
        DiagLog.i(tag, "mirror ${width}x$height ready")
    }

    fun show(surface: Surface) {
        val d = display ?: return
        d.surface = surface
        if (!shown) DiagLog.i(tag, "mirror ${width}x$height started")
        shown = true
    }

    fun hide() {
        val d = display ?: return
        if (shown) {
            d.surface = null
            DiagLog.i(tag, "mirror hidden")
        }
        shown = false
    }

    fun close() {
        hide()
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
    }
}
