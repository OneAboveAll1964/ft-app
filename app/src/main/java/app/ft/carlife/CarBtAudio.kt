package app.ft.carlife

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.ft.core.DiagLog

@SuppressLint("MissingPermission")
class CarBtAudio(private val context: Context) {
    private val tag = "CarBT"
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile
    private var a2dp: BluetoothProfile? = null

    fun open() {
        val a = adapter ?: return
        if (a2dp != null || !a.isEnabled) return
        runCatching {
            a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) a2dp = proxy
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null
                }
            }, BluetoothProfile.A2DP)
        }
    }

    fun close() {
        val a = adapter ?: return
        a2dp?.let { runCatching { a.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        a2dp = null
    }

    fun somethingIsPlaying(): BluetoothDevice? =
        runCatching { a2dp?.connectedDevices?.firstOrNull() }.getOrNull()

    fun theCar(preferredName: String, address: String): BluetoothDevice? {
        val a = adapter ?: return null
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty()
        val want = address.trim()
        if (want.isNotEmpty()) bonded.firstOrNull { it.address.equals(want, true) }?.let { return it }
        val name = preferredName.trim()
        if (name.isNotEmpty()) {
            bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains(name, true) == true }?.let { return it }
        }
        return null
    }

    fun askCarToPlay(device: BluetoothDevice): Boolean {
        val proxy = a2dp ?: return false
        if (runCatching { proxy.getConnectionState(device) }.getOrNull() == BluetoothProfile.STATE_CONNECTED) return true
        return runCatching {
            val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            m.isAccessible = true
            val ok = m.invoke(proxy, device) as? Boolean ?: false
            if (ok) DiagLog.i(tag, "asked the car to take this phone's sound over bluetooth")
            ok
        }.getOrElse {
            DiagLog.w(tag, "this phone will not let FT switch on the car's bluetooth sound, do it once from the car or the phone's bluetooth screen")
            false
        }
    }

    fun ready(): Boolean = a2dp != null
}
