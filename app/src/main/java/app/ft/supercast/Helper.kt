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

    private fun viaEnv(apk: String, token: String): String =
        "env CLASSPATH=$apk app_process / $MAIN token=$token"

    fun computerCommand(token: String): String =
        "adb shell \"CLASSPATH=\$(pm path app.ft | cut -d: -f2) app_process / $MAIN token=$token &\""

    fun running(): Boolean =
        Root.run("pgrep -f $MAIN")?.stdout?.trim()?.isNotEmpty() == true

    private fun place(context: Context, apk: String, token: String): Boolean {
        val local = File(context.cacheDir, "ft-helper.sh").apply {
            writeText("export CLASSPATH=$apk\nexec app_process / $MAIN token=$token\n")
        }
        Root.run(
            "cp '${local.absolutePath}' $SCRIPT",
            "chmod 755 $SCRIPT",
            "chown shell:shell $SCRIPT",
            "chcon u:object_r:shell_data_file:s0 $SCRIPT"
        )
        val readable = Root.run("su 2000 -c 'test -r $SCRIPT && echo yes'")?.stdout?.contains("yes") == true
        if (!readable) DiagLog.w(TAG, "the shell user cannot read the helper script, starting it another way")
        return readable
    }

    fun startWithRoot(context: Context, token: String, connected: () -> Boolean = { true }): Boolean {
        if (!Root.ensure()) {
            DiagLog.w(TAG, "no root to start the helper with")
            return false
        }
        val apk = apkPath(context) ?: return false
        Root.run("pkill -f $MAIN")
        val direct = viaEnv(apk, token)
        val ways = buildList {
            if (place(context, apk, token)) add("as the shell user" to "su 2000 -c 'nohup sh $SCRIPT >$LOG 2>&1 &'")
            add("as the shell user" to "su 2000 -c 'nohup $direct >$LOG 2>&1 &'")
            add("as root" to "nohup $direct >$LOG 2>&1 &")
        }
        for ((how, command) in ways) {
            Root.run(command)
            if (waitFor(connected)) {
                DiagLog.i(TAG, "helper started with root, running $how")
                return true
            }
            Root.run("pkill -f $MAIN")
        }
        val why = lastLog()
        DiagLog.w(TAG, if (why.isEmpty()) "the helper would not start" else "the helper would not start: $why")
        return false
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
