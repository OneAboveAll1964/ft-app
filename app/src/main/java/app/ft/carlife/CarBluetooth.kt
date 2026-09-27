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
    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var target: String? = null
    var onFrame: ((ByteArray) -> Unit)? = null

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
        if (job?.isActive == true) return
        target = deviceAddress
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun loop() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            DiagLog.w(tag, "bluetooth is off; turn it on and connect to the car so the head unit raises WiFi Direct")
            return
        }
        while (scope.isActive && job?.isActive == true) {
            if (socket?.isConnected != true) {
                runCatching { socket?.close() }
                socket = null
                val device = pickDevice(a)
                if (device == null) {
                    DiagLog.d(tag, "no bonded car device to poke over bluetooth yet")
                } else if (connect(a, device)) {
                    keepAlive()
                }
            }
            delay(4000)
        }
        runCatching { socket?.close() }
        socket = null
    }

    private fun pickDevice(a: BluetoothAdapter): BluetoothDevice? {
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty()
        val want = prefs.carBtName.trim()
        target?.let { addr -> bonded.firstOrNull { it.address.equals(addr, true) }?.let { return it } }
        if (want.isNotEmpty()) {
            bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains(want, true) == true }?.let { return it }
        }
        return bonded.firstOrNull { isConnected(it) } ?: bonded.firstOrNull()
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        m.invoke(device) as Boolean
    }.getOrDefault(false)

    private fun connect(a: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull() ?: device.address
        runCatching { a.cancelDiscovery() }
        for (attempt in attempts(device)) {
            val s = runCatching { attempt.first() }.getOrNull() ?: continue
            try {
                s.connect()
                socket = s
                DiagLog.i(tag, "bluetooth serial link open to '$name' via ${attempt.second}; head unit should raise WiFi Direct now")
                return true
            } catch (t: Throwable) {
                runCatching { s.close() }
                DiagLog.d(tag, "bluetooth connect to '$name' via ${attempt.second} failed: ${t.message}")
            }
        }
        return false
    }

    private fun attempts(device: BluetoothDevice): List<Pair<() -> BluetoothSocket, String>> = listOf(
        ({ device.createRfcommSocketToServiceRecord(CARLIFE_UUID) } to "CarLife service"),
        ({ device.createInsecureRfcommSocketToServiceRecord(CARLIFE_UUID) } to "CarLife service insecure"),
        ({ device.createRfcommSocketToServiceRecord(SPP_UUID) } to "SPP"),
        ({ device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) } to "SPP insecure"),
        ({
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, 1) as BluetoothSocket
        } to "channel 1")
    )

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
        while (scope.isActive && job?.isActive == true && s.isConnected) {
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
    }
}
