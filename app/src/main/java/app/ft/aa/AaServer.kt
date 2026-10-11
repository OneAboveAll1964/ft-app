package app.ft.aa

import android.content.Context
import android.content.pm.PackageManager
import app.ft.core.DiagLog
import app.ft.core.Root

object AaServer {
    private const val TAG = "AA"
    const val PACKAGE = "com.google.android.projection.gearhead"
    private const val KNOWN = "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"

    private enum class Kind { SERVICE, ACTIVITY, RECEIVER }

    private data class Part(val kind: Kind, val name: String)

    fun up(): Boolean = Root.aaServerUp()

    fun start(context: Context): Boolean {
        if (up()) return true
        if (!Root.ensure()) return false
        for (part in look(context)) {
            val component = "$PACKAGE/${part.name}"
            val tried = when (part.kind) {
                Kind.SERVICE -> listOf("am start-foreground-service -n $component", "am startservice -n $component")
                Kind.ACTIVITY -> listOf("am start -n $component")
                Kind.RECEIVER -> listOf("am broadcast -n $component")
            }
            for (command in tried) {
                Root.run(command)
                if (settles()) {
                    DiagLog.i(TAG, "Android Auto's head unit server switched on from ${part.name.substringAfterLast('.')}, nothing tapped")
                    return true
                }
            }
        }
        DiagLog.i(TAG, "none of Android Auto's own parts switched the head unit server on")
        return false
    }

    fun stop(context: Context): Boolean {
        if (!up()) return true
        if (!Root.ensure()) return false
        Root.run("am stopservice -n $PACKAGE/$KNOWN")
        if (gone()) {
            DiagLog.i(TAG, "Android Auto's head unit server stopped")
            return true
        }
        Root.run("pkill -f $PACKAGE:projection")
        if (gone()) {
            DiagLog.i(TAG, "Android Auto kept the door open after stopping, so FT closed it")
            return true
        }
        return false
    }

    private fun gone(): Boolean {
        repeat(8) {
            Thread.sleep(300)
            if (!up()) return true
        }
        return false
    }

    private fun settles(): Boolean {
        repeat(10) {
            Thread.sleep(300)
            if (up()) return true
        }
        return false
    }

    private fun look(context: Context): List<Part> {
        val wanted = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, wanted) }.getOrNull()
        val services = info?.services?.map { it.name }.orEmpty()
        val found = mutableListOf<Part>()
        if (services.isEmpty() || KNOWN in services) found += Part(Kind.SERVICE, KNOWN)
        services.forEach { if (fits(it) && it != KNOWN) found += Part(Kind.SERVICE, it) }
        info?.activities?.forEach { if (fits(it.name)) found += Part(Kind.ACTIVITY, it.name) }
        info?.receivers?.forEach { if (fits(it.name)) found += Part(Kind.RECEIVER, it.name) }
        return found
    }

    private fun fits(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("headunit") || lower.contains("head_unit")
    }

    fun parts(context: Context): List<String> = look(context).map { it.name }
}
