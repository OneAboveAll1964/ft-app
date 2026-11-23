package app.ft

import app.ft.ui.play.PermissionsDisclosure
import app.ft.core.LocalModernPhoneUi
import androidx.compose.ui.unit.sp
import app.ft.ui.components.FitText
import app.ft.ui.car.CarStyles
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.ft.aa.AaInstaller
import app.ft.projection.CarControls
import app.ft.core.CarConnection
import app.ft.core.DiagLog
import app.ft.ui.diag.DiagnosticsScreen
import app.ft.ui.home.HomeScreen
import app.ft.ui.home.rememberIccoaConnectionActions
import app.ft.ui.cars.CarsScreen
import app.ft.ui.cars.displayName
import app.ft.ui.car.CarIcons
import app.ft.core.CarProtocol
import app.ft.ui.settings.SettingsPage
import app.ft.ui.settings.SettingsScreen
import app.ft.ui.components.RootChip
import app.ft.ui.components.ReportButton
import app.ft.ui.theme.FTTheme
import androidx.activity.SystemBarStyle
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import app.ft.core.Looks
import android.widget.Toast
import app.ft.core.Backup
import app.ft.core.Lang
import app.ft.core.Sizing
import app.ft.ui.theme.Localized
import app.ft.ui.theme.rememberDark

enum class Screen(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    CARS(R.string.cars_title, CarIcons.Car),
    LOG(R.string.nav_log, Icons.Filled.Info),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings)
}

open class MainActivity : ComponentActivity() {
    private val app get() = application as FTApp

    private val mirrorConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", "screen mirror permitted")
            CarControls.startAudio(this)
        } else {
            DiagLog.w("App", "screen mirror declined")
            CarControls.shareDeclined(this)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(Sizing.phone(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBars(Looks.dark(Looks.mode.value, (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES))
        requestRuntimePermissions()
        handleIntent(intent)
        if (app.prefs.autoConnect) CarConnection.startAuto(this)
        setContent {
            val dark = rememberDark()
            LaunchedEffect(dark) { applyBars(dark) }
            FTTheme(dark) {
                val background = MaterialTheme.colorScheme.background
                LaunchedEffect(background) { window.setBackgroundDrawable(ColorDrawable(background.toArgb())) }
                Localized {
                    if (explainPermissions.value) PermissionsDisclosure(
                        onContinue = {
                            app.prefs.playPermissionsExplained = true
                            explainPermissions.value = false
                            requestRuntimePermissions()
                        },
                        onLater = { explainPermissions.value = false }
                    )
                    FTRoot(
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    )
                }
            }
        }
    }

    private fun applyBars(dark: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.data?.takeIf { it.scheme == Backup.SCHEME && it.host == Backup.LOOK_HOST }?.let { link ->
            val count = Backup.readLookCode(app.prefs, link.toString())
            if (count > 0) {
                CarStyles.reload()
                DiagLog.i("Backup", "car look brought over from a shared code ($count settings)")
                Toast.makeText(this, getString(R.string.backup_look_applied), Toast.LENGTH_LONG).show()
            }
        }
        handleDevelopmentIntent(intent)
    }

    protected open fun handleDevelopmentIntent(intent: Intent) = Unit

    protected fun requestMirror() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
        mirrorConsent.launch(request)
    }

    private val explainPermissions = androidx.compose.runtime.mutableStateOf(false)

    private fun requestRuntimePermissions() {
        val missing = runtimePermissions(this)
        if (missing.isEmpty()) return
        // Google Play: explain what each permission is for before Android asks.
        if (BuildConfig.PLAY && !app.prefs.playPermissionsExplained) {
            explainPermissions.value = true
            return
        }
        permissions.launch(missing)
    }
}

/** The runtime permissions FT asks for at start, minus those already granted. */
fun runtimePermissions(context: Context): Array<String> {
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
    return wanted.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}

@Composable
fun FTRoot(onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit) {
    val modern by app.ft.core.PhoneStyle.modern.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalModernPhoneUi provides modern) {
        FTRootContent(onOpenAccessibility, onOpenOverlay)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FTRootContent(onOpenAccessibility: () -> Unit, onOpenOverlay: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var settingsFromCars by rememberSaveable { mutableStateOf(false) }
    var carPage by rememberSaveable { mutableStateOf<String?>(null) }
    val profiles by FTApp.instance.cars.state.collectAsState()
    val screenStates = rememberSaveableStateHolder()
    val context = androidx.compose.ui.platform.LocalContext.current
    val iccoaActions = rememberIccoaConnectionActions()
    val inPage = screen == Screen.SETTINGS && settingsPage != null
    val inCar = screen == Screen.CARS && carPage != null
    val inSub = inPage || inCar
    fun back() {
        if (inPage && settingsFromCars) { screen = Screen.CARS; settingsPage = null }
        else if (inPage) settingsPage = null
        else if (inCar) carPage = null
        else screen = Screen.HOME
    }
    BackHandler(enabled = screen != Screen.HOME) { back() }
    val barState = rememberTopAppBarState()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(barState)
    LaunchedEffect(screen, settingsPage) { barState.contentOffset = 0f }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    val carHeading = when {
                        !inCar -> null
                        carPage == app.ft.ui.cars.NEW_CAR -> stringResource(R.string.cars_add)
                        else -> profiles.profiles.firstOrNull { it.id == carPage }?.let { it.displayName() }
                    }
                    val heading = carHeading ?: stringResource(if (inPage) settingsPage?.title ?: screen.label else screen.label)
                    AnimatedContent(targetState = screen to heading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { (s, text) ->
                        if (s == Screen.HOME) Image(
                            painter = painterResource(R.drawable.ft_logo),
                            contentDescription = "FT",
                            modifier = Modifier.height(40.dp)
                        )
                        else FitText(text, style = MaterialTheme.typography.titleLarge, smallest = 16.sp)
                    }
                },
                navigationIcon = {
                    if (inSub) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
                actions = {
                    if (screen == Screen.LOG) {
                        ReportButton()
                        IconButton(onClick = { DiagLog.clear() }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_clear_log)) }
                    }
                    if (!LocalModernPhoneUi.current || screen == Screen.SETTINGS) RootChip()
                },
                scrollBehavior = scroll
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = !inSub, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                NavigationBar {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = screen == s,
                            onClick = {
                                if (s == Screen.SETTINGS) { settingsPage = null; settingsFromCars = false }
                                if (s == Screen.CARS) carPage = null
                                screen = s
                            },
                            icon = { Icon(s.icon, contentDescription = stringResource(s.label)) },
                            label = { Text(stringResource(s.label)) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        AnimatedContent(
            targetState = Triple(screen, settingsPage, if (screen == Screen.CARS || settingsFromCars) carPage else null),
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fun depth(state: Triple<Screen, SettingsPage?, String?>) = when {
                    state.first == Screen.SETTINGS && state.second != null -> if (state.third != null) 2 else 1
                    state.first == Screen.CARS && state.third != null -> 1
                    else -> 0
                }
                val from = depth(initialState)
                val to = depth(targetState)
                when {
                    to > from ->
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    to < from ->
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                    else -> fadeIn() togetherWith fadeOut()
                }
            },
            label = "screen"
        ) { (s, page, car) ->
            val stateKey = "${s.name}:${page?.name.orEmpty()}:${if (s == Screen.CARS) car.orEmpty() else ""}:" +
                if (s == Screen.HOME || s == Screen.SETTINGS) profiles.activeId else ""
            screenStates.SaveableStateProvider(stateKey) {
            key(if (s == Screen.HOME || s == Screen.SETTINGS) profiles.activeId else s.name) {
            when (s) {
                Screen.HOME -> HomeScreen(
                    pad = pad,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenOverlay = onOpenOverlay,
                    onOpenConnectionSettings = {
                        settingsFromCars = false
                        settingsPage = SettingsPage.CAR
                        screen = Screen.SETTINGS
                    },
                    onOpenAaSetup = {
                        settingsFromCars = false
                        settingsPage = SettingsPage.ANDROID_AUTO
                        screen = Screen.SETTINGS
                    },
                    onOpenCars = { carPage = null; screen = Screen.CARS },
                    iccoaConnectionActions = iccoaActions
                )
                Screen.CARS -> CarsScreen(pad,
                    page = car,
                    onOpen = { carPage = it },
                    onOpenSettings = { settingsPage = it; settingsFromCars = true; screen = Screen.SETTINGS },
                    onConnect = {
                        if (FTApp.instance.prefs.carProtocol == CarProtocol.ICCOA) iccoaActions.start(false)
                        else CarConnection.switchOn(context)
                        carPage = null
                        screen = Screen.HOME
                    })
                Screen.LOG -> DiagnosticsScreen(pad)
                Screen.SETTINGS -> SettingsScreen(pad, page) { settingsPage = it }
            }
            }
            }
        }
    }
}
