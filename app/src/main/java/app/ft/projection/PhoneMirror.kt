package app.ft.projection

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
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
    var width = 0; private set
    var height = 0; private set

    fun start(projection: MediaProjection, width: Int, height: Int, dpi: Int, surface: Surface) {
        stop()
        this.projection = projection
        this.width = width
        this.height = height
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DiagLog.i(tag, "projection stopped")
                display?.release()
                display = null
            }
        }, null)
        display = projection.createVirtualDisplay("FT-Mirror", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        DiagLog.i(tag, "mirror ${width}x$height started")
    }

    fun stop() {
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
    }

    val active: Boolean get() = display != null
}
