package app.ft.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object DuplicateAppHelper {
    val KNOWN_VARIANTS = listOf("com.shkomaghdid.ft", "app.ft")

    fun findConflictingPackage(context: Context): String? {
        val current = context.packageName
        val pm = context.packageManager ?: return null
        for (pkg in KNOWN_VARIANTS) {
            if (pkg == current) continue
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun requestUninstall(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
