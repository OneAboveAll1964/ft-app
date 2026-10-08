package app.ft.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

data class DiagEntry(val time: Long, val tag: String, val text: String, val level: Int)

object DiagLog {
    private const val MAX = 800
    private const val FILE_LIMIT = 4L * 1024 * 1024
    private const val FILES_KEPT = 4
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries
    private val pending = LinkedBlockingQueue<DiagEntry>(5000)
    @Volatile private var dir: File? = null
    private var writer: Thread? = null

    fun attach(folder: File?) {
        if (folder == null || dir != null) return
        runCatching { folder.mkdirs() }
        dir = folder
        writer = Thread {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val levels = mapOf(Log.DEBUG to "D", Log.INFO to "I", Log.WARN to "W", Log.ERROR to "E")
            while (true) {
                val first = runCatching { pending.take() }.getOrNull() ?: break
                val batch = ArrayList<DiagEntry>(64).apply { add(first) }
                pending.drainTo(batch, 500)
                val file = current() ?: continue
                runCatching {
                    file.appendText(batch.joinToString("") { e ->
                        "${stamp.format(Date(e.time))} ${levels[e.level] ?: "I"} ${e.tag}: ${e.text}\n"
                    })
                }
            }
        }.apply { isDaemon = true; name = "ft-log-file"; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun current(): File? {
        val d = dir ?: return null
        val now = File(d, "ft.log")
        if (now.exists() && now.length() > FILE_LIMIT) {
            for (i in FILES_KEPT - 1 downTo 1) {
                val from = File(d, "ft.$i.log")
                if (from.exists()) from.renameTo(File(d, "ft.${i + 1}.log"))
            }
            now.renameTo(File(d, "ft.1.log"))
            File(d, "ft.${FILES_KEPT + 1}.log").delete()
        }
        return now
    }

    fun i(tag: String, text: String) = add(tag, text, Log.INFO)
    fun w(tag: String, text: String) = add(tag, text, Log.WARN)
    fun d(tag: String, text: String) = add(tag, text, Log.DEBUG)
    fun e(tag: String, text: String, t: Throwable? = null) =
        add(tag, if (t != null) "$text: ${t.javaClass.simpleName} ${t.message ?: ""}\n${trace(t)}" else text, Log.ERROR)

    fun rx(tag: String, what: String, data: ByteArray) = add(tag, "RX $what  ${Bytes.hex(data, PAYLOAD_LIMIT)}", Log.DEBUG)
    fun tx(tag: String, what: String, data: ByteArray) = add(tag, "TX $what  ${Bytes.hex(data, PAYLOAD_LIMIT)}", Log.DEBUG)

    fun trace(t: Throwable, maxFrames: Int = 40): String {
        val sb = StringBuilder()
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 6) {
            if (depth > 0) sb.append("Caused by: ${cause.javaClass.name}: ${cause.message ?: ""}\n")
            cause.stackTrace.take(maxFrames).forEach { sb.append("    at ").append(it).append('\n') }
            if (cause.stackTrace.size > maxFrames) sb.append("    … ${cause.stackTrace.size - maxFrames} more\n")
            cause = cause.cause.takeIf { it !== cause }
            depth++
        }
        return sb.toString().trimEnd()
    }

    fun crash(thread: Thread, t: Throwable) {
        val text = "FT crashed on thread '${thread.name}': ${t.javaClass.name}: ${t.message ?: ""}\n${trace(t, 80)}"
        Log.println(Log.ERROR, "FT/Crash", text)
        val d = dir ?: return
        runCatching {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val queued = ArrayList<DiagEntry>().also { pending.drainTo(it) }
            File(d, "ft.log").appendText(
                queued.joinToString("") { e -> "${stamp.format(Date(e.time))} ${levelName(e.level)} ${e.tag}: ${e.text}\n" } +
                    "${stamp.format(Date())} E Crash: $text\n"
            )
        }
    }

    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return (FILES_KEPT + 1 downTo 1).map { File(d, "ft.$it.log") }.filter { it.exists() } + listOfNotNull(File(d, "ft.log").takeIf { it.exists() })
    }

    fun flush(timeoutMs: Long = 1500) {
        val end = System.currentTimeMillis() + timeoutMs
        while (pending.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(25)
        Thread.sleep(60)
    }

    private fun levelName(level: Int) = when (level) {
        Log.DEBUG -> "D"; Log.WARN -> "W"; Log.ERROR -> "E"; else -> "I"
    }

    private const val PAYLOAD_LIMIT = 1024

    private fun add(tag: String, text: String, level: Int) {
        Log.println(level, "FT/$tag", text)
        val entry = DiagEntry(System.currentTimeMillis(), tag, text, level)
        _entries.update { (it + entry).takeLast(MAX) }
        if (dir != null) pending.offer(entry)
    }

    fun clear() = _entries.update { emptyList() }
}
