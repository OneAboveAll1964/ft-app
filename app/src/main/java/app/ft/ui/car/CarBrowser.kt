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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CarBrowser(startUrl: String, searchTemplate: String = "https://duckduckgo.com/?q=%s", onExit: () -> Unit) {
    val showAddressBar = true
    var web by remember { mutableStateOf<WebView?>(null) }
    var typed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }

    Column(Modifier.fillMaxSize()) {
        if (showAddressBar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 104.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() else onExit() } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    FilledTonalIconButton(onClick = { web?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    Surface(
                        onClick = { editing = true; typed = "" },
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.weight(1f).height(48.dp)
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
                    FilledTonalIconButton(onClick = { onExit() }) {
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
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                            override fun onReceivedTitle(view: WebView?, t: String?) { title = t.orEmpty() }
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
                value = typed,
                onValue = { typed = it },
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
        }
    }
}
