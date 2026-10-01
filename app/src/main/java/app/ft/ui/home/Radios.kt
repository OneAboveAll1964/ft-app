package app.ft.ui.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ft.carlife.NetUtil

data class Radios(
    val bluetooth: Boolean,
    val nearby: Boolean,
    val wifi: Boolean,
    val wifiAllowed: Boolean,
    val location: Boolean,
    val hotspot: Boolean
)

private const val AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"

fun readRadios(context: Context): Radios {
    val c = context.applicationContext
    fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val wm = c.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return Radios(
        bluetooth = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
        nearby = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
        wifi = wm?.isWifiEnabled == true,
        wifiAllowed = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.NEARBY_WIFI_DEVICES) else granted(Manifest.permission.ACCESS_FINE_LOCATION),
        location = Build.VERSION.SDK_INT >= 33 || lm?.isLocationEnabled != false,
        hotspot = hotspotOn(c)
    )
}

private fun hotspotOn(c: Context): Boolean {
    val sticky = runCatching { c.registerReceiver(null, IntentFilter(AP_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED) }.getOrNull()
    when (sticky?.getIntExtra("wifi_state", -1)) {
        13 -> return true
        10, 11, 14 -> return false
    }
    return NetUtil.hotspotIpv4() != null
}

@Composable
fun rememberRadios(context: Context, refresh: Int): Radios {
    var radios by remember { mutableStateOf(readRadios(context)) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                radios = readRadios(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(AP_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(refresh) { radios = readRadios(context) }
    return radios
}

object Fixes {
    fun appSettings(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))

    fun bluetooth(c: Context): Intent =
        if (Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun wifi(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    fun location(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun hotspot(c: Context): Intent {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        return if (c.packageManager.resolveActivity(tether, 0) != null) tether else Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }

    fun open(c: Context, intent: Intent) {
        runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { c.startActivity(appSettings(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
