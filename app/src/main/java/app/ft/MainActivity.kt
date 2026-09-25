package app.ft

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.core.DiagLog
import app.ft.ui.diag.DiagnosticsScreen
import app.ft.ui.home.HomeScreen
import app.ft.ui.settings.SettingsScreen
import app.ft.ui.theme.FTTheme

enum class Screen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LOG("Log", Icons.Filled.Info),
    SETTINGS("Settings", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    private val app get() = application as FTApp
    private val usbPermissionAction = "app.ft.USB_PERMISSION"

    private val mirrorConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", "screen mirror permitted")
        } else DiagLog.w("App", "screen mirror declined")
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            val acc = accessoryOf(intent)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null) {
                DiagLog.i("USB", "permission granted for ${acc.manufacturer}/${acc.model}")
                CarLifeService.startUsb(this@MainActivity, acc)
            } else DiagLog.w("USB", "permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(usbPermission, IntentFilter(usbPermissionAction), Context.RECEIVER_NOT_EXPORTED)
        requestRuntimePermissions()
        handleIntent(intent)
        if (app.prefs.autoConnect) CarLifeService.startAuto(this)
        if (app.prefs.autoStartWifi) CarLifeService.startWifi(this)
        if (app.prefs.autoStartAa) AaHeadUnitService.start(this, app.prefs.aaBluetooth)
        setContent {
            FTTheme {
                FTRoot(
                    onAllowMirror = { requestMirror() },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                    onConnectUsb = { connectUsbNow() }
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermission) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) accessoryOf(intent)?.let { connectUsb(it) }
        if (intent.getBooleanExtra("wifi", false)) CarLifeService.startWifi(this)
        if (intent.getBooleanExtra("auto", false)) CarLifeService.startAuto(this)
        if (intent.getBooleanExtra("mux", false)) CarLifeService.startMux(this)
        if (intent.getBooleanExtra("aa", false)) AaHeadUnitService.start(this, false)
        if (intent.getBooleanExtra("mirror", false)) requestMirror()
        intent.getStringExtra("pkgMaps")?.let { app.prefs.mapsPackage = it }
        intent.getStringExtra("pkgVideo")?.let { app.prefs.videoPackage = it }
        intent.getStringExtra("pkgMusic")?.let { app.prefs.musicPackage = it }
    }

    private fun accessoryOf(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private fun connectUsbNow() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            DiagLog.w("USB", "no CarLife accessory attached; plug the phone into the car")
            return
        }
        connectUsb(acc)
    }

    private fun connectUsb(acc: UsbAccessory) {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        DiagLog.i("USB", "accessory ${acc.manufacturer}/${acc.model} v${acc.version}")
        if (usb.hasPermission(acc)) {
            CarLifeService.startUsb(this, acc)
        } else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(usbPermissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(acc, pi)
        }
    }

    private fun requestMirror() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
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
        else wanted += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FTRoot(onAllowMirror: () -> Unit, onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit, onConnectUsb: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    val barState = rememberTopAppBarState()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(barState)
    LaunchedEffect(screen) { barState.contentOffset = 0f }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { s ->
                        if (s == Screen.HOME) Text("FT", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        else Text(s.label, style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (screen != Screen.HOME) IconButton(onClick = { screen = Screen.HOME }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (screen == Screen.LOG) IconButton(onClick = { DiagLog.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "Clear") }
                },
                scrollBehavior = scroll
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = s.label) },
                        label = { Text(s.label) }
                    )
                }
            }
        }
    ) { pad ->
        AnimatedContent(targetState = screen, modifier = Modifier.fillMaxSize(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    pad = pad,
                    onOpenLog = { screen = Screen.LOG },
                    onAllowMirror = onAllowMirror,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenOverlay = onOpenOverlay,
                    onConnectUsb = onConnectUsb
                )
                Screen.LOG -> DiagnosticsScreen(pad)
                Screen.SETTINGS -> SettingsScreen(pad)
            }
        }
    }
}
