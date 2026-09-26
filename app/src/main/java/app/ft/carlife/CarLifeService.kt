package app.ft.carlife
import android.annotation.SuppressLint

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.MainActivity
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.core.DiagLog
import app.ft.projection.CarDisplay
import app.ft.projection.MirrorSink
import app.ft.projection.PhoneMirror
import app.ft.ui.car.CarScreen
import app.ft.ui.theme.CarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CarState(
    val running: Boolean = false,
    val link: String = "",
    val session: CarLifeSession.State = CarLifeSession.State.Idle,
    val listening: Boolean = false,
    val aaOverlay: Boolean = false,
    val mirroring: Boolean = false,
    val mirrorPackage: String = "",
    val ip: String? = null,
    val p2p: String = "off",
    val peers: List<String> = emptyList(),
    val carIp: String? = null,
    val beacon: Boolean = false
)

class CarLifeService : Service() {
    companion object {
        const val ACTION_WIFI = "app.ft.carlife.WIFI"
        const val ACTION_AUTO = "app.ft.carlife.AUTO"
        const val ACTION_STOP = "app.ft.carlife.STOP"
        const val ACTION_AUDIO = "app.ft.carlife.AUDIO"
        const val EXTRA_BT_ADDR = "btAddr"
        const val CORNER = 96
        private const val CHANNEL = "ft_carlife"
        private const val NOTIFICATION_ID = 41

        private val _state = MutableStateFlow(CarState())
        val state: StateFlow<CarState> = _state
        @Volatile private var instance: CarLifeService? = null

        fun startWifi(context: Context) {
            context.startForegroundService(Intent(context, CarLifeService::class.java).setAction(ACTION_WIFI))
        }

        fun startAuto(context: Context, btAddress: String? = null) {
            context.startForegroundService(Intent(context, CarLifeService::class.java).setAction(ACTION_AUTO).putExtra(EXTRA_BT_ADDR, btAddress))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CarLifeService::class.java).setAction(ACTION_STOP))
        }

        fun startAudio(context: Context) {
            context.startForegroundService(Intent(context, CarLifeService::class.java).setAction(ACTION_AUDIO))
        }

        fun btSend(hex: String) = instance?.sendBluetooth(hex)
        fun btEcho(on: Boolean) = instance?.echoBluetooth(on)
        fun pickCar(name: String) = instance?.finder?.connectByName(name)
        fun forgetCar() = instance?.forget()
        fun searchAgain() = instance?.restartFinder()
        fun startAa() = instance?.startAndroidAuto()
        fun startAaWireless() = instance?.triggerAaWireless()
        fun volumeUp() = instance?.volume(true)
        fun volumeDown() = instance?.volume(false)
        fun navBack() = FTTouchService.instance?.back()
        fun navHome() = FTTouchService.instance?.home()
        fun launchApp(pkg: String) = instance?.launch(pkg)
        fun stopMirror() = instance?.mirrorStop()
        fun goHome() = instance?.home()
    }

    private val tag = "CarLife"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var carDisplay: CarDisplay
    private val mirror = PhoneMirror()
    private var session: CarLifeSession? = null
    private var wifiLink: WifiChannelLink? = null
    private var finder: CarFinder? = null
    private var stateJob: Job? = null
    private var mirrorJob: Job? = null
    private var finderFallback: Job? = null
    private var aaWatch: Job? = null
    private var listenOnly = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var projection: MediaProjection? = null
    private val audio = AudioCapture { pcm -> session?.sendAudio(pcm) }
    @Volatile private var aaAutoLaunched = false
    @Volatile private var askedForShare = false
    private val app get() = application as FTApp
    private val beacon by lazy { CarBeacon(scope) { app.prefs.carName } }
    private val bt by lazy { CarBluetooth(this, app.prefs, scope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        carDisplay = CarDisplay(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "FT projection", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WIFI -> {
                foreground("Waiting for the head unit")
                listenOnly = true
                startWifiLink()
                updateBeacon()
            }
            ACTION_AUDIO -> {
                askedForShare = true
                audio.stop()
                foreground("Sending sound to the car", projection = true, microphone = canRecord())
                startAudioToCar()
            }
            ACTION_AUTO -> {
                foreground("Looking for the car")
                startWifiLink()
                bt.start(intent.getStringExtra(EXTRA_BT_ADDR))
                scheduleFinderFallback()
            }
        }
        return START_STICKY
    }

    private fun foreground(text: String, projection: Boolean = false, microphone: Boolean = false) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (projection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val note = notification(text)
        val fallback = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
        runCatching { startForeground(NOTIFICATION_ID, note, type) }
            .onFailure { t ->
                DiagLog.w(tag, "foreground type $type refused (${t.message}), continuing without the microphone type")
                runCatching { startForeground(NOTIFICATION_ID, note, fallback) }
            }
        awake()
        _state.update { it.copy(running = true, ip = NetUtil.localIpv4()) }
    }

    private fun awake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FT:carlife").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun canRecord(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWifiLink() {
        if (wifiLink != null) return
        val p = app.prefs
        val l = WifiChannelLink(
            mapOf(
                CarLifeProtocol.CH_CMD to p.cmdPort,
                CarLifeProtocol.CH_VIDEO to p.videoPort,
                CarLifeProtocol.CH_MEDIA to p.mediaPort,
                CarLifeProtocol.CH_TTS to p.ttsPort,
                CarLifeProtocol.CH_VR to p.vrPort,
                CarLifeProtocol.CH_CTRL to p.touchPort
            )
        )
        wifiLink = l
        _state.update { it.copy(listening = true) }
        attachSession(l)
    }

    private fun startFinder() {
        if (finder != null) return
        val f = CarFinder(this, app.prefs, scope)
        finder = f
        f.onJoined = { l ->
            _state.update { it.copy(carIp = l.groupOwnerIp, ip = NetUtil.localIpv4()) }
            val p = app.prefs
            f.registerService(mapOf("cmd" to p.cmdPort, "video" to p.videoPort, "media" to p.mediaPort, "touch" to p.touchPort))
            beacon.target = l.groupOwnerIp
            updateBeacon()
            updateNotification("On the car network, calling the head unit")
        }
        f.onLeft = {
            _state.update { it.copy(carIp = null) }
            beacon.target = null
            updateBeacon()
            updateNotification("Looking for the car")
        }
        scope.launch { f.state.collect { s -> _state.update { it.copy(p2p = s) } } }
        scope.launch { f.peers.collect { ps -> _state.update { it.copy(peers = ps.map { p -> p.name }) } } }
        f.start()
    }

    private fun scheduleFinderFallback() {
        finderFallback?.cancel()
        _state.update { it.copy(p2p = "not needed while the car is on this network") }
        finderFallback = scope.launch {
            delay(25_000)
            if (wifiLink?.connected != true && finder == null) {
                DiagLog.i(tag, "no head unit reached us on this network, falling back to WiFi Direct")
                startFinder()
            }
        }
    }

    private fun stopFinder(reason: String) {
        finderFallback?.cancel()
        finderFallback = null
        if (finder != null) {
            DiagLog.i(tag, "WiFi Direct search stopped: $reason")
            finder?.stop()
            finder = null
        }
        _state.update { it.copy(p2p = reason, peers = emptyList()) }
    }

    private fun restartFinder() {
        finder?.stop()
        finder = null
        _state.update { it.copy(peers = emptyList(), carIp = null) }
        startFinder()
    }

    private fun forget() {
        app.prefs.carP2pName = ""
        finder?.disconnect()
        restartFinder()
    }

    private fun updateBeacon() {
        val busy = wifiLink?.connected == true
        val want = wifiLink != null && !busy
        if (want) beacon.start() else beacon.stop()
        _state.update { it.copy(beacon = want) }
    }

    private fun attachSession(l: CarLifeLink) {
        session?.stop()
        val s = CarLifeSession(this, l, app.prefs, scope)
        session = s
        s.onVideoConfig = { w, h, fps -> onVideoConfig(w, h, fps) }
        s.onStartVideo = { carDisplay.requestKeyFrame() }
        s.onFrameRate = { fps ->
            carDisplay.setFrameRate(fps)
            carDisplay.setBitrate((carDisplay.width * carDisplay.height * fps / 12).coerceAtLeast(400_000))
        }
        s.onStopVideo = { onStopVideo() }
        s.onKeyFrameRequest = { carDisplay.requestKeyFrame() }
        s.onTouch = { a, x, y -> routeTouch(a, x, y) }
        s.onHardKey = { k -> onHardKey(k) }
        s.onClosed = { reason ->
            DiagLog.i(tag, "link closed: $reason")
            updateBeacon()
        }
        stateJob?.cancel()
        stateJob = scope.launch {
            s.state.collect { st ->
                _state.update { it.copy(link = l.name, session = st) }
                updateBeacon()
                if (st !is CarLifeSession.State.Idle) stopFinder("car is on this network, WiFi Direct not needed")
                else if (finder == null && finderFallback?.isActive != true) scheduleFinderFallback()
                if (st is CarLifeSession.State.Projecting && app.prefs.aaAutoStart && !aaAutoLaunched) {
                    aaAutoLaunched = true
                    DiagLog.i(tag, "auto-starting Android Auto after connection")
                    startAndroidAuto()
                }
            }
        }
        s.start()
    }

    private fun onVideoConfig(w: Int, h: Int, fps: Int) {
        val s = session ?: return
        if (carDisplay.active.value && carDisplay.width == w && carDisplay.height == h) {
            DiagLog.i(tag, "head unit re-sent the same video config, keeping the current screen")
            carDisplay.setFrameRate(fps)
            carDisplay.requestKeyFrame()
            return
        }
        carDisplay.start(
            w, h, fps, app.prefs.maxBitrate,
            onConfig = { cfg -> s.sendVideo(cfg) },
            onFrame = { frame, _ -> s.sendVideo(frame) }
        ) {
            CarTheme { CarScreen() }
        }
        updateNotification("Projecting ${w}×$h to the car")
        startAudioToCar()
    }

    private fun onStopVideo() {
        mirrorClose()
        carDisplay.stop()
        aaAutoLaunched = false
        _state.update { it.copy(aaOverlay = false) }
        updateNotification(if (finder != null) "Looking for the car" else "Waiting for the head unit")
    }

    private fun sendBluetooth(hex: String) {
        val clean = hex.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.length < 2 || clean.length % 2 != 0) {
            DiagLog.w(tag, "bluetooth send: bad hex '$hex'")
            return
        }
        val bytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        bt.send(bytes)
    }

    private fun echoBluetooth(on: Boolean) {
        bt.onFrame = if (on) { frame -> bt.send(frame) } else null
        DiagLog.i(tag, "bluetooth echo ${if (on) "on" else "off"}")
    }

    @SuppressLint("MissingPermission")
    private fun triggerAaWireless() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val want = app.prefs.carBtName.trim()
        val device = bonded.firstOrNull { d ->
            want.isNotEmpty() && runCatching { d.name }.getOrNull()?.contains(want, true) == true
        } ?: bonded.firstOrNull { d -> runCatching { d.name }.getOrNull()?.contains("corolla", true) == true }
        ?: bonded.firstOrNull()
        if (device == null) {
            DiagLog.w(tag, "no bonded bluetooth device to hand Android Auto")
            return
        }
        val name = runCatching { device.name }.getOrNull() ?: device.address
        for (action in listOf(
            "com.google.android.projection.gearhead.START_WIRELESS_PROJECTION",
            "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
        )) {
            val i = Intent(action)
                .setPackage("com.google.android.projection.gearhead")
                .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra("com.google.android.apps.auto.wireless.setup.service.EXTRA_BLUETOOTH_DEVICE", device)
            runCatching { sendBroadcast(i) }
                .onSuccess { DiagLog.i(tag, "asked Android Auto to start wireless projection with '$name' via $action") }
                .onFailure { DiagLog.w(tag, "wireless projection request failed: ${it.message}") }
        }
    }

    private fun startAndroidAuto() {
        val pkg = app.prefs.aaPackage
        if (runCatching { packageManager.getPackageInfo(pkg, 0) }.isFailure) {
            DiagLog.w(tag, "Android Auto ($pkg) is not installed on this phone")
            return
        }
        mirrorStop()
        if (AaHeadUnitService.current == null && !AaHeadUnitService.state.value.listening) {
            AaHeadUnitService.start(this, app.prefs.aaBluetooth)
        }
        DiagLog.i(tag, "bridging this phone's Android Auto onto the car, waiting for it to start projecting")
        aaWatch?.cancel()
        aaWatch = scope.launch {
            AaHeadUnitService.state.collect { s ->
                if (s.connected && !_state.value.aaOverlay) {
                    DiagLog.i(tag, "Android Auto is projecting, showing it on the car")
                    aaOverlay(true)
                }
            }
        }
    }

    private fun routeTouch(action: Int, x: Int, y: Int) {
        val st = _state.value
        val corner = x < CORNER && y < CORNER
        val aa = AaHeadUnitService.current
        if (st.aaOverlay && !corner && aa != null && aa.phase.value >= AaSession.Phase.DISCOVERED) {
            val p = app.prefs
            val w = carDisplay.width.coerceAtLeast(1)
            val h = carDisplay.height.coerceAtLeast(1)
            aa.sendTouch(x * p.aaWidth / w, y * p.aaHeight / h, action)
            return
        }
        if (st.mirroring && !corner) {
            val svc = FTTouchService.instance
            if (svc != null && mirror.active) {
                if (mirror.ownDisplay) {
                    svc.inject(action, x.toFloat().coerceIn(0f, mirror.width - 1f), y.toFloat().coerceIn(0f, mirror.height - 1f), mirror.displayId)
                } else {
                    val w = carDisplay.width.coerceAtLeast(1).toFloat()
                    val h = carDisplay.height.coerceAtLeast(1).toFloat()
                    val scale = minOf(w / mirror.width, h / mirror.height)
                    val offX = (w - mirror.width * scale) / 2f
                    val offY = (h - mirror.height * scale) / 2f
                    svc.inject(action, ((x - offX) / scale).coerceIn(0f, mirror.width - 1f), ((y - offY) / scale).coerceIn(0f, mirror.height - 1f))
                }
                return
            }
        }
        carDisplay.dispatchTouch(action, x, y)
    }

    private fun onHardKey(key: Int) {
        val st = _state.value
        when {
            st.aaOverlay -> {
                val aa = AaHeadUnitService.current
                if (aa != null && key in 3..4) aa.sendKey(key) else aaOverlay(false)
            }
            st.mirroring -> if (key == 4) FTTouchService.instance?.back() else mirrorStop()
            else -> Unit
        }
    }

    private fun aaOverlay(on: Boolean) {
        if (on) mirrorStop() else aaWatch?.cancel()
        _state.update { it.copy(aaOverlay = on) }
        DiagLog.i(tag, "android auto overlay ${if (on) "on" else "off"}")
        carDisplay.requestKeyFrame()
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            DiagLog.w(tag, "no launcher for $pkg")
            return
        }
        launchIntent(intent, pkg)
    }

    private fun launchIntent(intent: Intent, pkg: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!mirror.ready && !mirrorOpen(pkg, ownDisplay = true)) {
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
            return
        }
        var placed = false
        if (mirror.ownDisplay && mirror.displayId > 0) {
            placed = runCatching {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(mirror.displayId)
                startActivity(intent, options.toBundle())
                true
            }.getOrElse { t ->
                DiagLog.w(tag, "$pkg would not open on the car display (${t.javaClass.simpleName}: ${t.message}), mirroring the phone instead")
                false
            }
            if (!placed) {
                DiagLog.w(tag, "this phone will not let FT place apps on a car-sized display; turn 'Car-sized apps' off in Settings to mirror instead")
            }
        }
        if (!placed) {
            runCatching { startActivity(intent) }.onFailure { DiagLog.e(tag, "launch failed", it) }
        }
        DiagLog.i(tag, "launched $pkg on ${if (mirror.ownDisplay) "the car display" else "the phone, mirrored"}")
        mirrorStart(pkg)
    }

    private fun mirrorStart(pkg: String) {
        if (!mirror.ready && !mirrorOpen(pkg, ownDisplay = true)) {
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
            return
        }
        _state.update { it.copy(aaOverlay = false, mirroring = true, mirrorPackage = pkg) }
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            MirrorSink.surface.collect { surface ->
                try {
                    if (surface != null) mirror.show(surface) else mirror.hide()
                    carDisplay.requestKeyFrame()
                } catch (t: Throwable) {
                    DiagLog.e(tag, "mirror surface failed", t)
                }
            }
        }
    }

    private fun ensureProjection(reason: String): MediaProjection? {
        projection?.let { return it }
        val code = app.mirrorResultCode
        val data = app.mirrorData
        if (code == 0 || data == null) {
            DiagLog.w(tag, "screen sharing not permitted yet, open FT on the phone and tap Allow")
            return null
        }
        return try {
            foreground(reason, projection = true, microphone = canRecord())
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            app.mirrorResultCode = 0
            app.mirrorData = null
            if (mp == null) {
                DiagLog.w(tag, "media projection unavailable")
                app.mirrorGranted.value = false
                return null
            }
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    DiagLog.i(tag, "screen sharing ended")
                    projection = null
                    app.mirrorGranted.value = false
                    audio.stop()
                    mirrorJob?.cancel()
                    mirrorJob = null
                    mirror.close()
                    _state.update { it.copy(mirroring = false, mirrorPackage = "") }
                    carDisplay.requestKeyFrame()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
            mp
        } catch (t: Throwable) {
            DiagLog.e(tag, "could not start screen sharing", t)
            app.mirrorResultCode = 0
            app.mirrorData = null
            app.mirrorGranted.value = false
            null
        }
    }

    private fun startAudioToCar() {
        if (audio.active) return
        if (!canRecord()) {
            DiagLog.w(tag, "no microphone permission, the car will get picture without sound")
            return
        }
        val mp = ensureProjection("Sending sound to the car")
        if (mp == null) {
            askForScreenShare()
            return
        }
        audio.start(mp)
    }

    private fun askForScreenShare() {
        if (askedForShare) return
        askedForShare = true
        DiagLog.i(tag, "asking for screen sharing so the car can have sound")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("mirror", true)
            )
        }.onFailure { DiagLog.w(tag, "could not open FT to ask for screen sharing: ${it.message}") }
    }

    fun volume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI
        )
    }

    private fun mirrorOpen(pkg: String, ownDisplay: Boolean): Boolean {
        val mp = ensureProjection("Showing $pkg on the car") ?: return false
        return try {
            val useCar = ownDisplay && app.prefs.carSizedApps && carDisplay.width > 0 && carDisplay.height > 0
            val metrics = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            val w = if (useCar) carDisplay.width else metrics.width()
            val h = if (useCar) carDisplay.height else metrics.height()
            val d = if (useCar) app.prefs.carDensity else resources.displayMetrics.densityDpi
            val ok = mirror.open(mp, w, h, d, useCar)
            if (ok && !audio.active) startAudioToCar()
            ok
        } catch (t: Throwable) {
            DiagLog.e(tag, "mirror start failed", t)
            false
        }
    }

    private fun mirrorStop() {
        mirrorJob?.cancel()
        mirrorJob = null
        val was = mirror.active
        mirror.hide()
        if (_state.value.mirroring) _state.update { it.copy(mirroring = false, mirrorPackage = "") }
        if (was) DiagLog.i(tag, "mirror stopped")
        carDisplay.requestKeyFrame()
    }

    private fun mirrorClose() {
        mirrorStop()
        audio.stop()
        mirror.close()
        if (projection != null) {
            runCatching { projection?.stop() }
            projection = null
            app.mirrorGranted.value = false
            DiagLog.i(tag, "screen sharing released, allow it again on the phone for the next drive")
        }
    }

    private fun home() {
        mirrorStop()
        aaOverlay(false)
    }

    private fun teardown() {
        mirrorClose()
        beacon.stop()
        bt.stop()
        aaAutoLaunched = false
        finderFallback?.cancel()
        finderFallback = null
        aaWatch?.cancel()
        aaWatch = null
        finder?.stop()
        finder = null
        session?.stop()
        session = null
        stateJob?.cancel()
        wifiLink?.stop()
        wifiLink = null
        listenOnly = false
        askedForShare = false
        carDisplay.stop()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        _state.value = CarState()
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("FT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    override fun onDestroy() {
        teardown()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
