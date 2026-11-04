package app.ft.core

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Root {
    enum class State { UNKNOWN, NONE, DENIED, GRANTED }

    data class Out(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    data class SoftAp(val ssid: String, val passphrase: String, val open: Boolean, val band: Int)

    private val candidates = listOf("su", "/system/bin/su", "/debug_ramdisk/su", "/sbin/su", "/system/xbin/su")
    private const val SOFTAP_STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml"

    @Volatile private var binary: String? = null
    private val _state = MutableStateFlow(State.UNKNOWN)
    val stateFlow: StateFlow<State> = _state.asStateFlow()
    val state: State get() = _state.value
    @Volatile private var checkedAt = 0L

    val granted: Boolean get() = _state.value == State.GRANTED

    fun refresh(): State {
        val out = probe()
        val next = when {
            out == null -> State.NONE
            out.ok && out.stdout.contains("uid=0") -> State.GRANTED
            else -> State.DENIED
        }
        checkedAt = android.os.SystemClock.elapsedRealtime()
        if (next != _state.value) DiagLog.i("Root", "root check: $next")
        _state.value = next
        return next
    }

    fun ensure(maxAgeMs: Long = 10_000): Boolean {
        if (state == State.UNKNOWN || android.os.SystemClock.elapsedRealtime() - checkedAt > maxAgeMs) refresh()
        return granted
    }

    fun run(vararg commands: String): Out? {
        val su = binary ?: findBinary() ?: return null
        return try {
            val p = ProcessBuilder(su).redirectErrorStream(false).start()
            p.outputStream.bufferedWriter().use { w ->
                for (c in commands) {
                    w.write(c); w.write("\n")
                }
                w.write("exit\n"); w.flush()
            }
            val out = read(p.inputStream)
            val err = read(p.errorStream)
            Out(p.waitFor(), out, err)
        } catch (t: Throwable) {
            DiagLog.d("Root", "su run failed: ${t.message}")
            if (binary == su) binary = null
            null
        }
    }

    private fun ok(cmd: String): Boolean = run(cmd)?.ok == true

    /** AA startup must never wait for a fresh root grant or an unbounded shell. */
    internal fun runAaCommand(command: String): Boolean {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!granted) return false
        val su = binary ?: return false
        return try {
            val process = ProcessBuilder(su, "-c", command)
                .redirectOutput(java.io.File("/dev/null"))
                .redirectError(java.io.File("/dev/null"))
                .start()
            AaRootProcess.await(process)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            DiagLog.d("Root", "AA root command failed (${e.javaClass.simpleName})")
            false
        }
    }

    fun appop(pkg: String, op: String, mode: String) = ok("appops set $pkg $op $mode")

    fun grant(pkg: String, permission: String) = ok("pm grant $pkg $permission")

    fun wifi(on: Boolean) = ok("svc wifi ${if (on) "enable" else "disable"}") ||
        ok("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}")

    fun bluetooth(on: Boolean) = ok("svc bluetooth ${if (on) "enable" else "disable"}") ||
        ok("cmd bluetooth_manager ${if (on) "enable" else "disable"}")

    fun location(on: Boolean) = ok("settings put secure location_mode ${if (on) 3 else 0}")

    fun whitelistBattery(pkg: String) = ok("dumpsys deviceidle whitelist +$pkg")

    fun enableAccessibility(pkg: String, component: String): Boolean {
        val current = run("settings get secure enabled_accessibility_services")?.stdout?.trim().orEmpty()
        if (current.contains(component)) return ok("settings put secure accessibility_enabled 1")
        val next = if (current.isBlank() || current == "null") component else "$current:$component"
        return ok("settings put secure enabled_accessibility_services $next") &&
            ok("settings put secure accessibility_enabled 1")
    }

    fun readSoftAp(): SoftAp? {
        val xml = run("cat $SOFTAP_STORE")?.takeIf { it.ok }?.stdout ?: return null
        return parseSoftAp(xml)
    }

    internal fun parseSoftAp(xml: String): SoftAp? {
        val ssid = tag(xml, "SSID") ?: tag(xml, "WifiSsid") ?: return null
        val passphrase = tag(xml, "Passphrase") ?: tag(xml, "PreSharedKey").orEmpty()
        val security = intAttr(xml, "SecurityType") ?: if (passphrase.isBlank()) 0 else 1
        val band = intAttr(xml, "ApBand") ?: intAttr(xml, "Band") ?: 0
        return SoftAp(ssid, passphrase, open = security == 0, band = if (band == 1) 5 else 2)
    }

    fun startHotspot(ap: SoftAp): Boolean {
        if (ap.ssid.isBlank()) return false
        val auth = if (ap.open) "open" else "wpa2"
        val pass = if (ap.open) "" else shellQuote(ap.passphrase)
        return ok("cmd wifi start-softap ${shellQuote(ap.ssid)} $auth $pass ${ap.band}".replace("  ", " ").trim())
    }

    fun stopHotspot() = ok("cmd wifi stop-softap")

    fun aaServerUp(): Boolean {
        return app.ft.aa.AaInstaller.serverRunning(app.ft.FTApp.instance.prefs.aaSelfPort)
    }

    fun startAaServer(context: android.content.Context? = null): Boolean {
        if (aaServerUp()) return true
        if (context == null) return false
        app.ft.aa.AaInstaller.settingsPackage(context)?.let { if (app.ft.aa.AaInstaller.stub(context, it)) return false }
        if (app.ft.aa.AaServer.start(context)) return true
        return app.ft.aa.AaSwitch.canTap() && app.ft.aa.AaSwitch.start(context) == app.ft.aa.AaSwitch.Result.ON
    }

    fun stopAaServer(context: android.content.Context? = null): Boolean {
        if (!aaServerUp()) return true
        return context != null && app.ft.aa.AaServer.stop(context)
    }
    private fun probe(): Out? {
        findBinary() ?: return null
        return run("id")
    }

    private fun findBinary(): String? {
        binary?.let { return it }
        for (c in candidates) {
            val found = try {
                val p = ProcessBuilder(c, "-c", "id").redirectErrorStream(true).start()
                val text = read(p.inputStream); p.waitFor(); text.contains("uid=")
            } catch (_: Throwable) { false }
            if (found) { binary = c; return c }
        }
        return null
    }

    private fun tag(xml: String, name: String): String? {
        val m = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        val raw = m.groupValues[1]
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
        return raw.trim().removeSurrounding("\"").takeIf { it.isNotBlank() }
    }

    private fun intAttr(xml: String, name: String): Int? =
        Regex("<int name=\"$name\" value=\"(-?\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun read(stream: java.io.InputStream): String =
        runCatching { BufferedReader(InputStreamReader(stream)).use { it.readText() } }.getOrDefault("")
}
