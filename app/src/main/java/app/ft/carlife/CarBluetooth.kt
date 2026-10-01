package app.ft.carlife

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class CarBluetooth(context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val tag = "CarBT"
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var servers: List<android.bluetooth.BluetoothServerSocket> = emptyList()
    private var serverJob: Job? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onProbe: (() -> Unit)? = null
    var onStep: ((String) -> Unit)? = null
    @Volatile private var heard = false

    val open: Boolean get() = socket?.isConnected == true

    fun send(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        return runCatching {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            DiagLog.tx(tag, "bluetooth reply", bytes)
            true
        }.getOrElse {
            DiagLog.w(tag, "bluetooth write failed: ${it.message}")
            false
        }
    }

    fun start(deviceAddress: String? = null) {
        if (serverJob?.isActive != true) serverJob = scope.launch(Dispatchers.IO) { listen() }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun listen() {
        val a = adapter ?: return
        var moaned = false
        while (scope.isActive && serverJob?.isActive == true) {
            if (!a.isEnabled) {
                if (!moaned) {
                    moaned = true
                    onStep?.invoke("Switch bluetooth on so the car can call this phone")
                }
                delay(4000)
                continue
            }
            moaned = false
            val offers = listOf(CARLIFE_UUID to "CarLife", SPP_UUID to "SPP")
            val sockets = offers.mapNotNull { (uuid, label) ->
                runCatching { a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid) }.getOrNull()?.let { it to label }
            }
            if (sockets.isEmpty()) {
                delay(4000)
                continue
            }
            servers = sockets.map { it.first }
            onStep?.invoke("Waiting for the car to call this phone")
            DiagLog.i(tag, "offering '$SERVICE_NAME' on ${sockets.joinToString(" and ") { it.second }}, waiting for the head unit to connect")
            val taken = java.util.concurrent.atomic.AtomicBoolean(false)
            val waits = sockets.map { (ss, label) ->
                scope.launch(Dispatchers.IO) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: return@launch
                    if (!taken.compareAndSet(false, true)) {
                        runCatching { s.close() }
                        return@launch
                    }
                    val who = runCatching { s.remoteDevice?.name }.getOrNull() ?: "the car"
                    DiagLog.i(tag, "'$who' called this phone over bluetooth on $label")
                    onStep?.invoke("'$who' called this phone")
                    runCatching { socket?.close() }
                    socket = s
                    prefs.carBtAddress = runCatching { s.remoteDevice?.address }.getOrNull() ?: prefs.carBtAddress
                    onProbe?.invoke()
                    keepAlive()
                }
            }
            waits.forEach { it.join() }
            servers.forEach { runCatching { it.close() } }
            servers = emptyList()
            delay(1500)
        }
    }

    fun write(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        return runCatching {
            val o = s.outputStream
            o.write(bytes)
            o.flush()
            true
        }.getOrElse {
            DiagLog.w(tag, "could not write to the head unit over bluetooth: ${it.message}")
            false
        }
    }

    private suspend fun keepAlive() {
        val s = socket ?: return
        val input = runCatching { s.inputStream }.getOrNull()
        val buf = ByteArray(256)
        var seen = 0
        while (scope.isActive && serverJob?.isActive == true && s.isConnected) {
            val n = runCatching { input?.read(buf) ?: -1 }.getOrElse { -1 }
            if (n < 0) break
            if (n > 0) {
                seen++
                val frame = buf.copyOf(n)
                if (seen <= 20 || seen % 30 == 0) DiagLog.rx(tag, "bluetooth frame #$seen", frame)
                onFrame?.invoke(frame)
            }
        }
        runCatching { s.close() }
        if (socket === s) socket = null
        DiagLog.i(tag, "bluetooth serial link closed")
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CARLIFE_UUID: UUID = UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78")
        private const val AA_WIRELESS_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
        private const val PROBE_NS = 4_000_000_000L
        private const val SERVICE_NAME = "carlife"
    }
}
