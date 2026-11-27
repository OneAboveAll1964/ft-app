package app.ft.supercast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.ft.core.DiagLog
import rikka.shizuku.Shizuku

object ShizukuSupport {
    private const val TAG = "SuperCast"
    const val REQUEST_CODE = 4201

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) != null
    }.getOrDefault(false)

    fun isRunning(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        if (!isRunning()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching {
            if (isRunning()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure {
            DiagLog.w(TAG, "could not request Shizuku permission: ${it.message}")
        }
    }

    fun openShizukuApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launchIntent != null) {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(marketIntent)
        }
    }

    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        return method.invoke(null, cmd, env, dir) as Process
    }

    fun startHelper(context: Context, token: String): Boolean {
        if (!hasPermission()) {
            DiagLog.w(TAG, "cannot start helper via Shizuku: permission not granted")
            return false
        }
        val apk = Helper.apkPath(context) ?: return false
        val cmd = "pkill -f ${Helper.MAIN}; rm -f ${Helper.LOG}; setsid nohup ${Helper.viaEnv(apk, token)} >${Helper.LOG} 2>&1 </dev/null &"
        return runCatching {
            val process = newProcess(arrayOf("sh", "-c", cmd))
            process.waitFor()
            DiagLog.i(TAG, "SuperCast helper start command dispatched via Shizuku")
            true
        }.onFailure {
            DiagLog.w(TAG, "failed to dispatch helper via Shizuku: ${it.message}")
        }.getOrDefault(false)
    }
}
