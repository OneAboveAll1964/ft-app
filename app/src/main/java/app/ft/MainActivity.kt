package app.ft

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.core.DiagLog
import app.ft.ui.diag.DiagnosticsScreen
import app.ft.ui.home.HomeScreen
import app.ft.ui.settings.SettingsScreen
import app.ft.ui.theme.FTTheme

enum class Screen { HOME, DIAGNOSTICS, SETTINGS }

class MainActivity : ComponentActivity() {
    private val app get() = application as FTApp
    private var screen by mutableStateOf(Screen.HOME)
    private var pendingAccessory: UsbAccessory? = null

    private val mirrorConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            DiagLog.i("App", "screen mirror permitted")
        } else DiagLog.w("App", "screen mirror declined")
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null) {
                DiagLog.i("USB", "permission granted for ${acc.manufacturer}/${acc.model}")
                CarLifeService.startUsb(this@MainActivity, acc)
            } else DiagLog.w("USB", "permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(usbPermission, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        requestRuntimePermissions()
        handleIntent(intent)
        if (app.prefs.autoStartWifi) CarLifeService.startWifi(this)
        if (app.prefs.autoStartAa) AaHeadUnitService.start(this, app.prefs.aaBluetooth)
        if (app.prefs.debugMuxEnabled) CarLifeService.startMux(this)
        setContent {
            FTTheme {
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onDiagnostics = { screen = Screen.DIAGNOSTICS },
                        onSettings = { screen = Screen.SETTINGS },
                        onAllowMirror = { requestMirror() },
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) },
                        onConnectUsb = { connectUsbNow() }
                    )
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.HOME })
                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("mux", false)) CarLifeService.startMux(this)
        if (intent.getBooleanExtra("wifi", false)) CarLifeService.startWifi(this)
        if (intent.getBooleanExtra("aa", false)) AaHeadUnitService.start(this, false)
        if (intent.getBooleanExtra("mirror", false)) requestMirror()
        intent.getStringExtra("pkgMaps")?.let { app.prefs.mapsPackage = it }
        intent.getStringExtra("pkgVideo")?.let { app.prefs.videoPackage = it }
        intent.getStringExtra("pkgMusic")?.let { app.prefs.musicPackage = it }
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        if (acc != null) connect(acc)
    }

    private fun connectUsbNow() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            DiagLog.w("USB", "no CarLife accessory attached")
            return
        }
        connect(acc)
    }

    private fun connect(acc: UsbAccessory) {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        DiagLog.i("USB", "accessory ${acc.manufacturer}/${acc.model} v${acc.version}")
        if (usb.hasPermission(acc)) {
            CarLifeService.startUsb(this, acc)
        } else {
            pendingAccessory = acc
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(acc, pi)
        }
    }

    private fun requestMirror() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
        mirrorConsent.launch(request)
    }

    private fun requestRuntimePermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermission) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "app.ft.USB_PERMISSION"
    }
}
