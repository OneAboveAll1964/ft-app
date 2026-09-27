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
    @Volatile private var server: android.bluetooth.BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private val described = HashSet<String>()
    @Volatile private var target: String? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onProbe: (() -> Unit)? = null
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
        if (job?.isActive == true) return
        target = deviceAddress
        job = scope.launch(Dispatchers.IO) { loop() }
        if (serverJob?.isActive != true) serverJob = scope.launch(Dispatchers.IO) { listen() }
    }

    fun stop() {
        job?.cancel()
        job = null
        serverJob?.cancel()
        serverJob = null
        runCatching { server?.close() }
        server = null
        described.clear()
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun listen() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        val ss = runCatching { a.listenUsingRfcommWithServiceRecord("FT", CARLIFE_UUID) }.getOrNull()
            ?: runCatching { a.listenUsingRfcommWithServiceRecord("FT", SPP_UUID) }.getOrNull()
            ?: return
        server = ss
        DiagLog.i(tag, "offering FT's own CarLife service so the head unit can dial this phone")
        while (scope.isActive && serverJob?.isActive == true) {
            val s = runCatching { ss.accept() }.getOrNull() ?: break
            DiagLog.i(tag, "head unit dialled this phone over bluetooth")
            runCatching { socket?.close() }
            socket = s
            keepAlive()
        }
        runCatching { ss.close() }
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
                val list = candidates(a)
                if (list.isEmpty()) {
                    DiagLog.d(tag, "no paired device here offers a serial link, so none of them can raise WiFi Direct")
                } else {
                    for (device in list) {
                        if (job?.isActive != true) break
                        if (connect(a, device)) {
                            if (speaksCarLife()) {
                                prefs.carBtAddress = device.address
                                keepAlive()
                            } else {
                                val who = runCatching { device.name }.getOrNull() ?: device.address
                                DiagLog.i(tag, "'$who' answered but said nothing a head unit would say, trying the next one")
                                runCatching { socket?.close() }
                                socket = null
                                if (prefs.carBtAddress.equals(device.address, true)) prefs.carBtAddress = ""
                                continue
                            }
                            break
                        }
                    }
                }
            }
            delay(4000)
        }
        runCatching { socket?.close() }
        socket = null
    }

    private fun candidates(a: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty()
        val named = ArrayList<BluetoothDevice>()
        val remembered = prefs.carBtAddress.trim()
        if (remembered.isNotEmpty()) bonded.firstOrNull { it.address.equals(remembered, true) }?.let { named.add(it) }
        target?.let { addr -> bonded.firstOrNull { it.address.equals(addr, true) }?.let { if (it !in named) named.add(it) } }
        val want = prefs.carBtName.trim()
        if (want.isNotEmpty()) {
            bonded.filter { runCatching { it.name }.getOrNull()?.contains(want, true) == true }
                .forEach { if (it !in named) named.add(it) }
        }
        val rest = bonded.filter { it !in named }.sortedByDescending { rank(it) }.filter { rank(it) > 0 }
        return named + rest
    }

    private fun services(device: BluetoothDevice): List<String> =
        runCatching { device.uuids?.map { it.uuid.toString().lowercase() } }.getOrNull().orEmpty()

    private fun couldBeACar(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return false
        return major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
            major == android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED
    }

    private fun rank(device: BluetoothDevice): Int {
        val offered = services(device)
        if (!couldBeACar(device)) return 0
        return when {
            offered.any { it == CARLIFE_UUID.toString().lowercase() } -> 4
            offered.any { it == AA_WIRELESS_UUID } -> 3
            offered.any { it == SPP_UUID.toString().lowercase() } -> 2
            else -> 0
        }
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        m.invoke(device) as Boolean
    }.getOrDefault(false)

    private fun connect(a: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull() ?: device.address
        runCatching { a.cancelDiscovery() }
        describe(device, name)
        if (rank(device) == 0 && prefs.carBtAddress.isBlank()) {
            DiagLog.d(tag, "skipping '$name', it offers no serial link")
            return false
        }
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

    private fun describe(device: BluetoothDevice, name: String) {
        if (!described.add(device.address)) return
        runCatching { device.fetchUuidsWithSdp() }
        val offered = runCatching { device.uuids?.map { it.uuid.toString() } }.getOrNull().orEmpty()
        if (offered.isEmpty()) {
            DiagLog.i(tag, "'$name' lists no bluetooth services, so FT will try the CarLife one and then each channel")
        } else {
            DiagLog.i(tag, "'$name' offers ${offered.size} bluetooth services: ${offered.joinToString(", ")}")
        }
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
    ) + (2..12).map { ch ->
        ({
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, ch) as BluetoothSocket
        } to "channel $ch")
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

    private suspend fun speaksCarLife(): Boolean {
        val s = socket ?: return false
        onProbe?.invoke()
        val input = runCatching { s.inputStream }.getOrNull() ?: return false
        val buf = ByteArray(256)
        val until = System.nanoTime() + PROBE_NS
        while (System.nanoTime() < until && scope.isActive) {
            val ready = runCatching { input.available() }.getOrDefault(0)
            if (ready > 0) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n > 0) {
                    heard = true
                    onFrame?.invoke(buf.copyOf(n))
                    return true
                }
                if (n < 0) return false
            }
            delay(200)
        }
        return false
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
        private const val AA_WIRELESS_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
        private const val PROBE_NS = 4_000_000_000L
    }
}
