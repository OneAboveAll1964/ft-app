package app.ft.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DiagEntry(val time: Long, val tag: String, val text: String, val level: Int)

object DiagLog {
    private const val MAX = 800
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries

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
    }

    fun clear() = _entries.update { emptyList() }
}
