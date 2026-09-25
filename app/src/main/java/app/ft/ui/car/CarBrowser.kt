package app.ft.ui.car

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CarBrowser(startUrl: String, searchTemplate: String = "https://duckduckgo.com/?q=%s", onExit: () -> Unit) {
    val showAddressBar = true
    var web by remember { mutableStateOf<WebView?>(null) }
    var typed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var fullscreen by remember { mutableStateOf<android.view.View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var webField by remember { mutableStateOf(false) }

    val overlay = fullscreen
    if (overlay != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { overlay }, modifier = Modifier.fillMaxSize())
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (showAddressBar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().height(72.dp).padding(start = 104.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { web?.let { if (it.canGoBack()) it.goBack() else onExit() } },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    FilledTonalIconButton(onClick = { web?.reload() }, modifier = Modifier.size(44.dp)) {
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
                                if (editing) typed.ifEmpty { "Type an address" } else title.ifEmpty { startUrl },
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
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = false
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun onField(focused: Boolean) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { webField = focused }
                            }
                        }, "FTNative")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(FOCUS_WATCH_JS, null)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                            override fun onReceivedTitle(view: WebView?, t: String?) { title = t.orEmpty() }
                            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                                fullscreenCallback?.onCustomViewHidden()
                                fullscreen = view
                                fullscreenCallback = callback
                            }
                            override fun onHideCustomView() {
                                fullscreen = null
                                fullscreenCallback?.onCustomViewHidden()
                                fullscreenCallback = null
                            }
                        }
                        loadUrl(startUrl)
                        web = this
                    }
                },
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
                            else -> searchTemplate.replace("%s", raw.replace(" ", "+"))
                        }
                        web?.loadUrl(url)
                    }
                    editing = false
                }
            )
        } else if (webField) {
            CarKeyboard(
                goLabel = "Enter",
                onInsert = { web?.evaluateJavascript(insertJs(it), null) },
                onBackspace = { web?.evaluateJavascript("document.execCommand('delete',false,null);", null) },
                onClose = { webField = false; web?.evaluateJavascript("if(document.activeElement)document.activeElement.blur();", null) },
                onGo = {
                    web?.evaluateJavascript(ENTER_JS, null)
                    webField = false
                }
            )
        }
    }
}
