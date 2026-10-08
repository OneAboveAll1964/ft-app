package app.ft

import app.ft.ui.car.CarStyles
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaInstaller
import app.ft.carlife.CarLifeService
import app.ft.projection.VideoPlans
import app.ft.core.CarAudioBus
import app.ft.core.DiagLog
import app.ft.ui.diag.DiagnosticsScreen
import app.ft.ui.home.HomeScreen
import app.ft.ui.settings.SettingsPage
import app.ft.ui.settings.SettingsScreen
import app.ft.ui.components.RootChip
import app.ft.ui.components.ReportButton
import app.ft.ui.theme.FTTheme

enum class Screen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LOG("Log", Icons.Filled.Info),
    SETTINGS("Settings", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    private val app get() = application as FTApp

    private val mirrorConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", "screen mirror permitted")
            CarLifeService.startAudio(this)
        } else {
            DiagLog.w("App", "screen mirror declined")
            CarLifeService.shareDeclined(this)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val pickAaApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) DiagLog.w("AaSetup", "no file chosen") else AaInstaller.install(this, uri)
    }

    private val installResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val job = i?.getStringExtra(AaInstaller.EXTRA_JOB) ?: AaInstaller.JOB_INSTALL
            when (i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val next = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT) as Intent?
                    next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(next) }
                }
                PackageInstaller.STATUS_SUCCESS -> if (job == AaInstaller.JOB_UNINSTALL) {
                    DiagLog.i("AaSetup", "Android Auto removed, now pick the apk to put it back")
                    takeOverAndroidAuto()
                } else {
                    DiagLog.i("AaSetup", "Android Auto installed by FT, so FT is now its installer")
                    AaInstaller.enableWirelessComponents(this@MainActivity)
                    AaInstaller.stopAutoUpdates(this@MainActivity)
                }
                else -> DiagLog.w("AaSetup", "$job did not finish: ${i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(installResult, IntentFilter(AaInstaller.ACTION_RESULT), RECEIVER_NOT_EXPORTED)
        requestRuntimePermissions()
        handleIntent(intent)
        if (app.prefs.autoConnect) CarLifeService.startAuto(this)
        setContent {
            FTTheme {
                FTRoot(
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                    onTakeOverAa = { takeOverAndroidAuto() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("aaCorner")) {
            app.prefs.aaCorner = intent.getIntExtra("aaCorner", 0)
            CarStyles.reload()
        }
        if (intent.hasExtra("linkMode")) app.prefs.linkMode = intent.getIntExtra("linkMode", 0)
        if (intent.hasExtra("pictureSize")) app.prefs.videoSize = intent.getIntExtra("pictureSize", VideoPlans.SIZE_CAR)
        if (intent.getBooleanExtra("off", false)) CarLifeService.switchOff(this)
        if (intent.getBooleanExtra("wifi", false) || intent.getBooleanExtra("auto", false)) CarLifeService.switchOn(this)
        if (intent.getBooleanExtra("aa", false)) AaHeadUnitService.start(this, false)
        if (intent.getBooleanExtra("mirror", false)) requestMirror()
        intent.getStringExtra("aaPkg")?.let { app.prefs.aaPackage = it }
        if (intent.hasExtra("aaAuto")) app.prefs.aaAutoStart = intent.getBooleanExtra("aaAuto", false)
        if (intent.hasExtra("fakeCall")) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = if (intent.getBooleanExtra("fakeCall", false)) android.media.AudioManager.MODE_IN_COMMUNICATION else android.media.AudioManager.MODE_NORMAL
        }
        if (intent.getBooleanExtra("aaSetup", false)) takeOverAndroidAuto()
        if (intent.getBooleanExtra("aaStash", false)) AaInstaller.stash(this)
        if (intent.getBooleanExtra("startAa", false)) CarLifeService.startAa()
        if (intent.getBooleanExtra("aaWireless", false)) CarLifeService.startAaWireless()
        intent.getStringExtra("btSend")?.let { CarLifeService.btSend(it) }
        if (intent.hasExtra("btEcho")) CarLifeService.btEcho(intent.getBooleanExtra("btEcho", false))
        intent.getStringExtra("pkgMaps")?.let { app.prefs.mapsPackage = it }
        intent.getStringExtra("pkgVideo")?.let { app.prefs.videoPackage = it }
        intent.getStringExtra("carTiles")?.let { app.prefs.carTiles = it; CarStyles.reload() }
        intent.getStringExtra("carBackground")?.let { app.prefs.carBackground = it; CarStyles.reload() }
        if (intent.hasExtra("carAccent")) {
            app.prefs.carAccent = intent.getIntExtra("carAccent", app.prefs.carAccent)
            CarStyles.reload()
        }
        if (intent.hasExtra("carSongInfo")) app.prefs.carSongInfo = intent.getBooleanExtra("carSongInfo", true)
        if (intent.hasExtra("guidance")) {
            app.prefs.guidanceMode = intent.getIntExtra("guidance", CarAudioBus.GUIDANCE_UNTOUCHED)
            CarAudioBus.guidance = app.prefs.guidanceMode
        }
        if (intent.hasExtra("carClock24")) {
            app.prefs.carClock24 = intent.getBooleanExtra("carClock24", true)
            CarStyles.reload()
        }
        intent.getStringExtra("pkgMusic")?.let { app.prefs.musicPackage = it }
    }

    private fun takeOverAndroidAuto() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            DiagLog.i("AaSetup", "let FT install apps on the screen that just opened, then tap Set up again")
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return
        }
        when (AaInstaller.step(this)) {
            AaInstaller.Step.DONE -> {
                AaInstaller.enableWirelessComponents(this)
                AaInstaller.stopAutoUpdates(this)
            }
            AaInstaller.Step.REMOVE_UPDATES -> {
                AaInstaller.stash(this)
                AaInstaller.removeUpdates(this)
            }
            AaInstaller.Step.UNINSTALL -> {
                AaInstaller.stash(this)
                AaInstaller.uninstall(this)
            }
            AaInstaller.Step.INSTALL -> if (AaInstaller.stashed(this).isNotEmpty()) AaInstaller.installStash(this) else {
                DiagLog.i("AaSetup", "pick a saved copy of Android Auto, an apk or an apks, and FT will install it")
                runCatching { pickAaApk.launch(arrayOf("*/*")) }
                    .onFailure { DiagLog.e("AaSetup", "no file picker available", it) }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(installResult) }
        super.onDestroy()
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
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        else wanted += Manifest.permission.ACCESS_FINE_LOCATION
        wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.READ_MEDIA_VIDEO
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FTRoot(onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit, onTakeOverAa: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val inPage = screen == Screen.SETTINGS && settingsPage != null
    BackHandler(enabled = screen != Screen.HOME) { if (inPage) settingsPage = null else screen = Screen.HOME }
    val barState = rememberTopAppBarState()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(barState)
    LaunchedEffect(screen, settingsPage) { barState.contentOffset = 0f }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    val heading = if (inPage) settingsPage?.title.orEmpty() else screen.label
                    AnimatedContent(targetState = screen to heading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { (s, text) ->
                        if (s == Screen.HOME) Image(
                            painter = painterResource(R.drawable.ft_logo),
                            contentDescription = "FT",
                            modifier = Modifier.height(40.dp)
                        )
                        else Text(text, style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (inPage) IconButton(onClick = { settingsPage = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (screen == Screen.LOG) {
                        ReportButton()
                        IconButton(onClick = { DiagLog.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "Clear") }
                    }
                    RootChip()
                },
                scrollBehavior = scroll
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = !inPage, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                NavigationBar {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = screen == s,
                            onClick = {
                                if (s == Screen.SETTINGS) settingsPage = null
                                screen = s
                            },
                            icon = { Icon(s.icon, contentDescription = s.label) },
                            label = { Text(s.label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        AnimatedContent(
            targetState = screen to settingsPage,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val (from, fromPage) = initialState
                val (to, toPage) = targetState
                val inSettings = from == Screen.SETTINGS && to == Screen.SETTINGS
                when {
                    inSettings && fromPage == null && toPage != null ->
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    inSettings && fromPage != null && toPage == null ->
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                    else -> fadeIn() togetherWith fadeOut()
                }
            },
            label = "screen"
        ) { (s, page) ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    pad = pad,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenOverlay = onOpenOverlay,
                    onOpenAaSetup = {
                        settingsPage = SettingsPage.ANDROID_AUTO
                        screen = Screen.SETTINGS
                    }
                )
                Screen.LOG -> DiagnosticsScreen(pad)
                Screen.SETTINGS -> SettingsScreen(pad, page, onTakeOverAa) { settingsPage = it }
            }
        }
    }
}
