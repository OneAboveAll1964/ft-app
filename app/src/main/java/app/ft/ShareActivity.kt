package app.ft

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.ft.carlife.CarLifeService
import app.ft.core.DiagLog

class ShareActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val app = application as FTApp
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", "screen sharing allowed without asking")
            CarLifeService.startAudio(this)
        } else {
            DiagLog.w("App", "screen sharing was not allowed")
            CarLifeService.shareDeclined(this, quiet = true)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            finish()
            return
        }
        val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
        runCatching { consent.launch(request) }.onFailure {
            DiagLog.w("App", "could not ask for screen sharing: ${it.message}")
            CarLifeService.shareDeclined(this, quiet = true)
            finish()
        }
    }
}
