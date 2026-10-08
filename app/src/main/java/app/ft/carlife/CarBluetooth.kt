package app.ft.carlife

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class CarBluetooth(context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val tag = "CarBT"
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var servers: List<BluetoothServerSocket> = emptyList()
    @Volatile private var lastListenFailure = ""
    private var serverJob: Job? = null
    @Volatile private var listening = false
    var onFrame: ((ByteArray) -> Unit)? = null
    var onCar: ((String?) -> Unit)? = null
    var onStep: ((String) -> Unit)? = null

    val open: Boolean get() = socket?.isConnected == true

    fun start() {
        if (listening && serverJob?.isActive == true) return
        listening = true
        serverJob = scope.launch(Dispatchers.IO) { listen() }
    }

    fun stop() {
        listening = false
        serverJob?.cancel()
        serverJob = null
        closeServers()
        runCatching { socket?.close() }
        socket = null
    }

    fun send(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        return runCatching {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            DiagLog.tx(tag, "bluetooth", bytes)
            true
        }.getOrElse {
            DiagLog.w(tag, "could not write to the car over bluetooth: ${it.message}")
            false
        }
    }

    private fun closeServers() {
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
    }

    private suspend fun listen() {
        val a = adapter ?: return
        var moaned = false
        while (listening) {
            if (!a.isEnabled) {
                if (!moaned) {
                    moaned = true
                    onStep?.invoke("Turn on bluetooth")
                }
                delay(3000)
                continue
            }
            moaned = false
            val offers = listOf(CARLIFE_UUID to "CarLife", SPP_UUID to "SPP")
            val failures = ArrayList<String>()
            val offered = offers.mapNotNull { (uuid, label) ->
                runCatching { a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid) }
                    .onFailure { failures += "$label ($uuid): ${it.javaClass.simpleName} ${it.message ?: ""}" }
                    .getOrNull()?.let { it to label }
            }
            val failed = failures.joinToString("; ")
            if (failed.isNotEmpty() && failed != lastListenFailure) DiagLog.w(tag, "could not offer bluetooth service: $failed")
            lastListenFailure = failed
            if (offered.isEmpty()) {
                delay(3000)
                continue
            }
            servers = offered.map { it.first }
            DiagLog.i(tag, "offering '$SERVICE_NAME' on ${offered.joinToString(" and ") { it.second }}, waiting for the car to call")
            onStep?.invoke("Waiting for the car to call over bluetooth")
            val winner = CompletableDeferred<Pair<BluetoothSocket, String>?>()
            val waits = offered.map { (ss, label) ->
                scope.launch(Dispatchers.IO) {
                    val s = runCatching { ss.accept() }
                        .onFailure { if (listening && !winner.isCompleted) DiagLog.d(tag, "bluetooth $label stopped waiting: ${it.javaClass.simpleName} ${it.message ?: ""}") }
                        .getOrNull() ?: return@launch
                    if (!winner.complete(s to label)) runCatching { s.close() }
                }
            }
            scope.launch { waits.joinAll(); winner.complete(null) }
            val call = winner.await()
            closeServers()
            if (call == null) {
                delay(1000)
                continue
            }
            talk(call.first, call.second)
            delay(800)
        }
    }

    private fun talk(s: BluetoothSocket, label: String) {
        val who = runCatching { s.remoteDevice?.name }.getOrNull() ?: "The car"
        DiagLog.i(tag, "'$who' called this phone over bluetooth on $label")
        runCatching {
            val d = s.remoteDevice
            DiagLog.i(tag, "car bluetooth: address ${d?.address} class 0x${Integer.toHexString(d?.bluetoothClass?.deviceClass ?: 0)} bond ${d?.bondState} type ${d?.type} connection ${s.connectionType} max packet ${s.maxReceivePacketSize}")
        }
        socket = s
        runCatching { s.remoteDevice?.address }.getOrNull()?.let { prefs.carBtAddress = it }
        onCar?.invoke(who)
        val input = runCatching { s.inputStream }.getOrNull()
        val buf = ByteArray(512)
        var seen = 0
        var why = "the link ended"
        while (listening) {
            val n = runCatching { input?.read(buf) ?: -1 }.getOrElse { why = "${it.javaClass.simpleName} ${it.message ?: ""}"; -1 }
            if (n < 0) break
            if (n == 0) continue
            seen++
            val frame = buf.copyOf(n)
            if (seen <= 20 || seen % 30 == 0) DiagLog.rx(tag, "bluetooth #$seen", frame)
            onFrame?.invoke(frame)
        }
        runCatching { s.close() }
        if (socket === s) socket = null
        onCar?.invoke(null)
        DiagLog.i(tag, "bluetooth link to '$who' closed ($why, $seen messages)")
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CARLIFE_UUID: UUID = UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78")
        private const val SERVICE_NAME = "carlife"
    }
}
