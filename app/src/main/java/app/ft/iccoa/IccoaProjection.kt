package app.ft.iccoa

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Surface
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.MainActivity
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.aa.AaTouchViewport
import app.ft.core.CarAudioBus
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.aa.AaKeep
import app.ft.media.CarPlayer
import app.ft.projection.AudioCapture
import app.ft.projection.CarControls
import app.ft.projection.CarDisplay
import app.ft.projection.CarTouch
import app.ft.projection.CarTouchZones
import app.ft.projection.CarTouchCalibration
import app.ft.projection.MirrorSink
import app.ft.projection.MirrorTouchMap
import app.ft.projection.MirrorTouchSequence
import app.ft.projection.MirrorTouchCalibration
import app.ft.projection.PhoneMirror
import app.ft.projection.VideoPlan
import app.ft.ui.car.CarScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** FT's presentation and phone controls, independent of the ICCOA wire session. */
class IccoaProjection(
    private val context: Context,
    private val promoteProjection: () -> Unit,
    private val onCarHome: () -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val onAaConnectionChanged: (Boolean) -> Unit = {},
    private val prefs: Prefs = FTApp.instance.prefs
) : CarControls.Backend {
    private val app get() = FTApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val display = CarDisplay(context)
    private val mirror = PhoneMirror()
    private val audio = AudioCapture { CarAudioBus.write(CarAudioBus.LANE_PHONE, it) }
    private val mutableState = MutableStateFlow(CarControls.ScreenState())
    override val state: StateFlow<CarControls.ScreenState> = mutableState
    private val keys = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    override val screenKeys = keys.asSharedFlow()
    private val views = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val openView = views.asSharedFlow()
    private var projection: MediaProjection? = null
    private var mirrorJob: Job? = null
    private var aaJob: Job? = null
    private var aaStartJob: Job? = null
    private var pendingLaunch: Pair<Intent, String>? = null
    private var touchFt = false
    private var touchBeside = false
    private val mirrorTouches = MirrorTouchSequence()
    private var active = false
    private var displayPlan: VideoPlan? = null
    private var closed = false
    private var soundDeclined = false
    private var ownsAa = false
    private var savedRotation: Pair<Int, Int>? = null
    private var capturedSize: Pair<Int, Int>? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && mirror.ready && capturedSize == null) {
                val (w, h) = phoneSize()
                if (mirror.resize(w, h)) MirrorSink.surface.value?.let { mirror.show(it) }
            }
        }
    }
    private val surfaceGone: (Surface) -> Unit = { surface ->
        onMain { if (mirror.isShowing(surface)) mirror.hide() }
    }

    init { CarControls.attach(this) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) block()
        } else main.post { if (!closed) block() }
    }

    fun start(plan: VideoPlan, onConfig: (ByteArray) -> Unit, onTimedFrame: (ByteArray, Boolean, Long) -> Unit) = onMain {
        if (active && displayPlan == plan) {
            display.requestKeyframe()
            return@onMain
        }
        try {
            val firstStart = !active
            if (!active) {
                active = true
                soundDeclined = false
                CarAudioBus.sink = onAudio
                CarPlayer.toCar = true
                MirrorSink.onGone = surfaceGone
                displayManager.registerDisplayListener(rotationListener, main)
            }
            // Reconfigure only our encoded display. Keep the single-use projection grant,
            // its audio/mirror capture and the Android Auto session across PLAY/resume.
            display.start(plan, onConfig, { _, _ -> }, onTimedFrame,
                onFailure = { projectionFailed(it) }) { CarScreen() }
            displayPlan = plan
            mutableState.update { it.copy(
                phase = CarControls.Phase.PROJECTING, via = "ICCOA",
                width = plan.streamWidth, height = plan.streamHeight, fps = plan.fps
            ) }
            startCapturedAudio()
            if (firstStart && prefs.aaAutoStart) startAa()
        } catch (t: Throwable) {
            projectionFailed(t)
        }
    }

    private fun projectionFailed(error: Throwable) {
        stopInternal()
        DiagLog.e("ICCOA", "could not create FT's car display", error)
        onFailure("Could not create FT's car display (${error.javaClass.simpleName})")
    }

    fun requestKeyFrame() = onMain { display.requestKeyframe() }

    /** Phone-side button: never require the misaligned HU touch to start calibration. */
    fun startTouchCalibration() = onMain {
        if (!active || state.value.aaOverlay || state.value.mirroring) {
            DiagLog.w("ICCOA", "FT touch calibration needs active FT launcher/browser projection")
            return@onMain
        }
        CarTouchCalibration.begin(display.width, display.height)
        DiagLog.i("ICCOA", "FT touch calibration requested from phone display=${display.width}x${display.height}")
    }
    fun resetTouchCalibration() = onMain {
        if (!active) return@onMain
        CarTouchCalibration.reset(context, display.width, display.height, prefs.profileId)
    }
    fun touch(action: Int, x: Int, y: Int) = touch(CarTouch(action, x, y))
    fun touch(raw: CarTouch) = onMain {
        if (!active) return@onMain
        val (initialX, y) = display.toContent(raw.x, raw.y)
        val second = if (raw.x2 >= 0 && raw.y2 >= 0) display.toContent(raw.x2, raw.y2) else -1 to -1
        // FT owns its UI hit testing. AA and phone mirror have independent touch mapping.
        val isFtUi = !state.value.aaOverlay && !state.value.mirroring
        if (isFtUi && CarTouchCalibration.consume(context, raw.action, initialX, y, prefs.profileId)) return@onMain
        // Apply the stored HU X/Y calibration before routing FT, mirror and AA touch.
        val applyCalibration = true
        val (mappedX, mappedY) = if (applyCalibration)
            CarTouchCalibration.correct(context, initialX, y, display.width, display.height, prefs.profileId) else initialX to y
        val (mappedX2, mappedY2) = if (applyCalibration && second.first >= 0 && second.second >= 0)
            CarTouchCalibration.correct(context, second.first, second.second, display.width, display.height, prefs.profileId) else second
        val t = raw.copy(x = mappedX, y = mappedY, x2 = mappedX2, y2 = mappedY2)
        if (t.action == CarTouch.DOWN) touchFt = CarTouchZones.hit(mappedX, mappedY)
        if (touchFt) {
            if (t.action == CarTouch.DOWN) DiagLog.i("ICCOA", "touch route=FT content=$mappedX,$mappedY")
            display.dispatchTouch(t)
            return@onMain
        }
        val aa = AaHeadUnitService.current
        when {
            state.value.aaOverlay && aa != null && aa.phase.value >= AaSession.Phase.DISCOVERED -> {
                if (t.action !in 0..2) return@onMain
                val viewport = AaTouchViewport.bounds ?: return@onMain
                if (t.action == CarTouch.DOWN) touchBeside = !viewport.contains(mappedX, mappedY)
                if (touchBeside) {
                    if (t.action == CarTouch.DOWN) DiagLog.i("ICCOA", "touch beside AA content=$mappedX,$mappedY viewport=$viewport")
                    return@onMain
                }
                val display = aa.displayConfig
                val targetWidth = display?.width ?: prefs.aaWidth
                val targetHeight = display?.height ?: prefs.aaHeight
                val point = viewport.map(mappedX, mappedY, targetWidth, targetHeight)
                if (t.action == CarTouch.DOWN) DiagLog.i("ICCOA",
                    "touch route=AA content=$mappedX,$mappedY viewport=$viewport target=${targetWidth}x${targetHeight} mapped=${point.first},${point.second}")
                aa.sendTouch(point.first, point.second, t.action)
            }
            state.value.mirroring -> {
                if (!mirror.active || t.action !in 0..2) return@onMain
                val frame = MirrorSink.frame.value
                val viewport = MirrorTouchMap(
                    display.width, display.height, frame.width, frame.height,
                    mirror.width, mirror.height
                )
                val mapped = viewport.map(mappedX, mappedY, clipToBounds = t.action != CarTouch.DOWN)
                if (t.action == CarTouch.DOWN) {
                    mirrorTouches.reset()
                    touchBeside = mapped == null
                    DiagLog.i("Mirror", "touch DOWN car=$mappedX,$mappedY phone=${mapped?.first},${mapped?.second} " +
                        "source=${mirror.width}x${mirror.height} surface=${frame.width}x${frame.height}")
                }
                if (!touchBeside && mapped != null) {
                    val injector = FTTouchService.instance
                    mirrorTouches.accept(t.action, mapped.first, mapped.second, android.os.SystemClock.uptimeMillis()).forEach { cmd ->
                        if (cmd.action == MirrorTouchSequence.TAP) injector?.tap(cmd.x, cmd.y, cmd.durationMs)
                        else injector?.inject(cmd.action, cmd.x, cmd.y)
                    }
                }
            }
            else -> display.dispatchTouch(t)
        }
    }

    fun key(code: Int) = onMain {
        if (!active) return@onMain
        if (CarTouchCalibration.state.value.step != 0 && (code == 3 || code == 4)) {
            CarTouchCalibration.cancel()
            return@onMain
        }
        if (state.value.aaOverlay) {
            AaHeadUnitService.current?.sendKey(code)
        } else when (code) {
            3 -> { goHome(); keys.tryEmit(code) }
            4 -> if (state.value.mirroring) navBack() else keys.tryEmit(code)
            85 -> CarPlayer.toggle()
            87 -> CarPlayer.next()
            88 -> CarPlayer.previous()
            126 -> CarPlayer.resume()
            127 -> CarPlayer.pause()
            else -> keys.tryEmit(code)
        }
    }

    override fun soundSoon() = onMain {
        if (!active || soundDeclined || audio.active || state.value.waitingForShare || state.value.aaOverlay) return@onMain
        if (projection != null || app.mirrorData != null) startCapturedAudio()
        else if (canRecord()) askShare(forSound = true)
    }

    override fun startAudio(context: Context) = onMain {
        if (!active) return@onMain
        val launch = pendingLaunch
        pendingLaunch = null
        mutableState.update { it.copy(waitingForShare = false, shareForSound = false) }
        if (launch != null) launchMirror(launch.first, launch.second) else startCapturedAudio()
    }

    override fun shareDeclined(context: Context, quiet: Boolean) = cancelShareWait()
    override fun cancelShareWait() = onMain {
        if (state.value.shareForSound) soundDeclined = true
        pendingLaunch = null
        mutableState.update { it.copy(waitingForShare = false, shareForSound = false, mirrorPackage = "") }
    }

    private fun canRecord() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun startCapturedAudio() {
        if (audio.active || !canRecord()) return
        ensureProjection()?.let { audio.start(it) }
    }

    private fun ensureProjection(): MediaProjection? {
        projection?.let { return it }
        val data = app.mirrorData ?: return null
        val code = app.mirrorResultCode
        if (code == 0) return null
        // Android 14 grants are single-use. Promote the owning service before consuming one.
        return try {
            promoteProjection()
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(code, data) ?: error("MediaProjection is unavailable")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    onMain {
                        if (projection !== mp || width < 1 || height < 1) return@onMain
                        capturedSize = width to height
                        DiagLog.i("Mirror", "actual Android capture content size=${width}x${height}")
                        if (mirror.resize(width, height)) MirrorSink.surface.value?.let { mirror.show(it) }
                    }
                }
                override fun onStop() {
                    if (projection !== mp) return
                    releaseProjection(stop = false)
                    hideMirror()
                }
            }, main)
            app.mirrorGranted.value = true
            mp
        } catch (t: Throwable) {
            releaseProjection()
            app.mirrorGranted.value = false
            DiagLog.e("ICCOA", "could not start phone screen sharing", t)
            null
        } finally {
            if (app.mirrorData === data) {
                app.mirrorData = null
                app.mirrorResultCode = 0
            }
        }
    }

    private fun askShare(forSound: Boolean) {
        mutableState.update { it.copy(waitingForShare = true, shareForSound = forSound) }
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("mirror", true))
        }.onFailure { DiagLog.w("ICCOA", "open FT on the phone to allow screen sharing: ${it.message}") }
    }

    override fun showPhone() = onMain {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: "android"
        launchMirror(intent, pkg)
    }

    override fun launchApp(pkg: String) = onMain {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { launchMirror(it, pkg) }
    }

    private fun launchMirror(intent: Intent, pkg: String) {
        if (!active) return
        stopAa()
        val mp = ensureProjection()
        if (mp == null) {
            pendingLaunch = intent to pkg
            mutableState.update { it.copy(mirrorPackage = pkg) }
            askShare(forSound = false)
            return
        }
        // Reuse one display per consent token. Recreating it requires new consent on Android 14+.
        if (!mirror.ready) {
            val (w, h) = capturedSize ?: phoneSize()
            if (!mirror.open(mp, w, h, context.resources.displayMetrics.densityDpi, false, display.width, display.height)) {
                releaseProjection()
                return
            }
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
            .onFailure { DiagLog.e("ICCOA", "could not launch $pkg", it); return }
        mutableState.update { it.copy(mirroring = true, mirrorPackage = pkg, waitingForShare = false, shareForSound = false) }
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            MirrorSink.surface.collect { surface ->
                runCatching { if (surface == null) mirror.hide() else mirror.show(surface) }
                    .onFailure { DiagLog.e("ICCOA", "could not attach the phone picture", it) }
            }
        }
        startCapturedAudio()
    }

    @Suppress("DEPRECATION")
    private fun phoneSize(): Pair<Int, Int> {
        val point = android.graphics.Point()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point)
        return point.x.coerceAtLeast(1) to point.y.coerceAtLeast(1)
    }

    override fun stopMirror() = onMain { hideMirror() }
    private fun hideMirror() {
        mirrorTouches.reset()
        pendingLaunch = null
        mirrorJob?.cancel()
        mirrorJob = null
        mirror.hide()
        restoreRotation()
        mutableState.update { it.copy(mirroring = false, mirrorPackage = "", waitingForShare = false, shareForSound = false) }
    }

    override fun startAa() = onMain {
        if (!active) return@onMain
        if (runCatching { context.packageManager.getPackageInfo(prefs.aaPackage, 0) }.isFailure) return@onMain
        hideMirror()
        if (prefs.aaMatchCar) {
            val (width, height) = IccoaAaSize.fit(display.width, display.height)
            prefs.aaWidth = width
            prefs.aaHeight = height
            prefs.aaFps = state.value.fps
            DiagLog.i("ICCOA", "AA active size=${width}x$height for car=${display.width}x${display.height}")
        }
        ownsAa = true
        if (AaHeadUnitService.current == null && !AaHeadUnitService.state.value.listening) {
            AaHeadUnitService.start(context, prefs.aaBluetooth)
        }
        aaJob?.cancel()
        aaJob = scope.launch {
            AaHeadUnitService.state.collect { aa ->
                val connected = aa.connected
                val changed = mutableState.value.aaOverlay != connected
                mutableState.update { it.copy(aaOverlay = connected) }
                if (changed) onAaConnectionChanged(connected)
            }
        }
        aaStartJob?.cancel()
        aaStartJob = scope.launch {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) { AaKeep.switchOn(context) }
            delay(1500)
            requestAaConnection()
        }
    }

    override fun startAaWireless() = startAa()
    private fun requestAaConnection() {
        runCatching {
            context.sendBroadcast(Intent("com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START")
                .setComponent(ComponentName("com.google.android.projection.gearhead",
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("ip_address", "127.0.0.1").putExtra("projection_port", prefs.aaPort))
        }.onFailure { DiagLog.w("ICCOA", "Android Auto connection request failed: ${it.message}") }
    }

    private fun stopAa() {
        aaJob?.cancel()
        aaJob = null
        aaStartJob?.cancel()
        aaStartJob = null
        if (ownsAa) {
            AaHeadUnitService.stop(context)
            CarAudioBus.clear(CarAudioBus.LANE_MEDIA)
            CarAudioBus.clear(CarAudioBus.LANE_SPEECH)
            CarAudioBus.clear(CarAudioBus.LANE_SYSTEM)
        }
        ownsAa = false
        mutableState.update { it.copy(aaOverlay = false) }
        onAaConnectionChanged(false)
    }

    override fun navBack() = onMain { FTTouchService.instance?.back() }
    override fun navHome() = onMain { FTTouchService.instance?.home() }
    override fun navRecents() = onMain { FTTouchService.instance?.recents() }
    override fun navNotifications() = onMain { FTTouchService.instance?.notifications() }
    override fun rotatePhone() = onMain {
        if (!Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return@onMain
        }
        val cr = context.contentResolver
        runCatching {
            if (savedRotation == null) savedRotation = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) to
                Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0)
            val (w, h) = phoneSize()
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, if (h >= w) Surface.ROTATION_90 else Surface.ROTATION_0)
        }.onFailure { DiagLog.w("ICCOA", "could not rotate the phone: ${it.message}") }
    }

    private fun restoreRotation() {
        val (auto, rotation) = savedRotation ?: return
        savedRotation = null
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, rotation)
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, auto)
        }
    }

    override fun goToCarHome() = onMain { if (active) onCarHome() }
    override fun goHome() = onMain { hideMirror(); stopAa() }

    private fun releaseProjection(stop: Boolean = true) {
        mirrorJob?.cancel()
        mirrorJob = null
        audio.stop()
        mirror.close()
        capturedSize = null
        val mp = projection
        projection = null
        if (mp != null) {
            app.mirrorGranted.value = false
            if (stop) runCatching { mp.stop() }
        }
    }

    fun stop() = onMain { stopInternal() }
    private fun stopInternal() {
        CarTouchCalibration.cancel()
        MirrorTouchCalibration.cancel()
        active = false
        displayPlan = null
        hideMirror()
        stopAa()
        releaseProjection()
        runCatching { displayManager.unregisterDisplayListener(rotationListener) }
        if (MirrorSink.onGone === surfaceGone) MirrorSink.onGone = null
        if (CarAudioBus.sink === onAudio) {
            CarPlayer.pause()
            CarPlayer.toCar = false
            CarAudioBus.sink = null
        }
        display.stop()
        mutableState.value = CarControls.ScreenState()
    }

    fun close() = onMain {
        stopInternal()
        closed = true
        CarControls.detach(this)
        scope.cancel()
    }
}
