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
        val out = run("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ' 0A ' | grep -ci ':149D'") ?: return false
        return (out.stdout.trim().toIntOrNull() ?: 0) > 0
    }

    fun startAaServer(context: android.content.Context? = null): Boolean {
        if (aaServerUp()) return true
        app.ft.aa.AaInstaller.settingsPackage(app.ft.FTApp.instance)?.let { if (app.ft.aa.AaInstaller.stub(app.ft.FTApp.instance, it)) return false }
        if (context != null && app.ft.aa.AaServer.start(context)) return true
        if (context != null && !app.ft.aa.AaSwitch.canTap()) {
            enableAccessibility(context.packageName, "${context.packageName}/${app.ft.FTTouchService::class.java.name}")
            repeat(30) { if (!app.ft.aa.AaSwitch.canTap()) Thread.sleep(100) }
        }
        if (context != null && app.ft.aa.AaSwitch.canTap() && app.ft.aa.AaSwitch.start(context) == app.ft.aa.AaSwitch.Result.ON) return true
        return toggleAaServer(start = true)
    }

    fun stopAaServer(context: android.content.Context? = null): Boolean {
        if (!aaServerUp()) return true
        if (context != null && app.ft.aa.AaServer.stop(context)) return true
        return toggleAaServer(start = false)
    }

    private fun toggleAaServer(start: Boolean): Boolean {
        if (!granted) return false
        if (aaServerUp() == start) return true
        run("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")
        run("am start -n ${app.ft.aa.AaInstaller.settingsComponent(app.ft.FTApp.instance)}")
        val menu = waitForNode("content-desc", "More options", 5000) ?: run { back(); return aaServerUp() }
        tap(menu)
        var xml = dumpUi()
        var tries = 0
        while (xml != null && !xml.contains("head unit server") && tries++ < 6) { Thread.sleep(150); xml = dumpUi() }
        val wanted = if (start) "Start head unit server" else "Stop head unit server"
        val item = xml?.let { nodeBounds(it, "text", wanted) }
        if (item == null) {
            val opposite = xml?.contains(if (start) "Stop head unit server" else "Start head unit server") == true
            back()
            return if (opposite) start else aaServerUp()
        }
        tap(item)
        var ok = false
        repeat(20) { if (!ok) { Thread.sleep(150); ok = aaServerUp() == start } }
        back()
        if (ok) DiagLog.i("Root", if (start) "started Android Auto's head unit server" else "stopped Android Auto's head unit server")
        else DiagLog.w("Root", "could not ${if (start) "start" else "stop"} Android Auto's head unit server")
        return ok
    }

    private fun back() { run("input keyevent KEYCODE_BACK") }

    private fun tap(p: IntArray) { run("input tap ${p[0]} ${p[1]}") }

    private fun dumpUi(): String? {
        repeat(3) {
            val d = run("uiautomator dump /data/local/tmp/ft_ui.xml >/dev/null 2>&1; cat /data/local/tmp/ft_ui.xml")
            val xml = d?.takeIf { it.ok }?.stdout
            if (xml != null && xml.contains("<node")) return xml
            Thread.sleep(400)
        }
        return null
    }

    private fun waitForNode(attr: String, value: String, timeoutMs: Long): IntArray? {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val xml = dumpUi()
            val b = xml?.let { nodeBounds(it, attr, value) }
            if (b != null) return b
            Thread.sleep(150)
        }
        return null
    }

    private fun nodeBounds(xml: String, attr: String, value: String): IntArray? {
        val tag = Regex("<node\\b[^>]*\\b$attr=\"${Regex.escape(value)}\"[^>]*>").find(xml)?.value ?: return null
        val m = Regex("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"").find(tag) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return intArrayOf((x1.toInt() + x2.toInt()) / 2, (y1.toInt() + y2.toInt()) / 2)
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
