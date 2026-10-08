package app.ft.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagReport {
    private const val LOGCAT_LIMIT = 6 * 1024 * 1024
    private const val TRACE_LIMIT = 96 * 1024
    private val SYSTEM_TAGS = Regex("(?i)(app\\.ft|FT/|gearhead|projection|WifiP2p|wifi_?p2p|p2p-wlan|p2p0|SoftAp|hostapd|Tethering|Bluetooth|bt_stack|btif|bta_|A2dp|Hfp|Rfcomm|Sdp|carlife|AndroidRuntime|DEBUG\\s*:|Usb|MediaCodec|CCodec|ActivityManager.*(app\\.ft|gearhead)|libc\\s*:|lowmemorykiller|am_kill.*app\\.ft)")

    class Result(val file: File, val name: String)

    fun build(c: Context): Result {
        DiagLog.i("Report", "making a report for the developer")
        DiagLog.flush()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val who = "${Build.MANUFACTURER}-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "")
        val name = "FT-report-$who-$stamp.zip"
        val dir = File(c.cacheDir, "reports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, name)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(entry: String, text: String) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("report.txt", snapshot(c))
            zip.putNextEntry(ZipEntry("ft-log.txt"))
            DiagLog.files().forEach { f ->
                zip.write("===== ${f.name} (${f.length()} bytes) =====\n".toByteArray())
                runCatching { f.inputStream().use { it.copyTo(zip) } }
                    .onFailure { zip.write("could not read: ${it.message}\n".toByteArray()) }
            }
            zip.closeEntry()
            put("exits.txt", exits(c))
            put("logcat-ft.txt", logcat(arrayOf("logcat", "-d", "-v", "threadtime")))
            if (Root.granted) {
                val all = Root.run("logcat -d -v threadtime -b main,system,crash,events")?.stdout.orEmpty()
                put("logcat-system-filtered.txt", all.lineSequence().filter { SYSTEM_TAGS.containsMatchIn(it) }.joinToString("\n").takeLast(LOGCAT_LIMIT))
            }
        }
        DiagLog.i("Report", "report ready: $name (${out.length() / 1024} KB)")
        return Result(out, name)
    }

    fun saveToDownloads(c: Context, r: Result): Uri? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, r.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FT")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads refused the file")
            c.contentResolver.openOutputStream(uri)?.use { o -> r.file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
            uri
        }.onFailure { DiagLog.e("Report", "could not save the report to Downloads", it) }.getOrNull()
    }

    private fun snapshot(c: Context): String = buildString {
        fun section(title: String, lines: List<String>) {
            append("== ").append(title).append(" ==\n")
            lines.forEach { append(it).append('\n') }
            append('\n')
        }
        append("FT report ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}\n\n")
        section("phone", DiagSnapshot.device(c))
        section("ft", DiagSnapshot.app(c))
        section("settings", DiagSnapshot.settings(c))
        section("radios and bluetooth", DiagSnapshot.radios(c))
        section("android auto", DiagSnapshot.androidAuto(c))
        section("audio", DiagSnapshot.audio(c))
        section("video codecs", DiagSnapshot.codecs())
    }

    private fun exits(c: Context): String = buildString {
        if (Build.VERSION.SDK_INT < 30) {
            append("process exit history needs Android 11 or newer\n")
            return@buildString
        }
        val am = c.getSystemService(ActivityManager::class.java)
        val list = runCatching { am.getHistoricalProcessExitReasons(c.packageName, 0, 25) }.getOrElse {
            append("could not read exit history: ${it.message}\n"); return@buildString
        }
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        var traces = 0
        list.forEach { e ->
            append("${stamp.format(Date(e.timestamp))} ${reason(e.reason)} status=${e.status} importance=${e.importance} pss=${e.pss / 1024}MB rss=${e.rss / 1024}MB process=${e.processName}\n")
            e.description?.let { append("    ").append(it).append('\n') }
            if (traces < 4 && (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)) {
                runCatching { e.traceInputStream?.use { s -> s.readBytes().take(TRACE_LIMIT).toByteArray() } }.getOrNull()?.let { bytes ->
                    traces++
                    append("    ---- trace ----\n").append(String(bytes)).append("\n    ---- end ----\n")
                }
            }
        }
        if (list.isEmpty()) append("no recorded exits\n")
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "reason $r"
    }

    private fun logcat(cmd: Array<String>): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.takeLast(LOGCAT_LIMIT)
    }.getOrElse { "could not read logcat: ${it.message}" }
}
