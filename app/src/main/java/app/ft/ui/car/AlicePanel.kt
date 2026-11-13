package app.ft.ui.car

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.R
import app.ft.core.AssistantPanelController
import app.ft.core.CarAssistantPanel
import app.ft.core.DiagLog
import app.ft.supercast.SuperCast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Alice's own Android UI; the AA surface and session remain mounted underneath. */
@Composable
fun AlicePanel(request: AssistantPanelController.Request) {
    val context = LocalContext.current
    val dpi = (160 * LocalDensity.current.density).toInt().coerceIn(120, 640)
    var prepared by remember(request.id) { mutableStateOf(false) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    var rendered by remember(request.id) { mutableStateOf(false) }
    val dismiss = { CarAssistantPanel.dismiss(request) }
    DisposableEffect(request.id) {
        DiagLog.i("AlicePanel", "opening " + request.paneName + "; phone microphone, AA focus unchanged")
        onDispose {
            CarAssistantPanel.dismiss(request)
            SuperCast.later { SuperCast.closePane(request.paneName) }
            DiagLog.i("AlicePanel", "closing " + request.paneName)
        }
    }
    LaunchedEffect(request.id) {
        val started = try {
            when {
                SuperCast.ready.value -> true
                // Reopening while startup is in flight must not rotate its handshake token.
                SuperCast.isBusy -> withTimeoutOrNull(10_000) { SuperCast.ready.first { it } } == true
                else -> {
                    val launch = SuperCast.prepareStart()
                    try { withContext(Dispatchers.IO) { launch.start(context) } }
                    finally { launch.close() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w("AlicePanel", "helper startup failed: " + e.message)
            false
        }
        if (CarAssistantPanel.isCurrent(request)) {
            if (started) prepared = true else error = "helper"
        }
    }
    LaunchedEffect(prepared) {
        if (!prepared) return@LaunchedEffect
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var previousStatus = ""
        while (CarAssistantPanel.isCurrent(request) && error == null) {
            delay(500)
            val status = withContext(Dispatchers.IO) { SuperCast.alicePaneStatus(request.paneName) }
            if (status != previousStatus) {
                DiagLog.i("AlicePanel", request.paneName + " status=" + status)
                previousStatus = status
            }
            if (status == "helper" || status == "not_on_display") error = status
            else if (!rendered && SystemClock.elapsedRealtime() >= deadline) error = "display"
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).clickable(onClick = dismiss)) {
        val panelWidth = minOf(maxHeight * .8f, maxWidth * .85f)
        Surface(
            modifier = Modifier.width(panelWidth).fillMaxHeight().padding(12.dp)
                .clickable(enabled = true, onClick = {}),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF171720), contentColor = Color.White
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.voice_assistant_alice), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.action_close)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center) {
                    if (prepared && error == null) {
                        AndroidView(
                            factory = { AliceVideoView(it, request, dpi, { error = it }, { rendered = true }) },
                            onRelease = { it.releasePanel() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (error != null) {
                        Text(stringResource(when (error) {
                            "unavailable" -> R.string.voice_alice_unavailable
                            "not_on_display" -> R.string.voice_alice_panel_moved
                            "display" -> R.string.voice_alice_panel_display_error
                            else -> R.string.voice_alice_panel_helper_error
                        }), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    } else if (!rendered) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.voice_alice_panel_starting))
                        }
                    }
                }
                Text(stringResource(R.string.voice_alice_panel_mic), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private class AliceVideoView(
    context: Context,
    private val request: AssistantPanelController.Request,
    private val dpi: Int,
    private val failed: (String) -> Unit,
    private val rendered: () -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var currentTexture: SurfaceTexture? = null
    private var reportedFrame = false
    init { isOpaque = true; surfaceTextureListener = this }

    private fun open(texture: SurfaceTexture, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        currentTexture = texture
        texture.setDefaultBufferSize(width, height)
        SuperCast.surfaceLater(Surface(texture)) { surface ->
            if (currentTexture !== texture || !CarAssistantPanel.isCurrent(request)) return@surfaceLater
            val error = SuperCast.openAlicePane(request.paneName, surface, width, height, dpi)
            DiagLog.i("AlicePanel", request.paneName + " " + width + "x" + height + " launch=" + (error ?: "accepted"))
            if (error != null) main.post {
                if (currentTexture === texture && CarAssistantPanel.isCurrent(request)) failed(error)
            }
        }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = open(texture, width, height)
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = open(texture, width, height)
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (currentTexture === texture) releasePanel()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!reportedFrame) { reportedFrame = true; rendered() }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (CarAssistantPanel.isCurrent(request)) {
            SuperCast.touch(request.paneName, event.actionMasked, event.x, event.y, width, height)
        }
        return true
    }
    fun releasePanel() {
        currentTexture = null
        SuperCast.later { SuperCast.closePane(request.paneName) }
    }
}
