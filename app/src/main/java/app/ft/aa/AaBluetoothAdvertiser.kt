package app.ft.aa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class HotspotInfo(val ssid: String, val password: String, val ip: String)

@SuppressLint("MissingPermission")
class AaBluetoothAdvertiser(private val context: Context, private val port: Int) {
    private val tag = "AA-BT"
    private val running = AtomicBoolean(false)
    private var server: BluetoothServerSocket? = null
    private var job: Job? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private val _info = MutableStateFlow<HotspotInfo?>(null)
    val info: StateFlow<HotspotInfo?> = _info
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        startHotspot()
        job = scope.launch(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                _status.value = "bluetooth off"
                DiagLog.w(tag, "bluetooth unavailable")
                return@launch
            }
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord("Android Auto Wireless", UUID.fromString(AaProtocol.BT_UUID))
                server = ss
                _status.value = "advertising"
                DiagLog.i(tag, "RFCOMM listening with AA wireless UUID")
                while (isActive && running.get()) {
                    val s = ss.accept()
                    DiagLog.i(tag, "phone ${s.remoteDevice.name} connected over BT")
                    handle(s)
                }
            } catch (t: Throwable) {
                if (running.get()) DiagLog.w(tag, "BT server ended: ${t.message}")
                _status.value = "stopped"
            }
        }
    }

    private fun startHotspot() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    val cfg = reservation.softApConfiguration
                    val ssid = cfg.wifiSsid?.toString()?.trim('"') ?: cfg.ssid ?: "FT"
                    val pass = cfg.passphrase ?: ""
                    val ip = app.ft.carlife.NetUtil.localIpv4() ?: "192.168.43.1"
                    _info.value = HotspotInfo(ssid, pass, ip)
                    DiagLog.i(tag, "hotspot ready ssid=$ssid ip=$ip")
                }

                override fun onFailed(reason: Int) {
                    DiagLog.w(tag, "hotspot failed $reason")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            DiagLog.w(tag, "hotspot unavailable: ${t.message}")
        }
    }

    private fun handle(s: BluetoothSocket) {
        try {
            val i = s.inputStream
            val o = s.outputStream
            val hs = _info.value
            val ip = hs?.ip ?: app.ft.carlife.NetUtil.localIpv4() ?: "192.168.43.1"
            write(o, AaProtocol.BT_WIFI_START_REQUEST, ProtoWriter().string(1, ip).int32(2, port).toByteArray())
            while (running.get() && s.isConnected) {
                val h = ByteArray(4)
                Bytes.readFully(i, h)
                val len = Bytes.u16(h, 0)
                val type = Bytes.u16(h, 2)
                val body = ByteArray(len)
                Bytes.readFully(i, body)
                DiagLog.rx(tag, "bt type=$type", body)
                when (type) {
                    AaProtocol.BT_WIFI_INFO_REQUEST -> {
                        val info = _info.value
                        write(
                            o, AaProtocol.BT_WIFI_INFO_RESPONSE,
                            ProtoWriter()
                                .string(1, info?.ssid ?: "FT")
                                .string(2, info?.password ?: "")
                                .string(3, "")
                                .enum(4, AaProtocol.WIFI_SECURITY_WPA2_PERSONAL)
                                .enum(5, AaProtocol.WIFI_AP_DYNAMIC)
                                .toByteArray()
                        )
                    }
                    AaProtocol.BT_WIFI_VERSION_REQUEST -> write(o, AaProtocol.BT_WIFI_VERSION_RESPONSE, ByteArray(0))
                    AaProtocol.BT_WIFI_CONNECT_STATUS -> DiagLog.i(tag, "phone wifi connect status received")
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            DiagLog.d(tag, "bt session ended: ${t.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    private fun write(o: java.io.OutputStream, type: Int, body: ByteArray) {
        val f = ByteArray(4 + body.size)
        Bytes.putU16(body.size, f, 0)
        Bytes.putU16(type, f, 2)
        System.arraycopy(body, 0, f, 4, body.size)
        DiagLog.tx(tag, "bt type=$type", body)
        o.write(f)
        o.flush()
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        runCatching { hotspot?.close() }
        hotspot = null
        _info.value = null
        _status.value = "idle"
        job?.cancel()
    }
}
