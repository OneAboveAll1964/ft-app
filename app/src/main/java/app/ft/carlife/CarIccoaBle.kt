package app.ft.carlife

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import app.ft.core.DiagLog
import org.json.JSONObject
import java.util.UUID

data class CarWifiOffer(val ssid: String, val psk: String, val ip: String, val port: Int)

@SuppressLint("MissingPermission")
class CarIccoaBle(
    private val context: Context,
    private val onOffer: (CarWifiOffer) -> Unit,
    private val onStep: (String) -> Unit
) {
    private val tag = "CarBLE"
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var scanning = false
    private val text = StringBuilder()

    private val seen = HashSet<String>()

    private val scanner = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val offered = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val name = runCatching { device.name }.getOrNull() ?: device.address
            val interesting = offered.any { it == SERVICE_FE2C || it == SERVICE_FCFB || it == SERVICE }
            if (seen.add(device.address) && offered.isNotEmpty()) {
                DiagLog.d(tag, "nearby: '$name' offers ${offered.joinToString(", ")}")
            }
            if (!interesting) return
            DiagLog.i(tag, "'$name' is offering the car connection service over bluetooth")
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLog.w(tag, "could not look for the car over bluetooth (code $errorCode)")
            scanning = false
        }
    }

    fun start(known: BluetoothDevice?) {
        val a = adapter ?: return
        if (!a.isEnabled) return
        if (gatt != null) return
        val le = a.bluetoothLeScanner ?: return
        if (scanning) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val located = lm?.isLocationEnabled ?: true
        if (!located) {
            onStep("Switch location on so this phone can see the car")
            DiagLog.w(tag, "location is off, so android will not report any bluetooth device nearby")
            return
        }
        scanning = true
        seen.clear()
        onStep("Looking for the car over bluetooth")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { le.startScan(emptyList<ScanFilter>(), settings, scanner) }
            .onFailure { DiagLog.w(tag, "bluetooth scan refused: ${it.message}"); scanning = false }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanner) }
    }

    fun stop() {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        text.setLength(0)
    }

    private fun connect(device: BluetoothDevice) {
        val who = runCatching { device.name }.getOrNull() ?: device.address
        onStep("Asking '$who' for the car's network")
        DiagLog.i(tag, "opening a bluetooth connection to '$who'")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                DiagLog.i(tag, "connected to the car over bluetooth, asking for a bigger message size")
                runCatching { g.requestMtu(512) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                DiagLog.i(tag, "the car's bluetooth connection closed")
                runCatching { g.close() }
                if (gatt === g) gatt = null
                text.setLength(0)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            DiagLog.d(tag, "message size is now $mtu, looking at what the car offers")
            runCatching { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: run {
                DiagLog.w(tag, "the car does not offer the connection service over bluetooth")
                return
            }
            val listen = service.getCharacteristic(SERVER_INFO)
            if (listen != null) runCatching { g.setCharacteristicNotification(listen, true) }
            val tell = service.getCharacteristic(CLIENT_INFO) ?: run {
                DiagLog.w(tag, "the car will not take this phone's details")
                return
            }
            val body = JSONObject()
                .put("id", "FT_PHONE_01")
                .put("name", "FT Phone")
                .put("model", Build.MODEL ?: "Android")
                .put("band", 2)
                .put("mac", "02:00:00:00:00:00")
                .put("type", 1001)
                .toString()
            onStep("Telling the car about this phone")
            DiagLog.i(tag, "telling the car about this phone: $body")
            @Suppress("DEPRECATION")
            run {
                tell.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                tell.value = body.toByteArray()
                g.writeCharacteristic(tell)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid != CLIENT_INFO) return
            DiagLog.i(tag, "the car took this phone's details, now asking it for the network")
            val listen = g.getService(SERVICE)?.getCharacteristic(SERVER_INFO) ?: return
            val cccd = listen.getDescriptor(CCCD) ?: return
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid != SERVER_INFO) return
            val part = c.value ?: return
            take(String(part, Charsets.UTF_8))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != SERVER_INFO) return
            take(String(value, Charsets.UTF_8))
        }
    }

    private fun take(chunk: String) {
        text.append(chunk)
        val whole = text.toString()
        val start = whole.indexOf('{')
        val end = whole.lastIndexOf('}')
        if (start < 0 || end <= start) return
        val json = runCatching { JSONObject(whole.substring(start, end + 1)) }.getOrNull() ?: return
        text.setLength(0)
        val ssid = json.optString("ssid").ifBlank { json.optString("SSID") }
        val psk = json.optString("psk").ifBlank { json.optString("password") }
        val ip = json.optString("ip").ifBlank { json.optString("IP") }
        val port = json.optInt("port", json.optInt("Port", 0))
        if (ssid.isBlank()) {
            DiagLog.i(tag, "the car said: $json")
            return
        }
        DiagLog.i(tag, "the car offered its network '$ssid' at $ip:$port")
        onStep("The car offered its network '$ssid'")
        onOffer(CarWifiOffer(ssid, psk, ip, port))
    }

    companion object {
        private val SERVICE_FE2C: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
        private val SERVICE_FCFB: UUID = UUID.fromString("0000FCFB-0000-1000-8000-00805F9B34FB")
        private val SERVICE: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100")
        private val CLIENT_INFO: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101")
        private val SERVER_INFO: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
