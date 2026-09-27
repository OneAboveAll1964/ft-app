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
    @Volatile private var servers: List<android.bluetooth.BluetoothServerSocket> = emptyList()
    private var serverJob: Job? = null
    private val described = HashSet<String>()
    @Volatile private var target: String? = null
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
        if (job?.isActive == true) return
        target = deviceAddress
        if (serverJob?.isActive != true) serverJob = scope.launch(Dispatchers.IO) { listen() }
    }

    fun stop() {
        job?.cancel()
        job = null
        serverJob?.cancel()
        serverJob = null
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
        described.clear()
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

    private suspend fun loop() {
        val a = adapter
        if (a == null) {
            DiagLog.w(tag, "this phone has no bluetooth, so the head unit cannot be asked to raise WiFi Direct")
            return
        }
        var moaned = false
        while (scope.isActive && job?.isActive == true) {
            if (!a.isEnabled) {
                if (!moaned) {
                    moaned = true
                    onStep?.invoke("Waiting for bluetooth to be switched on")
                    DiagLog.w(tag, "bluetooth is off, FT will pick up as soon as you switch it on")
                }
                delay(4000)
                continue
            }
            if (moaned) {
                moaned = false
                DiagLog.i(tag, "bluetooth is on again, looking for the car")
            }
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
        val rest = bonded.filter { it !in named }.filter { rank(it) > 0 }.sortedByDescending { rank(it) }
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
        if (!couldBeACar(device)) return 0
        val offered = services(device)
        val base = when {
            offered.any { it == CARLIFE_UUID.toString().lowercase() } -> 6
            offered.any { it == AA_WIRELESS_UUID } -> 5
            offered.any { it == SPP_UUID.toString().lowercase() } -> 4
            else -> 2
        }
        return if (isConnected(device)) base + 10 else base
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        m.invoke(device) as Boolean
    }.getOrDefault(false)

    private fun connect(a: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull() ?: device.address
        runCatching { a.cancelDiscovery() }
        onStep?.invoke("Trying '$name' over bluetooth")
        describe(device, name)
        if (!couldBeACar(device) && prefs.carBtAddress.isBlank()) {
            DiagLog.d(tag, "skipping '$name', it offers no serial link")
            return false
        }
        for (attempt in attempts(device)) {
            val s = runCatching { attempt.first() }.getOrNull() ?: continue
            try {
                s.connect()
                socket = s
                onStep?.invoke("Connected to '$name' over bluetooth")
                DiagLog.i(tag, "bluetooth serial link open to '$name' via ${attempt.second}")
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
        private const val SERVICE_NAME = "carlife"
    }
}
