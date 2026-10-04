package app.ft.projection

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MirrorFrame(val width: Int = 0, val height: Int = 0, val own: Boolean = false)

object MirrorSink {
    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface
    private val _frame = MutableStateFlow(MirrorFrame())
    val frame: StateFlow<MirrorFrame> = _frame
    fun attach(surface: Surface) { _surface.value = surface }
    fun detach() { _surface.value = null }
    fun detach(surface: Surface) { _surface.compareAndSet(surface, null) }
    @Volatile var onGone: ((Surface) -> Unit)? = null
    fun gone(surface: Surface) {
        onGone?.invoke(surface)
        detach(surface)
    }
    fun frame(width: Int, height: Int, own: Boolean) { _frame.value = MirrorFrame(width, height, own) }
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
    private var fitWidth = 0
    private var fitHeight = 0

    val ready: Boolean get() = display != null
    val active: Boolean get() = shown && display != null
    val displayId: Int get() = display?.display?.displayId ?: -1

    fun open(projection: MediaProjection, width: Int, height: Int, dpi: Int, ownDisplay: Boolean, fitWidth: Int = 0, fitHeight: Int = 0): Boolean {
        releaseDisplay()
        this.projection = projection
        this.fitWidth = fitWidth
        this.fitHeight = fitHeight
        return createDisplay(width, height, dpi, ownDisplay)
    }

    private fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        if (ownDisplay || fitWidth <= 0 || fitHeight <= 0) return width to height
        val scale = minOf(fitWidth.toFloat() / width, fitHeight.toFloat() / height, 1f)
        val w = (width * scale).toInt().coerceAtLeast(2) and 1.inv()
        val h = (height * scale).toInt().coerceAtLeast(2) and 1.inv()
        return w to h
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
        val (outW, outH) = outputSize(width, height)
        val flags = if (ownDisplay) {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        } else {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        }
        display = runCatching {
            p.createVirtualDisplay(if (ownDisplay) "FT-Car" else "FT-Mirror", outW, outH, dpi, flags, null, null, null)
        }.getOrElse {
            DiagLog.e(tag, "could not create the ${if (ownDisplay) "car" else "mirror"} display", it)
            null
        }
        if (display == null) return false
        MirrorSink.frame(outW, outH, ownDisplay)
        DiagLog.i(tag, "${if (ownDisplay) "car display ${width}x$height" else "phone mirror ${width}x$height drawn at ${outW}x$outH"} ready (display ${displayId})")
        return true
    }

    fun resize(width: Int, height: Int): Boolean {
        val d = display ?: return false
        if (ownDisplay || (width == this.width && height == this.height) || width <= 0 || height <= 0) return false
        val (outW, outH) = outputSize(width, height)
        runCatching {
            d.surface = null
            shown = false
            pending = null
            d.resize(outW, outH, dpi)
        }.onFailure {
            DiagLog.w(tag, "could not turn the mirror: ${it.message}")
            return false
        }
        this.width = width
        this.height = height
        MirrorSink.frame(outW, outH, false)
        DiagLog.i(tag, "phone turned, mirror is now ${width}x$height drawn at ${outW}x$outH")
        return true
    }

    fun show(surface: Surface) {
        pending = surface
        val d = display ?: return
        d.surface = surface
        if (!shown) DiagLog.i(tag, "${if (ownDisplay) "car display" else "mirror"} ${width}x$height started")
        shown = true
    }

    fun isShowing(surface: Surface): Boolean = shown && pending === surface

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
        projection = null
    }
}
