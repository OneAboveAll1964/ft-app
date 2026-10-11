package app.ft.supercast

import android.content.Context
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.supercast.adb.AdbClient
import app.ft.supercast.adb.AdbKeys
import java.io.File

object Helper {
    private const val TAG = "SuperCast"
    const val ASSET = "ft-helper.jar"
    const val REMOTE = "/data/local/tmp/ft-helper.jar"
    const val MAIN = "app.ft.server.Main"
    const val LOG = "/data/local/tmp/ft-helper.log"

    fun unpack(context: Context): File? = runCatching {
        val out = File(context.cacheDir, ASSET)
        context.assets.open(ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.setReadable(true, false)
        out
    }.onFailure { DiagLog.e(TAG, "could not unpack the helper", it) }.getOrNull()

    const val SCRIPT = "/data/local/tmp/ft-helper.sh"

    fun script(token: String): String =
        "export CLASSPATH=$REMOTE\nexec app_process / $MAIN token=$token\n"

    fun running(): Boolean =
        Root.run("pgrep -f $MAIN")?.stdout?.trim()?.isNotEmpty() == true

    fun startWithRoot(context: Context, token: String): Boolean {
        if (!Root.ensure()) {
            DiagLog.w(TAG, "no root to start the helper with")
            return false
        }
        val local = unpack(context) ?: return false
        val copied = Root.run(
            "mkdir -p /data/local/tmp",
            "cp '${local.absolutePath}' $REMOTE",
            "chmod 644 $REMOTE",
            "chown shell:shell $REMOTE"
        )
        if (copied?.ok != true) {
            DiagLog.w(TAG, "could not put the helper in place: ${copied?.stderr?.trim()}")
            return false
        }
        Root.run("pkill -f $MAIN")
        val written = File(context.cacheDir, "ft-helper.sh").apply { writeText(script(token).replace("\\n", "\n")) }
        val placed = Root.run(
            "cp '${written.absolutePath}' $SCRIPT",
            "chmod 700 $SCRIPT",
            "chown shell:shell $SCRIPT"
        )
        if (placed?.ok != true) {
            DiagLog.w(TAG, "could not place the helper script")
            return false
        }
        val launch = "nohup sh $SCRIPT >$LOG 2>&1 &"
        val asShell = Root.run("su 2000 -c '$launch'")
        if (asShell?.ok == true) {
            DiagLog.i(TAG, "helper started with root, running as the shell user")
            return true
        }
        val asRoot = Root.run(launch)
        if (asRoot?.ok == true) {
            DiagLog.i(TAG, "helper started with root")
            return true
        }
        DiagLog.w(TAG, "the helper would not start: ${asRoot?.stderr?.trim()}")
        return false
    }

    fun startWithAdb(context: Context, token: String): Adb {
        val keys = AdbKeys.of(context) ?: return Adb.FAILED
        val client = AdbClient(keys)
        try {
            when (client.connect()) {
                AdbClient.Result.READY -> Unit
                AdbClient.Result.NOT_ALLOWED -> return Adb.NOT_ALLOWED
                AdbClient.Result.UNREACHABLE -> return Adb.NO_BRIDGE
                AdbClient.Result.TIMED_OUT -> return Adb.NO_BRIDGE
            }
            val local = unpack(context) ?: return Adb.FAILED
            client.shell("mkdir -p /data/local/tmp")
            if (!client.push(local, REMOTE, 420)) return Adb.FAILED
            client.shell("pkill -f $MAIN")
            client.shell("CLASSPATH=$REMOTE nohup app_process / $MAIN token=$token >$LOG 2>&1 &", waitMs = 2000)
            DiagLog.i(TAG, "helper started through the phone's own debug bridge")
            return Adb.STARTED
        } finally {
            client.close()
        }
    }

    enum class Adb { STARTED, NOT_ALLOWED, NO_BRIDGE, FAILED }

    fun stop() {
        Root.run("pkill -f $MAIN")
    }

    fun lastLog(): String = Root.run("tail -20 $LOG")?.stdout?.trim().orEmpty()
}
