package app.ft

import android.app.Application
import android.content.Intent
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        DiagLog.i("App", "FT ready")
    }

    companion object {
        lateinit var instance: FTApp
            private set
    }
}
