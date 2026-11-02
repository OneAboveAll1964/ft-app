package app.ft.iccoa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.ft.core.DiagLog

data class IccoaPeer(val address: String, val name: String, val rssi: Int, val connectable: Boolean, val hint: Boolean)

enum class IccoaPhase { IDLE, SCANNING, SCAN_COMPLETE, INSPECTING, GATT_COMPLETE, BLUETOOTH_OFF, PERMISSION_REQUIRED, LOCATION_OFF, FAILED }

data class IccoaDiscoveryState(
    val phase: IccoaPhase = IccoaPhase.IDLE,
    val peers: List<IccoaPeer> = emptyList(),
    val inspected: String = "",
    val reportedServiceFound: Boolean = false
)

/** BLE discovery and optional GATT inspection. Pairing belongs to IccoaSession. State lives on main. */
@SuppressLint("MissingPermission")
class IccoaDiscovery(private val context: Context, private val onState: (IccoaDiscoveryState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var state = IccoaDiscoveryState()
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var samples = AdvertisementSamples()
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private val peers = LinkedHashMap<String, IccoaPeer>()
    private var generation = 0
    private var lastPublish = 0L
    private val scanTimeout = Runnable { finishScan() }
    private val gattTimeout = Runnable { failGatt("GATT discovery timed out") }

    fun start() {
        stop()
        samples = AdvertisementSamples()
        devices.clear()
        peers.clear()
        state = IccoaDiscoveryState()
        lastPublish = 0L
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            publish(IccoaPhase.PERMISSION_REQUIRED)
            DiagLog.w(TAG, "BLE scan needs Nearby devices permission (Location on Android 10/11)")
            return
        }
        if (runCatching { adapter?.isEnabled }.getOrDefault(false) != true) {
            publish(IccoaPhase.BLUETOOTH_OFF)
            return
        }
        if (Build.VERSION.SDK_INT < 31 && context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            publish(IccoaPhase.LOCATION_OFF)
            return
        }
        val token = generation
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { if (token == generation && callback === this) record(result) } }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { main.post { if (token == generation && callback === this) results.forEach(::record) } }
            override fun onScanFailed(errorCode: Int) { main.post {
                if (token != generation || callback !== this) return@post
                endScan()
                DiagLog.w(TAG, "BLE scan failed: Android error $errorCode")
                publish(IccoaPhase.FAILED)
            } }
        }
        callback = cb
        runCatching {
            scanner = adapter?.bluetoothLeScanner ?: error("No BLE scanner")
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
            publish(IccoaPhase.SCANNING)
            DiagLog.i(TAG, "Starting 60s unfiltered BLE evidence scan; phone=${Build.MODEL} build=${Build.DISPLAY}; manifest neverForLocation may filter some beacons. No ICCOA session is established.")
            scanner!!.startScan(null, settings, cb)
            main.postDelayed(scanTimeout, 60_000)
        }.onFailure {
            endScan()
            DiagLog.w(TAG, "Cannot start BLE scan: ${it.javaClass.simpleName} ${it.message}")
            publish(if (it is SecurityException) IccoaPhase.PERMISSION_REQUIRED else IccoaPhase.FAILED)
        }
    }

    private fun record(result: ScanResult) {
        runCatching {
            val address = result.device.address
            if (address !in devices && devices.size >= 128) return
            devices[address] = result.device
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name.orEmpty()
            val services = record?.serviceUuids.orEmpty().map { it.toString() }
            val serviceData = record?.serviceData.orEmpty().entries.joinToString { "${it.key}=${hex(it.value)}" }
            val manufacturer = record?.manufacturerSpecificData?.let { data ->
                (0 until data.size()).joinToString { "${data.keyAt(it)}=${hex(data.valueAt(it))}" }
            }.orEmpty()
            val raw = hex(record?.bytes ?: byteArrayOf())
            val hint = IccoaEvidence.hint(name, services + record?.serviceData.orEmpty().keys.map { it.toString() })
            val old = peers[address]
            peers[address] = IccoaPeer(address, name.ifBlank { old?.name.orEmpty() }, result.rssi, result.isConnectable, hint || old?.hint == true)
            if (samples.record(address, raw)) {
                DiagLog.i(TAG, "ADV address=$address name='$name' rssi=${result.rssi} connectable=${result.isConnectable} hint=$hint timestampNanos=${result.timestampNanos} services=$services serviceData=[$serviceData] manufacturer=[$manufacturer] raw=$raw")
            }
            val now = SystemClock.elapsedRealtime()
            if (old == null || now - lastPublish >= 1_000) {
                lastPublish = now
                publish()
            }
        }.onFailure {
            if (it is SecurityException) {
                endScan()
                publish(IccoaPhase.PERMISSION_REQUIRED)
            }
            DiagLog.w(TAG, "Cannot read BLE advertisement: ${it.javaClass.simpleName} ${it.message}")
        }
    }

    /** User-selected device only. Enumerate GATT metadata, without reading/writing values or bonding. */
    fun inspect(address: String) {
        val device = devices[address] ?: return
        if (peers[address]?.connectable != true || state.phase == IccoaPhase.INSPECTING) return
        endScan()
        closeGatt()
        val token = generation
        state = state.copy(inspected = address, reportedServiceFound = false)
        publish(IccoaPhase.INSPECTING)
        DiagLog.i(TAG, "User selected GATT discovery for $address; enumerate services only, no writes or pairing")
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) { main.post {
                if (token != generation || link !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    failGatt("GATT disconnected: status=$status state=$newState")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { check(link.discoverServices()) { "discoverServices returned false" } }
                        .onFailure { failGatt("Cannot discover services: ${it.message}") }
                }
            } }

            override fun onServicesDiscovered(link: BluetoothGatt, status: Int) { main.post {
                if (token != generation || link !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { failGatt("GATT services status=$status"); return@post }
                runCatching {
                    val services = link.services
                    val found = services.any { it.uuid.toString().equals(IccoaEvidence.REPORTED_SERVICE, ignoreCase = true) }
                    services.forEach { service ->
                        DiagLog.i(TAG, "GATT $address service=${service.uuid} type=${service.type}")
                        service.characteristics.forEach { ch ->
                            DiagLog.i(TAG, "GATT $address characteristic=${ch.uuid} properties=0x${ch.properties.toString(16)} permissions=0x${ch.permissions.toString(16)} descriptors=${ch.descriptors.map { it.uuid }}")
                        }
                    }
                    DiagLog.i(TAG, "GATT discovery complete for $address: ${services.size} services; reported ICCOA service=$found (not an authenticated ICCOA connection)")
                    state = state.copy(reportedServiceFound = found)
                    closeGatt()
                    publish(IccoaPhase.GATT_COMPLETE)
                }.onFailure {
                    failGatt("Cannot enumerate services: ${it.javaClass.simpleName} ${it.message}", it is SecurityException)
                }
            } }
        }
        runCatching {
            gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) ?: error("connectGatt returned null")
            main.postDelayed(gattTimeout, 15_000)
        }.onFailure { failGatt("Cannot connect GATT: ${it.javaClass.simpleName} ${it.message}", it is SecurityException) }
    }

    private fun finishScan() {
        if (state.phase != IccoaPhase.SCANNING) return
        endScan()
        DiagLog.i(TAG, "BLE scan complete: ${peers.size} devices, ${peers.values.count { it.hint }} hints. At most 8 distinct advertisement samples per device, 128 devices.")
        publish(IccoaPhase.SCAN_COMPLETE)
    }

    private fun failGatt(reason: String, permissionDenied: Boolean = false) {
        DiagLog.w(TAG, reason)
        closeGatt()
        publish(if (permissionDenied) IccoaPhase.PERMISSION_REQUIRED else IccoaPhase.FAILED)
    }

    private fun publish(phase: IccoaPhase = state.phase) {
        state = state.copy(phase = phase, peers = peers.values.sortedWith(compareByDescending<IccoaPeer> { it.hint }.thenByDescending { it.rssi }))
        onState(state)
    }

    private fun endScan() {
        main.removeCallbacks(scanTimeout)
        val old = callback
        callback = null
        if (old != null) runCatching { scanner?.stopScan(old) }
        scanner = null
    }

    private fun closeGatt() {
        main.removeCallbacks(gattTimeout)
        val old = gatt
        gatt = null
        if (old != null) {
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
    }

    fun stop() {
        generation++
        endScan()
        closeGatt()
        if (state.phase == IccoaPhase.SCANNING || state.phase == IccoaPhase.INSPECTING) {
            publish(IccoaPhase.SCAN_COMPLETE)
        }
    }

    companion object {
        private const val TAG = "ICCOA"
        private val HEX = "0123456789abcdef".toCharArray()
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { b -> val n = b.toInt() and 255; append(HEX[n ushr 4]); append(HEX[n and 15]) }
        }
    }
}
