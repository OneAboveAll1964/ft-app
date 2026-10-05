package app.ft.ui.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import app.ft.core.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AppCatalog {
    data class App(val pkg: String, val activity: String, val label: String)

    private val _apps = MutableStateFlow<List<App>?>(null)
    val apps: StateFlow<List<App>?> = _apps
    private val loading = Mutex()
    private val icons = LruCache<String, ImageBitmap>(240)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconWork = Dispatchers.IO.limitedParallelism(3)

    suspend fun refresh(context: Context) {
        if (loading.isLocked) return
        loading.withLock {
            val started = System.nanoTime()
            val list = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                runCatching {
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                        .asSequence()
                        .filter { it.activityInfo.packageName != context.packageName }
                        .distinctBy { it.activityInfo.packageName }
                        .map { App(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
                        .sortedBy { it.label.lowercase() }
                        .toList()
                }.getOrDefault(emptyList())
            }
            val first = _apps.value == null
            _apps.value = list
            if (first) DiagLog.i("Car", "app list ready: ${list.size} apps in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    fun cachedIcon(app: App, px: Int): ImageBitmap? = icons.get(key(app, px))

    suspend fun icon(context: Context, app: App, px: Int): ImageBitmap? {
        icons.get(key(app, px))?.let { return it }
        return withContext(iconWork) {
            icons.get(key(app, px)) ?: runCatching {
                context.packageManager.getActivityIcon(ComponentName(app.pkg, app.activity)).toBitmap(px, px).asImageBitmap()
            }.getOrNull()?.also { icons.put(key(app, px), it) }
        }
    }

    suspend fun warm(context: Context, list: List<App>, px: Int) {
        list.forEach { icon(context, it, px) }
    }

    private fun key(app: App, px: Int) = "${app.pkg}/${app.activity}@$px"
}
