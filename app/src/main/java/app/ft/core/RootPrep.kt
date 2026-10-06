package app.ft.core

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RootPrep {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val RUNTIME = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Volatile private var done = false

    fun grants(context: Context, force: Boolean = false) {
        if (!Root.ensure()) return
        val pkg = context.packageName
        var missingCount = 0

        if (force || !done) {
            Root.appop(pkg, "PROJECT_MEDIA", "allow")
            Root.appop(pkg, "SYSTEM_ALERT_WINDOW", "allow")
            val missing = RUNTIME.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            missing.forEach { Root.grant(pkg, it) }
            missingCount = missing.size
            Root.enableAccessibility(pkg, "$pkg/app.ft.FTTouchService")
            Root.whitelistBattery(pkg)
            done = true
        }

        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm?.isWifiEnabled == false) Root.wifi(true)
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (runCatching { bt?.isEnabled == false }.getOrDefault(false)) Root.bluetooth(true)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm?.isLocationEnabled == false) Root.location(true)

        _revision.value++
        DiagLog.i("Root", "root set things up: screen sharing, permissions, battery, radios" +
            if (missingCount > 0) " (granted $missingCount permissions)" else "")
    }

    fun ensureHotspot(context: Context, hotspotUp: Boolean) {
        if (!Root.granted || hotspotUp) return
        val ap = Root.readSoftAp()
        if (ap == null) {
            DiagLog.d("Root", "could not read the saved hotspot to start it")
            return
        }
        if (Root.startHotspot(ap)) {
            DiagLog.i("Root", "turned the phone's hotspot '${ap.ssid}' on for the car")
            _revision.value++
        }
    }

    fun poke() { _revision.value++ }

    fun reset() { done = false }
}
