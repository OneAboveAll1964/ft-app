package app.ft.supercast

import android.content.Context
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.supercast.adb.AdbClient
import app.ft.supercast.adb.AdbKeys

object Helper {
    private const val TAG = "SuperCast"
    const val MAIN = "app.ft.server.Main"
    const val LOG = "/data/local/tmp/ft-helper.log"
    private const val SHELL_UID = 2000

    enum class Adb { STARTED, NOT_ALLOWED, NO_BRIDGE, FAILED }

    fun apkPath(context: Context): String? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    }.getOrNull()

    fun run(apk: String, token: String): String =
        "CLASSPATH=$apk app_process / $MAIN token=$token"

    private fun viaEnv(apk: String, token: String): String =
        "env CLASSPATH=$apk app_process / $MAIN token=$token"

    fun computerCommand(token: String): String =
        "adb shell \"CLASSPATH=\$(pm path app.ft | cut -d: -f2) app_process / $MAIN token=$token &\""

    fun running(): Boolean =
        Root.run("pgrep -f $MAIN")?.stdout?.trim()?.isNotEmpty() == true

    fun startWithRoot(context: Context, token: String, connected: () -> Boolean = { true }): Boolean {
        if (!Root.ensure()) {
            DiagLog.w(TAG, "no root to start the helper with")
            return false
        }
        val apk = apkPath(context) ?: return false
        Root.run("pkill -f $MAIN")
        val direct = "nohup ${viaEnv(apk, token)} >$LOG 2>&1 &"
        for (command in listOf("su $SHELL_UID -c '$direct'", direct)) {
            Root.run(command)
            if (waitFor(connected)) {
                DiagLog.i(TAG, "helper started with root, running as ${who()}")
                return true
            }
            Root.run("pkill -f $MAIN")
        }
        val why = lastLog()
        DiagLog.w(TAG, if (why.isEmpty()) "the helper would not start" else "the helper would not start: $why")
        return false
    }

    private fun who(): String = when (SuperCast.helperUid) {
        0 -> "the root user"
        SHELL_UID -> "the shell user"
        else -> "uid ${SuperCast.helperUid}"
    }

    private fun waitFor(connected: () -> Boolean): Boolean {
        var alive = false
        repeat(24) { tick ->
            Thread.sleep(250)
            if (connected()) return true
            if (running()) alive = true
            else if (alive || tick > 8) return false
        }
        return connected()
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
