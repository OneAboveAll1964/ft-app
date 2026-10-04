package app.ft.ui.car

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ft.core.DiagLog

private const val FOCUS_WATCH_JS = """
(function(){
  if (window.__ftKb) return; window.__ftKb = 1;
  function editable(t){ return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable); }
  document.addEventListener('focusin', function(e){ if (editable(e.target)) FTNative.onField(true); }, true);
  document.addEventListener('focusout', function(){ FTNative.onField(false); }, true);
  if (editable(document.activeElement)) FTNative.onField(true);
})();
"""

private const val ENTER_JS = """
(function(){
  var e = document.activeElement; if (!e) return;
  ['keydown','keypress','keyup'].forEach(function(t){
    e.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
  });
  var f = e.form; if (f) { try { f.requestSubmit ? f.requestSubmit() : f.submit(); } catch (x) {} }
})();
"""

private fun insertJs(text: String): String {
    val esc = text.replace("\\", "\\\\").replace("'", "\\'")
    return "document.execCommand('insertText', false, '$esc');"
}

class WebPage(val startUrl: String, val searchTemplate: String = "https://duckduckgo.com/?q=%s") {
    private var web: WebView? = null
    val title = mutableStateOf("")
    val progress = mutableIntStateOf(100)
    val fullscreen = mutableStateOf<View?>(null)
    val field = mutableStateOf(false)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val main = Handler(Looper.getMainLooper())

    val view: WebView? get() = web

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(context: Context): WebView {
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            return w
        }
        val w = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onField(focused: Boolean) {
                    main.post { field.value = focused }
                }
            }, "FTNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(FOCUS_WATCH_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { this@WebPage.progress.intValue = newProgress }
                override fun onReceivedTitle(view: WebView?, t: String?) { this@WebPage.title.value = t.orEmpty() }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (fullscreen.value != null) fullscreenCallback?.onCustomViewHidden()
                    fullscreen.value = view
                    fullscreenCallback = callback
                    DiagLog.i("Car", "browser video full screen, the page stays underneath")
                }
                override fun onHideCustomView() {
                    fullscreen.value = null
                    fullscreenCallback = null
                }
            }
            loadUrl(startUrl)
        }
        web = w
        return w
    }

    fun back(): Boolean {
        if (fullscreen.value != null) {
            leaveFullscreen()
            return true
        }
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    fun leaveFullscreen() {
        val cb = fullscreenCallback
        fullscreenCallback = null
        fullscreen.value = null
        runCatching { cb?.onCustomViewHidden() }
    }

    fun shown() {
        web?.onResume()
    }

    fun hidden() {
        if (fullscreen.value != null) leaveFullscreen()
        field.value = false
        web?.evaluateJavascript(PAUSE_MEDIA_JS, null)
        web?.onPause()
    }

    fun destroy() {
        fullscreenCallback = null
        fullscreen.value = null
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            runCatching { w.destroy() }
        }
        web = null
    }
}

private const val PAUSE_MEDIA_JS = "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});"

@Composable
fun CarBrowser(page: WebPage, onExit: () -> Unit) {
    val showAddressBar = true
    var typed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val title by page.title
    val progress by page.progress
    val overlay by page.fullscreen
    var webField by page.field
    DisposableEffect(page) {
        page.shown()
        onDispose { page.hidden() }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (showAddressBar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                val pillLeft = CarStyles.current.value.corner.let { it == 0 || it == 2 }
                Row(
                    Modifier.fillMaxWidth().height(72.dp).padding(start = if (pillLeft) 104.dp else 10.dp, end = if (pillLeft) 10.dp else 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (!page.back()) onExit() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    FilledTonalIconButton(onClick = { page.view?.reload() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    Surface(
                        onClick = { editing = true; typed = "" },
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                if (editing) typed.ifEmpty { "Type an address" } else title.ifEmpty { page.startUrl },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    FilledTonalIconButton(onClick = { onExit() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Home, contentDescription = "Car home")
                    }
                }
            }
            if (progress in 1..99) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> page.webView(ctx) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (editing) {
            CarKeyboard(
                onInsert = { typed += it },
                onBackspace = { if (typed.isNotEmpty()) typed = typed.dropLast(1) },
                onClose = { editing = false },
                onGo = {
                    val raw = typed.trim()
                    if (raw.isNotEmpty()) {
                        val url = when {
                            raw.startsWith("http://") || raw.startsWith("https://") -> raw
                            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
                            else -> page.searchTemplate.replace("%s", raw.replace(" ", "+"))
                        }
                        page.view?.loadUrl(url)
                    }
                    editing = false
                }
            )
        } else if (webField) {
            CarKeyboard(
                goLabel = "Enter",
                onInsert = { page.view?.evaluateJavascript(insertJs(it), null) },
                onBackspace = { page.view?.evaluateJavascript("document.execCommand('delete',false,null);", null) },
                onClose = { webField = false; page.view?.evaluateJavascript("if(document.activeElement)document.activeElement.blur();", null) },
                onGo = {
                    page.view?.evaluateJavascript(ENTER_JS, null)
                    webField = false
                }
            )
        }
    }
    val full = overlay
    if (full != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            key(full) {
                AndroidView(
                    factory = { (full.parent as? ViewGroup)?.removeView(full); full },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
    }
}
