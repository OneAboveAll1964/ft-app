package app.ft.aa

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.ft.core.DiagLog
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

object AaInstaller {
    private const val TAG = "AaSetup"
    const val GEARHEAD = "com.google.android.projection.gearhead"
    const val ACTION_RESULT = "app.ft.aa.INSTALL_RESULT"
    private const val PLAY = "com.android.vending"

    private val WANTED = listOf(
        "com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl",
        "com.google.android.apps.auto.carservice.gmscorecompat.CarUsbReceiverTPlus",
        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    )

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

    fun openSettings(context: Context) {
        val settings = Intent()
            .setClassName(GEARHEAD, "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(settings) }.isSuccess) return
        DiagLog.w(TAG, "Android Auto would not open its settings, showing its app page instead")
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GEARHEAD"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun installerOfAndroidAuto(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(GEARHEAD).installingPackageName
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

    private fun receipt(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(ACTION_RESULT).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("cannot read the chosen file")

    private fun isBundle(context: Context, uri: Uri): Boolean = runCatching {
        ZipInputStream(BufferedInputStream(open(context, uri))).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.substringAfterLast('/').endsWith(".apk", true)) return true
                e = zip.nextEntry
            }
        }
        false
    }.getOrDefault(false)

    private fun partsWanted(context: Context): (String) -> Boolean {
        val abis = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }.toSet()
        val dpi = android.content.res.Resources.getSystem().displayMetrics.densityDpi
        val bucket = when {
            dpi <= 120 -> "ldpi"; dpi <= 160 -> "mdpi"; dpi <= 213 -> "tvdpi"; dpi <= 240 -> "hdpi"
            dpi <= 320 -> "xhdpi"; dpi <= 480 -> "xxhdpi"; else -> "xxxhdpi"
        }
        val locales = context.resources.configuration.locales
        val langs = (0 until locales.size()).map { locales[it].language.lowercase() }.toMutableSet().apply { add("en") }
        return fun(raw: String): Boolean {
            val name = raw.substringAfterLast('/')
            if (!name.endsWith(".apk", true)) return false
            if (!name.startsWith("split_config.")) return true
            val tag = name.removePrefix("split_config.").removeSuffix(".apk").lowercase()
            return tag in abis || tag == bucket || tag == "nodpi" || tag in langs
        }
    }

    fun install(context: Context, apk: Uri): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(GEARHEAD)
            runCatching { setInstallReason(PackageManager.INSTALL_REASON_USER) }
        }
        val bundle = isBundle(context, apk)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            if (bundle) {
                val keep = partsWanted(context)
                var written = 0
                var skipped = 0
                ZipInputStream(BufferedInputStream(open(context, apk))).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val name = e.name.substringAfterLast('/')
                        if (!e.isDirectory && name.endsWith(".apk", true)) {
                            if (keep(name)) {
                                session.openWrite(name, 0, -1).use { out ->
                                    zip.copyTo(out, 1 shl 16)
                                    session.fsync(out)
                                }
                                written++
                            } else skipped++
                        }
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
                require(written > 0) { "that file holds no app to install" }
                DiagLog.i(TAG, "unpacked $written part${if (written == 1) "" else "s"} of Android Auto${if (skipped > 0) ", left out $skipped for other phones" else ""}")
            } else {
                open(context, apk).use { input ->
                    session.openWrite("base.apk", 0, -1).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
            }
            session.commit(receipt(context, sessionId).intentSender)
        }
        DiagLog.i(TAG, "installing Android Auto; confirm the prompt on the phone")
        true
    }.onFailure { DiagLog.e(TAG, "could not start the Android Auto install", it) }.getOrDefault(false)

    fun enableWirelessComponents(context: Context): Boolean {
        if (!weInstalledIt(context)) {
            DiagLog.w(TAG, "FT is not the installer of Android Auto (it is ${installerOfAndroidAuto(context) ?: "unknown"}), so its parts cannot be switched on")
            return false
        }
        var ok = true
        for (name in WANTED) {
            val done = runCatching {
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(GEARHEAD, name),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                true
            }.getOrElse {
                DiagLog.w(TAG, "could not switch on ${name.substringAfterLast('.')}: ${it.message}")
                false
            }
            if (done) DiagLog.i(TAG, "switched on ${name.substringAfterLast('.')} in Android Auto")
            ok = ok && done
        }
        return ok
    }
}
