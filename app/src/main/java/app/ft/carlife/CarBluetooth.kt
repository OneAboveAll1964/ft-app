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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class CarBluetooth(context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val tag = "CarBT"
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    @Volatile private var listener: Listener? = null
    @Volatile private var lastListenFailure = ""
    private var serverJob: Job? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onCar: ((String?) -> Unit)? = null
    var onStep: ((String) -> Unit)? = null

    val open: Boolean get() = listener?.socket?.isConnected == true

    @Synchronized
    fun start() {
        if (serverJob?.isActive == true) return
        listener?.close()
        val owner = Listener()
        listener = owner
        serverJob = scope.launch(Dispatchers.IO) {
            coroutineScope {
                // Cancellation must close blocking accept/read before waiting for their jobs.
                val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { owner.close() }
                }
                try { listen(owner) } finally { owner.close(); cleanup.cancel() }
            }
        }
    }

    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        listener?.close()
        listener = null
    }

    fun send(bytes: ByteArray): Boolean {
        val s = listener?.socket ?: return false
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

    private class Listener {
        private var closed = false
        private var servers: List<BluetoothServerSocket> = emptyList()
        private val accepted = HashSet<BluetoothSocket>()
        @Volatile var socket: BluetoothSocket? = null
            private set

        @Synchronized fun offer(next: List<BluetoothServerSocket>) {
            if (closed) next.forEach { runCatching { it.close() } } else servers = next
        }
        @Synchronized fun accept(s: BluetoothSocket): Boolean {
            if (closed) { runCatching { s.close() }; return false }
            accepted.add(s)
            return true
        }
        @Synchronized fun release(s: BluetoothSocket) {
            if (accepted.remove(s)) runCatching { s.close() }
            if (socket === s) socket = null
        }
        @Synchronized fun connect(s: BluetoothSocket): Boolean {
            if (closed || s !in accepted) return false
            socket = s
            return true
        }
        @Synchronized fun closeServers() {
            servers.forEach { runCatching { it.close() } }
            servers = emptyList()
        }
        @Synchronized fun close() {
            closed = true
            closeServers()
            accepted.toList().forEach { release(it) }
        }
    }

    private suspend fun listen(owner: Listener) {
        val a = adapter ?: return
        var moaned = false
        while (currentCoroutineContext().isActive) {
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
            owner.offer(offered.map { it.first })
            DiagLog.i(tag, "offering '$SERVICE_NAME' on ${offered.joinToString(" and ") { it.second }}, waiting for the car to call")
            onStep?.invoke("Waiting for the car to call over bluetooth")
            val call = coroutineScope {
                val winner = CompletableDeferred<Pair<BluetoothSocket, String>?>()
                val waits = offered.map { (ss, label) ->
                    launch(Dispatchers.IO) {
                        val s = runCatching { ss.accept() }
                            .onFailure { if (isActive && !winner.isCompleted) DiagLog.d(tag, "bluetooth $label stopped waiting: ${it.javaClass.simpleName} ${it.message ?: ""}") }
                            .getOrNull() ?: return@launch
                        if (owner.accept(s) && !winner.complete(s to label)) owner.release(s)
                    }
                }
                launch { waits.joinAll(); winner.complete(null) }
                try { winner.await() } finally { owner.closeServers() }
            }
            if (call == null) {
                delay(1000)
                continue
            }
            try {
                talk(owner, call.first, call.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.e(tag, "bluetooth session failed, waiting for the car to reconnect", e)
            }
            delay(800)
        }
    }

    private suspend fun talk(owner: Listener, s: BluetoothSocket, label: String) {
        val who = runCatching { s.remoteDevice?.name }.getOrNull() ?: "The car"
        DiagLog.i(tag, "'$who' called this phone over bluetooth on $label")
        runCatching {
            val d = s.remoteDevice
            DiagLog.i(tag, "car bluetooth: address ${d?.address} class 0x${Integer.toHexString(d?.bluetoothClass?.deviceClass ?: 0)} bond ${d?.bondState} type ${d?.type} connection ${s.connectionType} max packet ${s.maxReceivePacketSize}")
        }
        if (!owner.connect(s)) return
        try {
            if (!currentCoroutineContext().isActive) return
            runCatching { s.remoteDevice?.address }.getOrNull()?.let { prefs.carBtAddress = it }
            onCar?.invoke(who)
            val input = runCatching { s.inputStream }.getOrNull()
            val buf = ByteArray(512)
            var seen = 0
            var why = "the link ended"
            while (currentCoroutineContext().isActive) {
                val n = runCatching { input?.read(buf) ?: -1 }.getOrElse { why = "${it.javaClass.simpleName} ${it.message ?: ""}"; -1 }
                if (n < 0) break
                if (n == 0) continue
                seen++
                val frame = buf.copyOf(n)
                if (seen <= 20 || seen % 30 == 0) DiagLog.rx(tag, "bluetooth #$seen", frame)
                onFrame?.invoke(frame)
            }
            DiagLog.i(tag, "bluetooth link to '$who' closed ($why, $seen messages)")
        } finally {
            owner.release(s)
            if (listener === owner) onCar?.invoke(null)
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CARLIFE_UUID: UUID = UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78")
        private const val SERVICE_NAME = "carlife"
    }
}
