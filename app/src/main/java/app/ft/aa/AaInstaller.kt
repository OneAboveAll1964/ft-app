package app.ft.aa

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.ft.core.DiagLog
import java.io.File

object AaInstaller {
    private const val TAG = "AaSetup"
    const val GEARHEAD = "com.google.android.projection.gearhead"
    private const val PLAY = "com.android.vending"

    private fun info(context: Context): ApplicationInfo? = runCatching {
        context.packageManager.getApplicationInfo(GEARHEAD, 0)
    }.getOrNull()

    fun installed(context: Context) = info(context) != null

    fun serverRunning(port: Int): Boolean {
        val probe = java.net.ServerSocket()
        return try {
            probe.reuseAddress = false
            probe.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 1)
            false
        } catch (_: java.net.BindException) {
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    fun stub(context: Context, pkg: String = GEARHEAD): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty().contains("stub", ignoreCase = true)
    }.getOrDefault(false)

    fun settingsPackage(context: Context): String? {
        val wanted = app.ft.FTApp.instance.prefs.aaServerPackage
        val pkg = if (wanted.isNotBlank() && wanted != GEARHEAD && runCatching { context.packageManager.getApplicationInfo(wanted, 0) }.isSuccess) wanted else GEARHEAD
        return pkg.takeIf { runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess }
    }

    fun settingsComponent(context: Context): String {
        val pkg = settingsPackage(context) ?: GEARHEAD
        if (pkg == GEARHEAD) return "$GEARHEAD/com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
        return context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString() ?: pkg
    }

    internal fun openStarterSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent().setClassName(GEARHEAD,
            "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }.onSuccess {
        app.ft.core.DiagLog.i("AA", "Android Auto starter settings opened")
    }.onFailure {
        app.ft.core.DiagLog.w("AA", "Android Auto starter settings could not open (${it.javaClass.simpleName})")
    }.isSuccess

    fun openSettings(context: Context, fallback: Boolean = true): Boolean {
        val pkg = settingsPackage(context) ?: GEARHEAD
        val settings = (if (pkg == GEARHEAD) {
            Intent().setClassName(GEARHEAD, "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity")
        } else {
            context.packageManager.getLaunchIntentForPackage(pkg) ?: Intent()
        }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or if (fallback) 0 else Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (runCatching { context.startActivity(settings) }.isSuccess) return true
        if (!fallback) return false
        DiagLog.w(TAG, "Android Auto would not open its settings, showing its app page instead")
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GEARHEAD"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }

    fun installerOfAndroidAuto(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(GEARHEAD).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(GEARHEAD)
        }
    }.getOrNull()

    fun weInstalledIt(context: Context): Boolean =
        installed(context) && installerOfAndroidAuto(context) == context.packageName

    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(GEARHEAD, 0).versionName ?: ""
    }.getOrDefault("")

    fun openStore(context: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GEARHEAD")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(Intent(store).setPackage(PLAY)) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GEARHEAD"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { DiagLog.w(TAG, "could not open the store page: ${it.message}") }
    }

    fun forgetOldCopy(context: Context) {
        val dir = File(context.cacheDir, "aa")
        if (dir.exists() && dir.deleteRecursively()) DiagLog.i(TAG, "removed the copy of Android Auto older FT versions kept")
    }
}
