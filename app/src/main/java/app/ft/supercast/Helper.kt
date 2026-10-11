package app.ft.supercast

import android.content.Context
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.supercast.adb.AdbClient
import app.ft.supercast.adb.AdbKeys
import java.io.File

object Helper {
    private const val TAG = "SuperCast"
    const val MAIN = "app.ft.server.Main"
    const val LOG = "/data/local/tmp/ft-helper.log"
    private const val SCRIPT = "/data/local/tmp/ft-helper.sh"

    enum class Adb { STARTED, NOT_ALLOWED, NO_BRIDGE, FAILED }

    fun apkPath(context: Context): String? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    }.getOrNull()

    fun run(apk: String, token: String): String =
        "CLASSPATH=$apk app_process / $MAIN token=$token"

    fun computerCommand(token: String): String =
        "adb shell \"CLASSPATH=\$(pm path app.ft | cut -d: -f2) app_process / $MAIN token=$token &\""

    fun running(): Boolean =
        Root.run("pgrep -f $MAIN")?.stdout?.trim()?.isNotEmpty() == true

    fun startWithRoot(context: Context, token: String): Boolean {
        if (!Root.ensure()) {
            DiagLog.w(TAG, "no root to start the helper with")
            return false
        }
        val apk = apkPath(context) ?: return false
        val local = File(context.cacheDir, "ft-helper.sh").apply {
            writeText("export CLASSPATH=$apk\nexec app_process / $MAIN token=$token\n")
        }
        val placed = Root.run(
            "cp '${local.absolutePath}' $SCRIPT",
            "chmod 700 $SCRIPT",
            "chown shell:shell $SCRIPT"
        )
        if (placed?.ok != true) {
            DiagLog.w(TAG, "could not place the helper script")
            return false
        }
        Root.run("pkill -f $MAIN")
        val launch = "nohup sh $SCRIPT >$LOG 2>&1 &"
        if (Root.run("su 2000 -c '$launch'")?.ok == true) {
            DiagLog.i(TAG, "helper started with root, running as the shell user")
            return true
        }
        if (Root.run(launch)?.ok == true) {
            DiagLog.i(TAG, "helper started with root")
            return true
        }
        DiagLog.w(TAG, "the helper would not start")
        return false
    }

    fun startWithAdb(context: Context, token: String): Adb {
        val apk = apkPath(context) ?: return Adb.FAILED
        val keys = AdbKeys.of(context) ?: return Adb.FAILED
        val client = AdbClient(keys)
        try {
            when (client.connect()) {
                AdbClient.Result.READY -> Unit
                AdbClient.Result.NOT_ALLOWED -> return Adb.NOT_ALLOWED
                else -> return Adb.NO_BRIDGE
            }
            client.shell("pkill -f $MAIN")
            client.shell("${run(apk, token)} >$LOG 2>&1 &", waitMs = 2000)
            DiagLog.i(TAG, "helper started through the phone's own debug bridge")
            return Adb.STARTED
        } finally {
            client.close()
        }
    }

    fun stop() {
        Root.run("pkill -f $MAIN")
    }

    fun lastLog(): String = Root.run("tail -20 $LOG")?.stdout?.trim().orEmpty()
}
