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
    private var pending: Surface? = null
    private var shown = false
    var width = 0; private set
    var height = 0; private set
    var dpi = 160; private set
    var ownDisplay = false; private set

    val ready: Boolean get() = display != null
    val active: Boolean get() = shown && display != null
    val displayId: Int get() = display?.display?.displayId ?: -1

    fun open(projection: MediaProjection, width: Int, height: Int, dpi: Int, ownDisplay: Boolean, onStopped: () -> Unit): Boolean {
        close()
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DiagLog.i(tag, "projection stopped")
                releaseDisplay()
                this@PhoneMirror.projection = null
                onStopped()
            }
        }, Handler(Looper.getMainLooper()))
        return createDisplay(width, height, dpi, ownDisplay)
    }

    fun switchMode(ownDisplay: Boolean, width: Int, height: Int, dpi: Int): Boolean {
        if (projection == null) return false
        val surface = pending
        releaseDisplay()
        val ok = createDisplay(width, height, dpi, ownDisplay)
        if (ok && surface != null) show(surface)
        return ok
    }

    private fun createDisplay(width: Int, height: Int, dpi: Int, ownDisplay: Boolean): Boolean {
        val p = projection ?: return false
        this.width = width
        this.height = height
        this.dpi = dpi
        this.ownDisplay = ownDisplay
        val flags = if (ownDisplay) {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        } else {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        }
        display = runCatching {
            p.createVirtualDisplay(if (ownDisplay) "FT-Car" else "FT-Mirror", width, height, dpi, flags, null, null, null)
        }.getOrElse {
            DiagLog.e(tag, "could not create the ${if (ownDisplay) "car" else "mirror"} display", it)
            null
        }
        if (display == null) return false
        DiagLog.i(tag, "${if (ownDisplay) "car display" else "phone mirror"} ${width}x$height ready (display ${displayId})")
        return true
    }

    fun show(surface: Surface) {
        pending = surface
        val d = display ?: return
        d.surface = surface
        if (!shown) DiagLog.i(tag, "${if (ownDisplay) "car display" else "mirror"} ${width}x$height started")
        shown = true
    }

    fun hide() {
        val d = display
        if (d != null && shown) {
            d.surface = null
            DiagLog.i(tag, "output hidden")
        }
        shown = false
    }

    private fun releaseDisplay() {
        runCatching { display?.release() }
        display = null
        shown = false
    }

    fun close() {
        hide()
        pending = null
        releaseDisplay()
        runCatching { projection?.stop() }
        projection = null
    }
}
