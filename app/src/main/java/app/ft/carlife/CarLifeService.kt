package app.ft.carlife

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
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
    val carIp: String? = null
)

class CarLifeService : Service() {
    companion object {
        const val ACTION_WIFI = "app.ft.carlife.WIFI"
        const val ACTION_AUTO = "app.ft.carlife.AUTO"
        const val ACTION_STOP = "app.ft.carlife.STOP"
        const val CORNER = 96
        private const val CHANNEL = "ft_carlife"
        private const val NOTIFICATION_ID = 41

        private val _state = MutableStateFlow(CarState())
        val state: StateFlow<CarState> = _state
        @Volatile private var instance: CarLifeService? = null

        fun startWifi(context: Context) {
            context.startForegroundService(Intent(context, CarLifeService::class.java).setAction(ACTION_WIFI))
        }

        fun startAuto(context: Context) {
            context.startForegroundService(Intent(context, CarLifeService::class.java).setAction(ACTION_AUTO))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CarLifeService::class.java).setAction(ACTION_STOP))
        }

        fun pickCar(name: String) = instance?.finder?.connectByName(name)
        fun searchAgain() = instance?.restartFinder()
        fun setAaOverlay(on: Boolean) = instance?.aaOverlay(on)
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
    private val app get() = application as FTApp

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
                startWifiLink()
            }
            ACTION_AUTO -> {
                foreground("Looking for the car")
                startWifiLink()
                startFinder()
            }
        }
        return START_STICKY
    }

    private fun foreground(text: String, projection: Boolean = false) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (projection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION_ID, notification(text), type)
        _state.update { it.copy(running = true, ip = NetUtil.localIpv4()) }
    }

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
            f.probe(l.groupOwnerIp, listOf(p.cmdPort, 7200))
            updateNotification("On the car network, waiting for the head unit")
        }
        f.onLeft = {
            _state.update { it.copy(carIp = null) }
            updateNotification("Looking for the car")
        }
        scope.launch { f.state.collect { s -> _state.update { it.copy(p2p = s) } } }
        scope.launch { f.peers.collect { ps -> _state.update { it.copy(peers = ps.map { p -> p.name }) } } }
        f.start()
    }

    private fun restartFinder() {
        finder?.stop()
        finder = null
        _state.update { it.copy(peers = emptyList(), carIp = null) }
        startFinder()
    }

    private fun attachSession(l: CarLifeLink) {
        session?.stop()
        val s = CarLifeSession(this, l, app.prefs, scope)
        session = s
        s.onVideoConfig = { w, h, fps -> onVideoConfig(w, h, fps) }
        s.onStartVideo = { carDisplay.requestKeyFrame() }
        s.onStopVideo = { onStopVideo() }
        s.onKeyFrameRequest = { carDisplay.requestKeyFrame() }
        s.onTouch = { a, x, y -> routeTouch(a, x, y) }
        s.onHardKey = { k -> onHardKey(k) }
        s.onClosed = { reason -> DiagLog.i(tag, "link closed: $reason") }
        stateJob?.cancel()
        stateJob = scope.launch { s.state.collect { st -> _state.update { it.copy(link = l.name, session = st) } } }
        s.start()
    }

    private fun onVideoConfig(w: Int, h: Int, fps: Int) {
        val s = session ?: return
        carDisplay.start(
            w, h, fps,
            onConfig = { cfg -> s.sendVideo(cfg) },
            onFrame = { frame, _ -> s.sendVideo(frame) }
        ) {
            CarTheme { CarScreen() }
        }
        updateNotification("Projecting ${w}×$h to the car")
    }

    private fun onStopVideo() {
        mirrorStop()
        carDisplay.stop()
        _state.update { it.copy(aaOverlay = false) }
        updateNotification(if (finder != null) "Looking for the car" else "Waiting for the head unit")
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
                val w = carDisplay.width.coerceAtLeast(1).toFloat()
                val h = carDisplay.height.coerceAtLeast(1).toFloat()
                val scale = minOf(w / mirror.width, h / mirror.height)
                val offX = (w - mirror.width * scale) / 2f
                val offY = (h - mirror.height * scale) / 2f
                svc.inject(action, ((x - offX) / scale).coerceIn(0f, mirror.width - 1f), ((y - offY) / scale).coerceIn(0f, mirror.height - 1f))
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
        if (on) {
            mirrorStop()
            if (AaHeadUnitService.current == null && !AaHeadUnitService.state.value.listening) AaHeadUnitService.start(this, app.prefs.aaBluetooth)
        }
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            DiagLog.i(tag, "launched $pkg")
        } catch (t: Throwable) {
            DiagLog.e(tag, "launch failed", t)
        }
        mirrorStart(pkg)
    }

    private fun mirrorStart(pkg: String) {
        val code = app.mirrorResultCode
        val data = app.mirrorData
        if (code == 0 || data == null) {
            DiagLog.w(tag, "screen mirror not permitted yet, open FT on the phone and tap Allow mirror")
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
            return
        }
        _state.update { it.copy(aaOverlay = false, mirroring = true, mirrorPackage = pkg) }
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            MirrorSink.surface.collect { surface ->
                if (surface != null && !mirror.active) {
                    try {
                        foreground("Mirroring $pkg to the car", projection = true)
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val mp = mpm.getMediaProjection(code, data)
                        app.mirrorResultCode = 0
                        app.mirrorData = null
                        val metrics = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
                        val dpi = resources.displayMetrics.densityDpi
                        if (mp != null) mirror.start(mp, metrics.width(), metrics.height(), dpi, surface)
                        carDisplay.requestKeyFrame()
                    } catch (t: Throwable) {
                        DiagLog.e(tag, "mirror start failed", t)
                        _state.update { it.copy(mirroring = false) }
                    }
                }
            }
        }
    }

    private fun mirrorStop() {
        mirrorJob?.cancel()
        mirrorJob = null
        val was = mirror.active
        mirror.stop()
        if (_state.value.mirroring) _state.update { it.copy(mirroring = false, mirrorPackage = "") }
        if (was) DiagLog.i(tag, "mirror stopped")
        carDisplay.requestKeyFrame()
    }

    private fun home() {
        mirrorStop()
        aaOverlay(false)
    }

    private fun teardown() {
        mirrorStop()
        finder?.stop()
        finder = null
        session?.stop()
        session = null
        wifiLink?.stop()
        wifiLink = null
        carDisplay.stop()
        stateJob?.cancel()
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
