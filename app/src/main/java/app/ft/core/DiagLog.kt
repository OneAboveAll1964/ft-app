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
        add(tag, if (t != null) "$text: ${t.javaClass.simpleName} ${t.message ?: ""}" else text, Log.ERROR)

    fun rx(tag: String, what: String, data: ByteArray) = add(tag, "RX $what  ${Bytes.hex(data)}", Log.DEBUG)
    fun tx(tag: String, what: String, data: ByteArray) = add(tag, "TX $what  ${Bytes.hex(data)}", Log.DEBUG)

    private fun add(tag: String, text: String, level: Int) {
        Log.println(level, "FT/$tag", text)
        val entry = DiagEntry(System.currentTimeMillis(), tag, text, level)
        _entries.update { (it + entry).takeLast(MAX) }
        if (dir != null) pending.offer(entry)
    }

    fun clear() = _entries.update { emptyList() }
}
