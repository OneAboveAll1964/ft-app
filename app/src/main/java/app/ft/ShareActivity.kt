package app.ft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.ft.projection.CarControls
import app.ft.core.DiagLog

class ShareActivity : ComponentActivity() {
    private val quiet get() = intent.getBooleanExtra("quiet", false)

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val app = application as FTApp
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", if (quiet) "screen sharing allowed without asking" else "screen mirror permitted")
            CarControls.startAudio(this)
        } else {
            DiagLog.w("App", "screen sharing was not allowed")
            CarControls.shareDeclined(this, quiet)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            CarControls.shareDeclined(this, quiet)
            finish()
            return
        }
        runCatching {
            val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
            consent.launch(request)
        }.onFailure {
            DiagLog.w("App", "could not ask for screen sharing: ${it.message}")
            CarControls.shareDeclined(this, quiet)
            finish()
        }
    }

    companion object {
        fun request(context: Context, quiet: Boolean = false) {
            context.startActivity(
                Intent(context, ShareActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .apply { if (quiet) addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) }
                    .putExtra("quiet", quiet)
            )
        }
    }
}
