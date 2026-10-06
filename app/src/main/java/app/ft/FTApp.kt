package app.ft

import android.app.Application
import android.content.Intent
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class FTApp : Application() {
    lateinit var prefs: Prefs
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var mirrorResultCode: Int = 0
    @Volatile var mirrorData: Intent? = null
    val mirrorGranted = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        DiagLog.attach(getExternalFilesDir("logs"))
        app.ft.ui.car.CarStyles.reload()
        DiagLog.i("App", "FT ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""} ready")
        scope.launch(Dispatchers.IO) {
            if (app.ft.core.Root.ensure()) app.ft.core.RootPrep.grants(this@FTApp)
        }
    }

    companion object {
        lateinit var instance: FTApp
            private set
    }
}
