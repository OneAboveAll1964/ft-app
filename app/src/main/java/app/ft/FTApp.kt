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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagLog.crash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        app.ft.ui.car.CarStyles.reload()
        DiagLog.i("App", "FT ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""} ready")
        DiagLog.i("App", "phone ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}), Android ${android.os.Build.VERSION.RELEASE} SDK ${android.os.Build.VERSION.SDK_INT}, " +
            "chip ${if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE}, build ${android.os.Build.DISPLAY}")
        scope.launch(Dispatchers.IO) {
            if (app.ft.core.Root.ensure()) app.ft.core.RootPrep.grants(this@FTApp)
        }
    }

    companion object {
        lateinit var instance: FTApp
            private set
    }
}
