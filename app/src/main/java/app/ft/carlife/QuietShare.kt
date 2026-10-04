package app.ft.carlife

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object QuietShare {
    const val OP = "android:project_media"

    fun command(context: Context) = "adb shell appops set ${context.packageName} PROJECT_MEDIA allow"

    fun undo(context: Context) = "adb shell appops set ${context.packageName} PROJECT_MEDIA default"

    fun allowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
