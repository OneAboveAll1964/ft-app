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
    const val EXTRA_JOB = "job"
    const val JOB_INSTALL = "install"
    const val JOB_UNINSTALL = "uninstall"
    private const val PLAY = "com.android.vending"

    enum class Step { REMOVE_UPDATES, UNINSTALL, INSTALL, DONE }

    private val WANTED = listOf(
        "com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl",
        "com.google.android.apps.auto.carservice.gmscorecompat.CarUsbReceiverTPlus",
        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    )

    private fun info(context: Context): ApplicationInfo? = runCatching {
        context.packageManager.getApplicationInfo(GEARHEAD, 0)
    }.getOrNull()

    fun installed(context: Context) = info(context) != null

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

    private fun isSystem(a: ApplicationInfo) = a.flags and ApplicationInfo.FLAG_SYSTEM != 0
    private fun isUpdatedSystem(a: ApplicationInfo) = a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    fun step(context: Context): Step {
        if (weInstalledIt(context)) return Step.DONE
        val a = info(context) ?: return Step.INSTALL
        if (isUpdatedSystem(a)) return Step.REMOVE_UPDATES
        if (isSystem(a)) return Step.INSTALL
        return Step.UNINSTALL
    }

    fun explain(context: Context): Pair<String, String> = when (step(context)) {
        Step.DONE -> "FT installed Android Auto" to
            "FT can ask it to project onto the car"
        Step.REMOVE_UPDATES -> "Android Auto belongs to ${installerOfAndroidAuto(context) ?: "the phone"}" to
            "FT keeps a copy, takes the update off, then puts its own copy back"
        Step.UNINSTALL -> "Android Auto belongs to ${installerOfAndroidAuto(context) ?: "another installer"}" to
            "FT keeps a copy, removes it, then puts its own copy back"
        Step.INSTALL -> if (stashed(context).isNotEmpty()) "A copy of Android Auto is waiting" to
            "FT will put it back and become its installer"
        else "Android Auto is not really installed" to
            "Pick a saved apk or apks of it and FT will install it"
    }

    private fun receipt(context: Context, job: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(ACTION_RESULT).setPackage(context.packageName).putExtra(EXTRA_JOB, job),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun removeUpdates(context: Context): Boolean = runCatching {
        DiagLog.i(TAG, "Android Auto shipped with the phone, so confirm Uninstall updates on the next screen")
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$GEARHEAD"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.onFailure { DiagLog.e(TAG, "could not open the uninstall screen", it) }.getOrDefault(false)

    fun uninstall(context: Context): Boolean = runCatching {
        DiagLog.i(TAG, "removing Android Auto so FT can put it back as its installer")
        context.packageManager.packageInstaller.uninstall(GEARHEAD, receipt(context, JOB_UNINSTALL, 2).intentSender)
        true
    }.onFailure { DiagLog.e(TAG, "could not remove Android Auto", it) }.getOrDefault(false)

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
        val dpi = context.resources.displayMetrics.densityDpi
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
            session.commit(receipt(context, JOB_INSTALL, sessionId).intentSender)
        }
        DiagLog.i(TAG, "installing Android Auto; confirm the prompt on the phone")
        true
    }.onFailure { DiagLog.e(TAG, "could not start the Android Auto install", it) }.getOrDefault(false)

    fun stash(context: Context): Boolean {
        val a = info(context) ?: return false
        if (!isUpdatedSystem(a) && isSystem(a)) return false
        val dir = File(context.cacheDir, "aa").apply { deleteRecursively(); mkdirs() }
        val parts = (listOf(a.sourceDir) + (a.splitSourceDirs?.toList() ?: emptyList())).filterNotNull()
        var kept = 0
        for (p in parts) {
            val src = File(p)
            val out = File(dir, if (src.name.endsWith(".apk", true)) src.name else "${src.name}.apk")
            val ok = runCatching { src.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }; true }
                .getOrElse { DiagLog.w(TAG, "could not keep ${src.name}: ${it.message}"); false }
            if (ok) kept++
        }
        if (kept == 0) { dir.deleteRecursively(); return false }
        DiagLog.i(TAG, "kept a copy of Android Auto ${version(context)} so FT can put it back")
        return true
    }

    fun stashed(context: Context): List<File> =
        File(context.cacheDir, "aa").listFiles()?.filter { it.name.endsWith(".apk", true) }?.sortedBy { it.name } ?: emptyList()

    fun installStash(context: Context): Boolean = runCatching {
        val parts = stashed(context)
        require(parts.isNotEmpty()) { "no kept copy of Android Auto" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(GEARHEAD)
            runCatching { setInstallReason(PackageManager.INSTALL_REASON_USER) }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            for (p in parts) {
                session.openWrite(p.name, 0, p.length()).use { out ->
                    p.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
            }
            session.commit(receipt(context, JOB_INSTALL, sessionId).intentSender)
        }
        DiagLog.i(TAG, "putting the kept copy of Android Auto back; confirm the prompt on the phone")
        true
    }.onFailure { DiagLog.e(TAG, "could not put Android Auto back", it) }.getOrDefault(false)

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

    fun stopAutoUpdates(context: Context): Boolean {
        val hasPlay = runCatching { context.packageManager.getApplicationInfo(PLAY, 0); true }.getOrDefault(false)
        if (!hasPlay) {
            DiagLog.i(TAG, "no store on this phone, so nothing can take Android Auto back from FT")
            return true
        }
        DiagLog.w(TAG, "turn off auto-update for Android Auto on the store page that just opened, or the store will take it back from FT")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GEARHEAD"))
                    .setPackage(PLAY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { DiagLog.w(TAG, "could not open the store page: ${it.message}") }.getOrDefault(false)
    }
}
