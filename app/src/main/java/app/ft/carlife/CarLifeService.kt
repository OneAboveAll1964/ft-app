package app.ft.carlife
import app.ft.media.Track
import app.ft.media.MediaLibrary
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import app.ft.R
import app.ft.FTApp
import app.ft.core.Prefs
import app.ft.core.CarConnection
import app.ft.core.ServiceLaunches
import app.ft.FTTouchService
import app.ft.MainActivity
import app.ft.ShareActivity
import app.ft.aa.AaKeep
import app.ft.aa.AaInstaller
import app.ft.aa.AaTouchViewport
import app.ft.aa.AaTouchGestureViewport
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.aa.AaVideoFocus
import app.ft.core.CarAssistantPanel
import app.ft.core.CarVoiceAssistant
import app.ft.core.CarAudioBus
import app.ft.core.CarBluetoothReconnect
import app.ft.core.DiagLog
import app.ft.core.CarProtocol
import app.ft.supercast.SuperCast
import app.ft.core.DiagSnapshot
import app.ft.core.Root
import app.ft.core.RootPrep
import app.ft.media.CarPlayer
import app.ft.projection.CarDisplay
import app.ft.projection.AudioCapture
import app.ft.projection.CarTouchZones
import app.ft.projection.MirrorSink
import app.ft.projection.PhoneMirror
import app.ft.projection.VideoPlan
import app.ft.projection.VideoPlans
import app.ft.ui.car.CarScreen
import app.ft.ui.theme.CarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible
import app.ft.aa.AaConnectionPreparation
import app.ft.core.ConnectionPreparation
import app.ft.core.Lang
import kotlinx.coroutines.flow.drop

data class CarState(
    val aaPreparation: AaConnectionPreparation.Status? = null,
    val running: Boolean = false,
    val direct: Boolean = false,
    val link: String = "",
    val session: CarLifeSession.State = CarLifeSession.State.Idle,
    val video: VideoPlan? = null,
    val listening: Boolean = false,
    val aaOverlay: Boolean = false,
    val mirroring: Boolean = false,
    val mirrorPackage: String = "",
    val ip: String? = null,
    val btCar: String? = null,
    val carWifi: String? = null,
    val wifiDirect: Boolean = false,
    val joinTimedOutSeconds: Int = 0,
    val beacon: Boolean = false,
    val step: String = "",
    val refused: Int = 0,
    val waitingForShare: Boolean = false,
    val shareForSound: Boolean = false,
    val notice: Int = 0,
    val usb: Boolean = false,
    val blocked: Int = 0,
    val hotspotClients: List<String>? = null
) {
    // Only evidence from this connection attempt counts; saved identity and HFP do not.
    val carFound: Boolean
        get() = !btCar.isNullOrBlank() || !carWifi.isNullOrBlank() ||
            wifiDirect || session !is CarLifeSession.State.Idle

    fun withBluetoothStep(text: String): CarState =
        if (carFound) this else copy(step = text)

    fun withBluetoothCar(name: String?): CarState = copy(
        btCar = name,
        carWifi = if (name != null) null else carWifi,
        step = if (name != null && !wifiDirect && session is CarLifeSession.State.Idle)
            "'$name' called over bluetooth" else step
    )

    // Closing SPP alone is normal during P2P setup. Expire its WiFi announcement
    // only when the wireless attempt ends. Live BT/group/session evidence remains.
    fun withWirelessAttemptEnded(): CarState {
        val current = copy(carWifi = null)
        return if (!current.carFound) current.copy(step = "Waiting for the car to call over bluetooth") else current
    }

    fun withSessionState(state: CarLifeSession.State, via: String): CarState = copy(
        link = via,
        session = state,
        refused = if (state !is CarLifeSession.State.Idle) 0 else refused,
        step = when (state) {
            is CarLifeSession.State.Linked -> "CarLife connected via $via"
            is CarLifeSession.State.Negotiated -> "CarLife video configured via $via"
            is CarLifeSession.State.Projecting -> "CarLife projecting via $via"
            CarLifeSession.State.Idle -> if (session is CarLifeSession.State.Idle) step else when {
                wifiDirect -> "WiFi Direct connected"
                !btCar.isNullOrBlank() || !carWifi.isNullOrBlank() -> "Joining the car WiFi Direct"
                else -> "Waiting for the car to call over bluetooth"
            }
        }
    )
}

class CarLifeService : Service() {
    companion object {
        const val ACTION_WIFI = "app.ft.carlife.WIFI"
        const val ACTION_AUTO = "app.ft.carlife.AUTO"
        const val ACTION_STOP = "app.ft.carlife.STOP"
        const val ACTION_AUDIO = "app.ft.carlife.AUDIO"
        const val ACTION_SHARE_DECLINED = "app.ft.carlife.SHARE_DECLINED"
        const val ACTION_USB = "app.ft.carlife.USB"
        private const val USB_PERMISSION = "app.ft.carlife.USB_PERMISSION"
        private val AA_KEYS = setOf(3, 4, 19, 20, 21, 22, 23, 84, 85, 86, 87, 88, 126, 127)
        private val MEDIA_KEYS = setOf(85, 87, 88, 126, 127)
        private const val CHANNEL = "ft_carlife"
        private const val NOTIFICATION_ID = 41
        private const val VOICE_START = 0
        private const val VOICE_DATA = 1
        private const val VOICE_END = 2
        private const val VOICE_BACKLOG_BYTES = 64_000L
        private const val SOUND_BYTES_PER_SECOND = 192_000
        private const val MAX_QUEUED_SOUND = SOUND_BYTES_PER_SECOND * 3 / 2
        private const val CATCH_UP_AFTER = SOUND_BYTES_PER_SECOND * 15 / 100
        private const val QUIET_LEVEL = 300
        private const val SOUND_REPORT_MS = 10_000L
        private const val GOODBYE_WAIT_MS = 300L
        private const val USB_QUICK_END_MS = 5_000L
        private const val USB_QUIET_MS = 10_000L
        private const val TEST_CABLE_PORT = 7300
        private const val SOUND_DRAIN_MS = 2_000L
        private const val BESIDE_SLOP = 24f
        private const val GEAR_SETTLE_MS = 1_500L
        private const val GEAR_HOLD_MS = 700L
        private const val GEAR_LOOK_MS = 1_500L
        private const val GEAR_VOLUME_HOLD_MS = 4_000L

        private val _state = MutableStateFlow(CarState())
        val state: StateFlow<CarState> = _state
        private val _screenKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val screenKeys: SharedFlow<Int> = _screenKeys
        private val _openView = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val openView: SharedFlow<String> = _openView
        @Volatile private var instance: CarLifeService? = null
        private val launches = ServiceLaunches()
        val isBusy: Boolean get() = instance != null || launches.hasPending

        fun startWifi(context: Context) = startAuto(context)

        fun startAuto(context: Context) {
            if (FTApp.instance.prefs.carProtocol != CarProtocol.CARLIFE) return
            if (!FTApp.instance.prefs.autoConnect) {
                DiagLog.i("CarLife", "FT is switched off, nothing starts")
                return
            }
            send(context, ACTION_AUTO, foreground = true)
        }

        fun switchOn(context: Context) {
            FTApp.instance.prefs.autoConnect = true
            startAuto(context)
        }

        fun switchOff(context: Context) {
            FTApp.instance.prefs.autoConnect = false
            DiagLog.i("CarLife", "FT switched off")
            stop(context)
            AaHeadUnitService.stop(context)
        }

        fun stop(context: Context) {
            if (!isBusy) return
            send(context, ACTION_STOP, foreground = false)
        }

        fun useUsb(context: Context, accessory: android.hardware.usb.UsbAccessory) {
            if (FTApp.instance.prefs.carProtocol != CarProtocol.CARLIFE) return
            val intent = Intent(context, CarLifeService::class.java).setAction(ACTION_USB).putExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY, accessory)
            launches.dispatch(intent)
            runCatching { context.startForegroundService(intent) }
                .onFailure { launches.finished(); DiagLog.w("CarLife", "Android would not let FT start for the car's USB (${it.javaClass.simpleName}), open FT to connect") }
        }

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, CarLifeService::class.java).setAction(action)
            launches.dispatch(intent)
            runCatching { if (foreground) context.startForegroundService(intent) else context.startService(intent) }
                .onFailure { launches.finished(); DiagLog.w("CarLife", "Android would not let FT start right now (${it.javaClass.simpleName}), open FT to connect") }
        }

        fun soundSoon() {
            instance?.let { svc -> svc.scope.launch { svc.soundSoon() } }
        }

        fun startAudio(context: Context) {
            if (FTApp.instance.prefs.carProtocol != CarProtocol.CARLIFE) return
            if (instance == null) return
            send(context, ACTION_AUDIO, foreground = true)
        }

        fun shareDeclined(context: Context, quiet: Boolean = false) {
            if (instance == null) return
            context.startService(Intent(context, CarLifeService::class.java).setAction(ACTION_SHARE_DECLINED).putExtra("quiet", quiet))
        }

        fun cancelShareWait() = instance?.let { svc ->
            svc.pendingLaunch = null
            if (_state.value.shareForSound) {
                svc.soundDeclined = true
                DiagLog.i("CarLife", "sound stays on the phone for this drive")
            }
            _state.update { it.copy(waitingForShare = false, shareForSound = false) }
        }

        fun btSend(hex: String) = instance?.sendBluetooth(hex)
        fun btEcho(on: Boolean) = instance?.echoBluetooth(on)
        fun tryAgain() = instance?.retry()
        fun startAa() = instance?.startAndroidAuto()
        fun startAaWireless() = instance?.triggerAaWireless()
        fun volumeUp() = instance?.volume(true)
        fun volumeDown() = instance?.volume(false)
        fun navBack() = phoneKey("back") { it.back() }
        fun navHome() = phoneKey("home") { it.home() }
        fun navRecents() = phoneKey("recent apps") { it.recents() }
        fun navNotifications() = phoneKey("notifications") { it.notifications() }
        fun rotatePhone() = instance?.rotate()
        fun showPhone() = instance?.phoneScreen()
        fun launchApp(pkg: String) = instance?.launch(pkg)
        fun stopMirror() = instance?.mirrorStop()
        fun goToCarHome() = instance?.carHome()
        fun goHome() = instance?.home()

        private fun phoneKey(name: String, action: (FTTouchService) -> Boolean) {
            val svc = FTTouchService.instance
            if (svc == null) {
                DiagLog.w("CarLife", "phone $name needs FT's accessibility switch on")
                return
            }
            if (action(svc)) DiagLog.i("CarLife", "phone $name") else DiagLog.w("CarLife", "the phone did not take $name")
        }
    }

    private val tag = "CarLife"
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preparation = ConnectionPreparation(preparationScope) {
        AaConnectionPreparation.prepare(this@CarLifeService, prefs) { status ->
            _state.update { it.copy(aaPreparation = status) }
        }
    }
    private var aaStartJob: Job? = null
    private lateinit var carDisplay: CarDisplay
    private val mirror = PhoneMirror()
    @Volatile private var session: CarLifeSession? = null
    private var sessionLink: CarLifeLink? = null
    @Volatile private var wifiCommandGroup: Pair<CarFinder, Long>? = null
    private val navigationPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "aaClusterNavigation" || key == null) scope.launch { session?.refreshNavigationPreference() }
        if (key == "carSongInfo" || key == null) scope.launch {
            songInfoSuspended = false
            songInfoAt = 0L
            session?.allowSongInfo(true)
        }
    }
    private var wifiLink: WifiChannelLink? = null
    private var usbLink: AccessoryLink? = null
    @Volatile private var usbAccessory: android.hardware.usb.UsbAccessory? = null
    private var usbAsk: android.content.BroadcastReceiver? = null
    private var testCable: Job? = null
    private var languageWatch: Job? = null
    @Volatile private var testCableServer: java.net.ServerSocket? = null
    private var finder: CarFinder? = null
    private var stateJob: Job? = null
    private var musicJob: Job? = null
    @Volatile private var mirrorJob: Job? = null
    private var ipJob: Job? = null
    private val startupWatch = CarLifeStartupWatch()
    private val peerWatch = WirelessPeerWatch()
    private var startupRetry: Job? = null
    private var aaWatch: Job? = null
    @Volatile private var mode = -1
    private var callWatch: Any? = null
    private var callPoll: Job? = null
    private var resumePhoneAudio: Pair<MediaProjection, Long>? = null
    @Volatile private var callRecovery: Any? = null
    private var callRecoveryJob: Job? = null
    private var p2pWatch: Job? = null
    @Volatile private var note: () -> String = { Lang.str(R.string.app_name) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wheelPending: Job? = null
    private var wheelKey = -1

    private val WHEEL_AGAIN_MS = 300L
    private var wheelAt = 0L
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Volatile private var projection: MediaProjection? = null
    private val projectionLock = Any()
    private val audio = AudioCapture { pcm -> CarAudioBus.write(CarAudioBus.LANE_PHONE, pcm) }
    private val soundQueue = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-car-sound").apply { isDaemon = true }
    }
    private val launchTicket = java.util.concurrent.atomic.AtomicInteger(0)
    private val outbound = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = java.util.concurrent.atomic.AtomicInteger(0)
    private val skippedBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val caughtUpBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var writer: Thread? = null
    private val carAudio: (ByteArray) -> Unit = { pcm ->
        startWriter()
        if (queuedBytes.get() > CATCH_UP_AFTER && quiet(pcm)) {
            caughtUpBytes.addAndGet(pcm.size.toLong())
        } else {
            outbound.offer(pcm)
            var queued = queuedBytes.addAndGet(pcm.size)
            while (queued > MAX_QUEUED_SOUND) {
                val old = outbound.poll() ?: break
                queued = queuedBytes.addAndGet(-old.size)
                skippedBytes.addAndGet(old.size.toLong())
            }
        }
    }

    private fun quiet(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            if (v > QUIET_LEVEL || v < -QUIET_LEVEL) return false
            i += 2
        }
        return true
    }

    @Volatile private var thermalCap = 0
    private val heatListener = PowerManager.OnThermalStatusChangedListener { status -> onHeat(status) }

    private fun watchHeat() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        runCatching { pm.addThermalStatusListener(mainExecutor, heatListener) }
        onHeat(pm.currentThermalStatus)
    }

    private fun onHeat(status: Int) {
        val cap = VideoPlans.thermalCap(status)
        if (cap == thermalCap) return
        thermalCap = cap
        DiagLog.i(tag, if (cap > 0) "the phone is getting hot (thermal status $status), sending at most $cap frames a second to cool it"
            else "the phone has cooled down (thermal status $status), back to the full frame rate")
        session?.let { s -> if (s.projecting) carDisplay.setFrameRate(VideoPlans.withinCap(s.fps, cap)) }
    }

    private fun catchUp(reason: String) {
        var dropped = 0L
        while (true) {
            val old = outbound.poll() ?: break
            queuedBytes.addAndGet(-old.size)
            dropped += old.size
        }
        if (dropped >= SOUND_BYTES_PER_SECOND / 50) DiagLog.i(tag, "caught up ${dropped * 1000L / SOUND_BYTES_PER_SECOND} ms of waiting sound at $reason")
    }

    private fun startWriter() {
        if (writer?.isAlive == true) return
        writer = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var windowAt = android.os.SystemClock.elapsedRealtime()
            var mostQueued = 0
            var slowestWrite = 0L
            var reportedSkip = 0L
            var reportedCatchUp = 0L
            var lostSeen = skippedBytes.get()
            while (!Thread.currentThread().isInterrupted) {
                mostQueued = maxOf(mostQueued, queuedBytes.get())
                val pcm = try {
                    outbound.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (pcm != null) queuedBytes.addAndGet(-pcm.size)
                val s = session ?: continue
                if (pcm == null) {
                    if (s.mediaIdle()) DiagLog.i(tag, "sound to the car stopped, told the car the music paused")
                    continue
                }
                val first = !s.musicOpen
                val writeAt = System.nanoTime()
                s.sendAudio(pcm)
                slowestWrite = maxOf(slowestWrite, (System.nanoTime() - writeAt) / 1_000_000)
                val lost = skippedBytes.get()
                if (lost > lostSeen) {
                    lostSeen = lost
                    catchUp("the car taking sound again after a long pause")
                }
                if (first && s.musicOpen) DiagLog.i(tag, "sound to the car started, told the car the music is playing")
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowAt >= SOUND_REPORT_MS) {
                    val skipped = skippedBytes.get()
                    val caughtUp = caughtUpBytes.get()
                    DiagLog.i(tag, "sound waited on the phone up to ${mostQueued * 1000L / SOUND_BYTES_PER_SECOND} ms, slowest write ${slowestWrite} ms" +
                        (if (caughtUp > reportedCatchUp) ", caught up ${(caughtUp - reportedCatchUp) * 1000L / SOUND_BYTES_PER_SECOND} ms on silence" else "") +
                        if (skipped > reportedSkip) ", dropped ${(skipped - reportedSkip) * 1000L / SOUND_BYTES_PER_SECOND} ms the car was not taking in time" else "")
                    reportedSkip = skipped
                    reportedCatchUp = caughtUp
                    windowAt = now
                    mostQueued = 0
                    slowestWrite = 0L
                }
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; name = "ft-car-audio-out"; start() }
    }

    private class VoiceCmd(val kind: Int, val rate: Int = 0, val channels: Int = 0,
                         val pcm: ByteArray? = null, val output: CarLifeVoice? = null)
    private val voiceQueued = java.util.concurrent.atomic.AtomicLong(0)
    private val voiceOut = java.util.concurrent.LinkedBlockingQueue<VoiceCmd>()
    @Volatile private var voiceWriter: Thread? = null
    private val carVoice = object : CarAudioBus.Voice {
        override fun begin(rate: Int, channels: Int) = queueVoice(VoiceCmd(VOICE_START, rate, channels))
        override fun data(pcm: ByteArray) {
            if (voiceQueued.get() >= VOICE_BACKLOG_BYTES) return
            voiceQueued.addAndGet(pcm.size.toLong())
            queueVoice(VoiceCmd(VOICE_DATA, pcm = pcm))
        }
        override fun end() = queueVoice(VoiceCmd(VOICE_END))
    }

    private fun queueVoice(c: VoiceCmd) {
        startVoiceWriter()
        voiceOut.offer(VoiceCmd(c.kind, c.rate, c.channels, c.pcm, session?.voiceOutput))
    }

    @Synchronized
    private fun startVoiceWriter() {
        if (voiceWriter?.isAlive == true) return
        voiceWriter = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (true) {
                    val c = voiceOut.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    session?.voiceOutput?.tick()
                    if (c == null) {
                        CarAudioBus.tick()
                        continue
                    }
                    c.pcm?.let { voiceQueued.addAndGet(-it.size.toLong()) }
                    val output = c.output ?: continue
                    when (c.kind) {
                        VOICE_START -> {
                            output.begin(c.rate, c.channels)
                            DiagLog.i(tag, "voice on the car's own voice channel, ${c.rate} Hz")
                        }
                        VOICE_DATA -> c.pcm?.let { output.data(it) }
                        else -> {
                            output.end()
                            DiagLog.i(tag, "voice finished")
                        }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; name = "ft-car-voice-out"; start() }
    }
    @Volatile private var aaAutoLaunched = false
    @Volatile private var autoOpened = false
    @Volatile private var quietShareFailed = false
    @Volatile private var quietShareAt = 0L
    @Volatile private var resumedMedia = false
    private var aaReturn: Job? = null
    @Volatile private var pendingLaunch: Pair<Intent, String>? = null
    @Volatile private var soundDeclined = false
    @Volatile private var blockedSoundNoticed = false
    @Volatile private var songInfoAt = 0L
    @Volatile private var songInfoSuspended = false
    @Volatile private var mediaReady = false
    @Volatile private var touchFt = false
    @Volatile private var touchBeside = false
    @Volatile private var carPaused = false
    private var soundWatch: Job? = null
    private var progressJob: Job? = null
    @Volatile private var turnWatch: android.hardware.display.DisplayManager.DisplayListener? = null
    private var savedRotation: Pair<Int, Int>? = null
    private var turnJob: Job? = null
    private var rotateJob: Job? = null
    private var lastSongPositionAt = 0L
    private val phoneVolume by lazy {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        PhoneVolume(getSharedPreferences(PhoneVolume.STORE, Context.MODE_PRIVATE),
            { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) },
            { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it, 0) },
            { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) },
            { DiagLog.w(tag, "phone volume recovery: ${it.message}") })
    }
    private val savedVolume get() = phoneVolume.saved
    private val app get() = application as FTApp
    private lateinit var prefs: Prefs
    private val voiceAssistant by lazy {
        CarVoiceAssistant(this, { prefs }, { _state.value.aaOverlay }, showAlice = {
            if (carDisplay.active.value) CarAssistantPanel.toggle(this)
        }) { AaHeadUnitService.current?.sendKey(84) }
    }
    private val beacon by lazy { CarBeacon(scope, { beaconTargets() }, { carGroupOwner() }, { helloForCar() }) }
    private val hotspotClients by lazy {
        HotspotClients(scope, { NetUtil.hotspotAddresses(this) }, { mode == 0 && wifiLink?.connected != true }) { list -> onHotspotClients(list) }
    }
    private val busyPorts: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val discovery by lazy { CarDiscovery(scope) { from -> sameWay(from) } }
    @Volatile private var p2pIface: String? = null
    @Volatile private var carP2pIp: String? = null
    private val bt by lazy { CarBluetooth(this, prefs, scope) }
    @Volatile private var wirelessBluetoothGeneration = 0L
    private val btAudio by lazy { CarBtAudio(this) }
    private var bluetoothReconnect: CarBluetoothReconnect? = null
    private fun step(text: String) {
        DiagLog.i(tag, text)
        _state.update { it.copy(step = text) }
    }

    private val wireless by lazy {
        CarWirelessSetup(
            send = { bytes -> bt.send(bytes, wirelessBluetoothGeneration) },
            onCarWifiName = { name -> queueWirelessName(name) },
            progress = { text ->
                if (wifiLink?.connected == true) DiagLog.i(tag, text) else step(text)
            },
            commandConnected = { wifiLink?.connected == true }
        )
    }

    internal fun queueWirelessName(name: String) {
        val generation = wirelessBluetoothGeneration
        val exchange = wireless.exchange
        val f = finder
        scope.launch(Dispatchers.Main) {
            if (mode == 1 && finder === f && bt.connectionGeneration == generation &&
                wireless.exchange == exchange) onCarRaisedWifiDirect(name)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = app.prefs
        instance = this
        CarConnection.lifecycleChanged()
        phoneVolume.restore()
        prefs.registerListener(navigationPreferenceListener)
        CarAudioBus.mixTogether = true
        CarAudioBus.guidance = prefs.guidanceMode
        CarAudioBus.voice = carVoice
        CarAudioBus.sink = carAudio
        watchCalls()
        CarPlayer.onTrack = { t ->
            catchUp("a new song")
            if (!t.video) scope.launch { tellCarTheSong(t) }
        }
        CarPlayer.onBreak = { reason -> catchUp(reason) }
        CarAudioBus.onBreak = { reason -> catchUp(reason) }
        watchHeat()
        MirrorSink.onGone = { surface -> if (mirror.isShowing(surface)) mirror.hide() }
        watchCarBluetoothAudio()
        bluetoothReconnect = CarBluetoothReconnect(this, prefs).also { it.start() }
        watchUsb()
        progressJob = scope.launch {
            CarPlayer.state.collect { st -> songPosition(st) }
        }
        carDisplay = CarDisplay(this)
        carDisplay.onVideoChanged = { plan -> _state.update { it.copy(video = plan) } }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, Lang.str(R.string.channel_projection), NotificationManager.IMPORTANCE_LOW))
        languageWatch = scope.launch {
            Lang.current.drop(1).collect {
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .createNotificationChannel(NotificationChannel(CHANNEL, Lang.str(R.string.channel_projection), NotificationManager.IMPORTANCE_LOW))
                }
                if (_state.value.running) updateNotification(note)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launches.received(intent)
        // Late USB, audio and autostart intents must not override the selected backend.
        if (intent?.action != ACTION_STOP && prefs.carProtocol != CarProtocol.CARLIFE) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if ((intent == null || intent.action == ACTION_AUTO) && !prefs.autoConnect) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                session?.let { s -> Thread { runCatching { s.goodbye() } }.apply { start(); join(GOODBYE_WAIT_MS) } }
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUDIO -> {
                foreground(note, projection = true, microphone = canRecord())
                val pending = pendingLaunch
                pendingLaunch = null
                if (pending == null) _state.update { it.copy(waitingForShare = false, shareForSound = false) }
                val ticket = launchTicket.get()
                soundLater {
                    audio.stop()
                    startAudioToCar()
                    if (pending != null) {
                        launchIntent(pending.first, pending.second, ticket = ticket)
                        if (pendingLaunch == null) _state.update { it.copy(waitingForShare = false, shareForSound = false) }
                    }
                }
            }
            ACTION_SHARE_DECLINED -> {
                if (intent.getBooleanExtra("quiet", false)) {
                    quietShareFailed = true
                    DiagLog.i(tag, "this phone still asks before sharing, FT will ask on the phone from now on")
                    val pending = pendingLaunch
                    if (pending != null) {
                        val pkg = pending.second
                        _state.update { it.copy(mirroring = false, mirrorPackage = pkg, waitingForShare = true, shareForSound = false) }
                        askForScreenShare()
                    } else {
                        _state.update { it.copy(waitingForShare = false, shareForSound = false) }
                    }
                    return START_STICKY
                }
                pendingLaunch = null
                if (_state.value.shareForSound) soundDeclined = true
                _state.update { it.copy(waitingForShare = false, shareForSound = false, mirrorPackage = "") }
                DiagLog.i(tag, "screen sharing was declined on the phone")
            }
            ACTION_USB -> {
                val acc: android.hardware.usb.UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY, android.hardware.usb.UsbAccessory::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY)
                if (acc != null) {
                    logEnvironment()
                    useUsb(acc)
                } else {
                    DiagLog.w(tag, "USB: Android did not pass on the car's accessory")
                    foreground(note)
                    if (mode == -1) startChosenWay()
                }
            }
            ACTION_AUTO, ACTION_WIFI, null -> {
                if (intent == null) DiagLog.i(tag, "Android restarted FT after it was stopped, listening for the car again")
                logEnvironment()
                startChosenWay()
            }
        }
        return START_STICKY
    }

    private fun logEnvironment() {
        scope.launch {
            RootPrep.grants(this@CarLifeService)
            val now = android.os.SystemClock.elapsedRealtime()
            if (envLoggedAt < 0 || now - envLoggedAt > 60_000) {
                envLoggedAt = now
                DiagLog.i("Env", "connecting via ${wayName(if (mode == 2) 2 else prefs.linkMode)}, what FT sees on this phone:")
                DiagSnapshot.connectionStart(this@CarLifeService).forEach { DiagLog.i("Env", it) }
            }
        }
    }

    private fun wayName(way: Int) = when (way) {
        1 -> "WiFi + BL"
        2 -> "USB"
        else -> "Hotspot"
    }

    private fun startChosenWay() {
        preparationScope.launch {
            foreground(note)
            preparation.run(prefs.aaPrepareBeforeConnect) { startChosenWayReady() }
        }
    }

    private fun startChosenWayReady() {
        listenForTestCable()
        if (mode == 2 && usbLink != null) {
            foreground(note)
            DiagLog.i(tag, "the car is on the USB cable, staying on it")
            return
        }
        if (cableFirst()) return
        val wanted = when (prefs.linkMode) {
            1 -> 1
            2 -> 2
            else -> 0
        }
        if (mode != -1 && mode != wanted) {
            DiagLog.i(tag, "switching to ${wayName(wanted)}, the other way is closed")
            stopLink()
        }
        if (wanted == 2) {
            mode = 2
            foreground(waitingNote())
            _state.update { it.copy(direct = false, usb = true) }
            step("Plug the phone into the car's USB and open CarLife on the car")
            return
        }
        val fresh = mode != wanted || wifiLink == null
        mode = wanted
        foreground(if (fresh) waitingNote() else note)
        _state.update { it.copy(direct = wanted == 1, usb = false) }
        startWifiLink()
        if (fresh) {
            if (wanted == 1) startDirect() else startHotspot()
        }
    }

    private fun cableCarLife(): android.hardware.usb.UsbAccessory? {
        val um = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return null
        return runCatching { um.accessoryList?.firstOrNull { UsbCar.isCarLife(it) } }.getOrNull()
    }

    private fun cableFirst(): Boolean {
        if (android.os.SystemClock.elapsedRealtime() < usbQuietUntil) return false
        val acc = cableCarLife() ?: return false
        val um = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        if (um.hasPermission(acc)) {
            useUsb(acc)
            return true
        }
        askForCable(um, acc)
        return false
    }

    private fun askForCable(um: android.hardware.usb.UsbManager, acc: android.hardware.usb.UsbAccessory) {
        if (usbAsk == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val granted = i.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val a = cableCarLife()
                    DiagLog.i(tag, "USB: the phone ${if (granted) "allowed" else "did not allow"} FT to use the car's cable")
                    if (granted && a != null && prefs.autoConnect) useUsb(a)
                }
            }
            runCatching {
                val f = android.content.IntentFilter(USB_PERMISSION)
                androidx.core.content.ContextCompat.registerReceiver(this, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                usbAsk = r
            }.onFailure { DiagLog.w(tag, "USB: cannot listen for the cable permission: ${it.message}") }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, 7, Intent(USB_PERMISSION).setPackage(packageName), flags)
        DiagLog.i(tag, "USB: the car's CarLife is on the cable, asking the phone to let FT use it")
        runCatching { um.requestPermission(acc, pi) }.onFailure { DiagLog.w(tag, "USB: could not ask for the cable: ${it.message}") }
    }

    private var pendingUsb: android.hardware.usb.UsbAccessory? = null

    private fun useUsb(acc: android.hardware.usb.UsbAccessory) {
        if (prefs.carProtocol != CarProtocol.CARLIFE) return
        preparationScope.launch {
            foreground(note)
            pendingUsb = acc
            preparation.run(prefs.aaPrepareBeforeConnect) {
                pendingUsb = null
                val manager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                if (manager.accessoryList?.contains(acc) == true) useUsbReady(acc)
            }
        }
    }

    private fun useUsbReady(acc: android.hardware.usb.UsbAccessory) {
        if (mode == 2 && usbLink != null) DiagLog.i(tag, "USB: the car attached again, starting the cable link over")
        DiagLog.i(tag, "USB: the car opened CarLife over the cable (${UsbCar.describe(acc)}), using the cable")
        val um = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        startCable(acc, "the car's ${acc.model ?: "CarLife"} over the cable") {
            val pfd = um.openAccessory(acc) ?: return@startCable null
            AccessoryLink.Pipe(java.io.FileInputStream(pfd.fileDescriptor), java.io.FileOutputStream(pfd.fileDescriptor)) { pfd.close() }
        }
    }

    private fun startCable(acc: android.hardware.usb.UsbAccessory?, what: String, open: () -> AccessoryLink.Pipe?) {
        if (prefs.carProtocol != CarProtocol.CARLIFE) return
        if (mode != -1) stopLink()
        mode = 2
        usbAccessory = acc
        usbStartedAt = android.os.SystemClock.elapsedRealtime()
        foreground({ Lang.str(R.string.note_usb_connected) })
        _state.update { it.copy(direct = false, usb = true, listening = true) }
        val l = AccessoryLink("USB", what, open)
        usbLink = l
        attachSession(l)
    }

    private fun listenForTestCable() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || testCable != null) return
        testCable = scope.launch {
            val ss = runCatching { NetUtil.listen(TEST_CABLE_PORT) { true } }.getOrNull() ?: return@launch
            testCableServer = ss
            DiagLog.i(tag, "USB test cable listening on $TEST_CABLE_PORT")
            try {
                while (true) {
                    val s = ss.accept()
                    runCatching { s.tcpNoDelay = true }
                    launch(Dispatchers.Main) {
                        if (prefs.carProtocol != CarProtocol.CARLIFE) {
                            runCatching { s.close() }
                            return@launch
                        }
                        DiagLog.i(tag, "USB: a car came in on the test cable")
                        startCable(null, "the test cable") { AccessoryLink.Pipe(s.getInputStream(), s.getOutputStream()) { s.close() } }
                    }
                }
            } catch (t: Throwable) {
                DiagLog.d(tag, "USB test cable stopped: ${t.message}")
            } finally {
                runCatching { ss.close() }
            }
        }
    }

    private fun usbGone(why: String) {
        if (mode != 2 || usbLink == null) return
        DiagLog.i(tag, "USB: the cable link ended ($why)")
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - usbStartedAt < USB_QUICK_END_MS) usbQuietUntil = now + USB_QUIET_MS
        stopLink()
        mode = -1
        if (prefs.autoConnect) startChosenWay()
    }

    private fun waitingNote(): () -> String {
        val m = mode
        return {
            Lang.str(
                when (m) {
                    1 -> R.string.note_waiting_bt
                    2 -> R.string.note_waiting_usb
                    else -> R.string.note_waiting_hotspot
                }
            )
        }
    }

    private fun foreground(text: () -> String, projection: Boolean = false, microphone: Boolean = false) {
        note = text
        val keepProjection = projection || this.projection != null
        val keepMicrophone = microphone || audio.active
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (keepProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (keepMicrophone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val built = notification(text())
        val fallback = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
        runCatching { startForeground(NOTIFICATION_ID, built, type) }
            .onFailure { t ->
                DiagLog.w(tag, "foreground type $type refused (${t.message}), continuing without the microphone type")
                runCatching { startForeground(NOTIFICATION_ID, built, fallback) }
            }
        awake()
        _state.update { it.copy(running = true, ip = NetUtil.localIpv4()) }
    }

    private fun holdWifiSteady() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wm.createWifiLock(mode, "FT:car") }.getOrNull()?.also {
            it.setReferenceCounted(false)
            runCatching { it.acquire() }
                .onSuccess { _ -> DiagLog.i(tag, "holding the wifi radio at low latency for the car") }
        }
    }

    private fun releaseWifi() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun awake() {
        if (mode == 2) releaseWifi() else holdWifiSteady()
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
        val p = prefs
        val l = WifiChannelLink(
            mapOf(
                CarLifeProtocol.CH_CMD to p.cmdPort,
                CarLifeProtocol.CH_VIDEO to p.videoPort,
                CarLifeProtocol.CH_MEDIA to p.mediaPort,
                CarLifeProtocol.CH_TTS to p.ttsPort,
                CarLifeProtocol.CH_VR to p.vrPort,
                CarLifeProtocol.CH_CTRL to p.touchPort
            ),
            refuse = { socket -> wrongWay(socket) },
            onBusy = { port -> portBusy(port) },
            onBound = { port -> portBound(port) },
            onCommandConnected = {
                // A listener survives group changes. Only a fresh CMD connection
                // can make a group's projection eligible to confirm its identity.
                wifiCommandGroup = finder?.takeIf { it.joined }?.let { it to it.connectionGeneration }
            },
            admissionDispatcher = Dispatchers.Main.immediate
        )
        wifiLink = l
        _state.update { it.copy(listening = true) }
        attachSession(l)
        discovery.start()
    }

    private fun startHotspot() {
        if (Root.granted) scope.launch {
            RootPrep.ensureHotspot(this@CarLifeService, NetUtil.hotspotIpv4(this@CarLifeService) != null)
            delay(3000)
            RootPrep.poke()
        }
        step("Waiting for the car to join the hotspot")
        updateBeacon()
        hotspotClients.start()
    }

    private fun onHotspotClients(list: List<String>?) {
        if (mode != 0) return
        _state.update { it.copy(hotspotClients = list) }
        if (wifiLink?.connected == true) return
        when {
            list == null -> DiagLog.i(tag, "Hotspot: FT does not see the phone's hotspot running (interfaces: ${NetUtil.apInterfaces(this).ifEmpty { setOf("none reported") }.joinToString()})")
            list.isEmpty() -> {
                DiagLog.i(tag, "Hotspot: nothing has joined the phone's hotspot yet")
                step("Waiting for the car to join the hotspot")
            }
            else -> {
                DiagLog.i(tag, "Hotspot: ${list.size} device${if (list.size == 1) "" else "s"} on the phone's hotspot: ${list.joinToString()}; FT is calling them directly too")
                step("Something joined the hotspot (${list.joinToString()}), waiting for it to connect")
            }
        }
    }

    private fun portBusy(port: Int) {
        busyPorts.add(port)
        val baidu = installed("com.baidu.carlife")
        DiagLog.w(tag, "port $port is held by another app${if (baidu) ", and Baidu CarLife is installed" else ""}; FT keeps trying, the car cannot reach FT until it is free")
        _state.update {
            it.copy(blocked = if (baidu) R.string.blocked_baidu else R.string.blocked_other)
        }
    }

    private fun portBound(port: Int) {
        if (!busyPorts.remove(port) || busyPorts.isNotEmpty()) return
        DiagLog.i(tag, "CarLife's ports are free again, FT is listening")
        _state.update { it.copy(blocked = 0) }
    }

    private fun installed(pkg: String): Boolean = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private data class VehicleCandidate(val address: String, val name: String, val revision: Int)
    private val vehicleCandidates = java.util.concurrent.ConcurrentHashMap<String, VehicleCandidate>()

    private fun startDirect() {
        vehicleCandidates.clear()
        val revision = prefs.carLifeVehicleRevision
        wireless.reset()
        bt.onFrame = { generation, frame ->
            synchronized(wireless) {
                val bound = prefs.carLifeVehicleAddress
                if (mode == 1 && bt.connectionGeneration == generation &&
                    (bound.isBlank() || bound.equals(bt.carAddress, true))) wireless.feed(frame)
            }
        }
        bt.onStep = { text ->
            DiagLog.i(tag, text)
            _state.update { it.withBluetoothStep(text) }
        }
        bt.onCar = { generation, name ->
            synchronized(wireless) {
                val address = bt.carAddress
                val bound = prefs.carLifeVehicleAddress
                if (mode == 1 && bt.connectionGeneration == generation &&
                    (name == null || bound.isBlank() || bound.equals(address, true))) {
                    if (name != null) {
                        wirelessBluetoothGeneration = generation
                        wireless.reset()
                    } else wireless.bluetoothDisconnected()
                    val f = finder
                    scope.launch(Dispatchers.Main) {
                        if (mode == 1 && finder === f && bt.connectionGeneration == generation) {
                            _state.update { it.withBluetoothCar(name) }
                            if (name != null) {
                                vehicleCandidates[address.uppercase(java.util.Locale.ROOT)] = VehicleCandidate(address, name, revision)
                                f?.beginBluetoothExchange(address)
                            }
                        }
                    }
                    if (name != null) {
                        DiagLog.i(tag, "'$name' called over bluetooth")
                        wireless.begin()
                    }
                }
            }
        }
        step("Waiting for the car to call over bluetooth")
        startFinder()
        bt.start()
        wakeCarBluetooth()
        if (prefs.carP2pName.isNotBlank()) finder?.search()
        watchWifiDirect()
    }

    private fun watchWifiDirect() {
        p2pWatch?.cancel()
        startupRetry?.cancel()
        startupWatch.reset()
        peerWatch.reset()
        directStartedAt = android.os.SystemClock.elapsedRealtime()
        blockerNote = ""
        lastOtherWifi = -1
        hotspotStoppedForDirect = false
        p2pWatch = scope.launch {
            var ticks = 0
            while (mode == 1) {
                delay(2000)
                withContext(Dispatchers.Main) { checkWirelessStartup(android.os.SystemClock.elapsedRealtime()) }
                if (wifiLink?.connected == true) {
                    withContext(Dispatchers.Main) { peerWatch.reset() }
                    continue
                }
                if (ticks++ % 5 == 0) checkDirectBlockers()
                askCarAgain()
                // Only finder may approve a group as the car's transport.
                // An arbitrary p2p interface can belong to casting or another app.
                finder?.checkExisting()
                val ip = if (finder?.joined == true) NetUtil.wifiDirectIpv4(p2pIface) else null
                val up = _state.value.wifiDirect
                if (ip != null && !up) {
                    DiagLog.i(tag, "WiFi Direct is up at $ip, picking it up")
                    _state.update { it.copy(wifiDirect = true, ip = ip, joinTimedOutSeconds = 0) }
                    wireless.wifiDirectReady(ip)
                    updateBeacon()
                } else if (ip == null && up) {
                    DiagLog.i(tag, "WiFi Direct is gone")
                    _state.update { it.copy(wifiDirect = false) }
                    wireless.wifiDirectReady(null)
                    updateBeacon()
                }
            }
        }
    }

    /** Read and act on Main, which also serializes group and CMD admission callbacks. */
    internal fun wirelessStartupPoint(): CarLifeStartupWatch.Point? {
        if (mode != 1 || _state.value.waitingForShare) return null
        val f = finder ?: return null
        val l = wifiLink ?: return null
        val st = session?.state?.value
        if (st is CarLifeSession.State.Negotiated || st is CarLifeSession.State.Projecting) return null
        if (l.connected) return CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.NEGOTIATION, l.commandGeneration)
        if (f.joined) return CarLifeStartupWatch.Point(
            if (_state.value.wifiDirect) CarLifeStartupWatch.Stage.COMMAND else CarLifeStartupWatch.Stage.ADDRESS,
            f.connectionGeneration
        )
        if (!bt.open || bt.connectionGeneration != wirelessBluetoothGeneration || wireless.carWifiName != null) return null
        return CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.BLUETOOTH, wirelessBluetoothGeneration, wireless.exchange)
    }

    internal fun checkWirelessStartup(now: Long) {
        // Finder owns cleanup and its fresh-name wait. Do not create a second
        // preparation or reset the retry budget while that preparation runs.
        val f = finder ?: return
        if (f.preparingRetry || startupRetry?.isActive == true) return
        val point = wirelessStartupPoint()
        val attempt = startupWatch.next(point, now) ?: return
        val l = wifiLink ?: return
        startupRetry = scope.launch(Dispatchers.Main) { retryWirelessStartup(f, l, point!!, attempt) }
    }

    internal suspend fun retryWirelessStartup(
        f: CarFinder, l: WifiChannelLink, point: CarLifeStartupWatch.Point, attempt: Int
    ) {
        currentCoroutineContext().ensureActive()
        if (finder !== f || wifiLink !== l || f.preparingRetry || wirelessStartupPoint() != point) return
        DiagLog.w(tag, "wireless startup stalled at ${point.stage}; retry $attempt/${CarLifeStartupWatch.MAX_RETRIES}, " +
            "group=${f.connectionGeneration} command=${l.commandGeneration} bluetooth=$wirelessBluetoothGeneration exchange=${wireless.exchange}")
        when (point.stage) {
            CarLifeStartupWatch.Stage.BLUETOOTH -> withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                synchronized(wireless) {
                    job.ensureActive()
                    // A write can wait for an earlier Bluetooth operation. Check
                    // the socket, exchange and accepted reply again after waiting.
                    if (mode == 1 && finder === f && wifiLink === l && !f.joined && !l.connected &&
                        bt.open && bt.connectionGeneration == point.generation && wirelessBluetoothGeneration == point.generation &&
                        wireless.exchange == point.exchange && wireless.carWifiName == null) wireless.begin()
                }
            }
            CarLifeStartupWatch.Stage.ADDRESS -> {
                // No address is an Android/group issue, not a reason to remove
                // a valid group or restart listening sockets.
                f.checkExisting()
            }
            CarLifeStartupWatch.Stage.COMMAND -> f.retry()
            CarLifeStartupWatch.Stage.NEGOTIATION -> {
                // Only the stalled CMD incarnation is retired; listeners and
                // the approved P2P group remain available for the next call.
                if (l.retryCommand(point.generation) { wirelessStartupPoint() == point }) f.retry()
            }
        }
    }

    private fun checkDirectBlockers() {
        if (mode != 1 || wifiLink?.connected == true) return
        val waited = android.os.SystemClock.elapsedRealtime() - directStartedAt
        val hotspot = NetUtil.hotspotIpv4(this) != null
        val other = DiagSnapshot.otherWifiMhz(this)
        val called = _state.value.btCar != null || _state.value.carWifi != null
        if ((other ?: 0) != lastOtherWifi) {
            lastOtherWifi = other ?: 0
            DiagLog.i(tag, if (other != null) "WiFi + BL: the phone is connected to another Wi-Fi on ${DiagSnapshot.band(other)} (${other} MHz); many phones can only join the car's WiFi Direct on that same channel" else "WiFi + BL: the phone is not connected to any other Wi-Fi")
        }
        if (hotspot && Root.granted && !hotspotStoppedForDirect) {
            hotspotStoppedForDirect = true
            DiagLog.i(tag, "WiFi + BL: the phone's hotspot is on and blocks WiFi Direct, turning it off with root")
            Root.stopHotspot()
        }
        val busy = finder?.busyStreak ?: 0
        val note = when {
            hotspot -> "Turn off the phone's hotspot, it blocks WiFi Direct"
            busy >= 3 -> "The phone won't search for the car: turn off its hotspot and any screen casting"
            !called && waited > 25_000 -> "Open CarLife on the car's screen so the car calls the phone"
            other != null && called && waited > 30_000 -> "Disconnect the phone from other Wi-Fi so it can join the car"
            else -> ""
        }
        if (note == blockerNote) return
        blockerNote = note
        if (note.isEmpty()) return
        DiagLog.w(tag, "WiFi + BL: $note (hotspot=$hotspot, search refused=$busy, other wifi=${other?.let { DiagSnapshot.band(it) } ?: "none"}, car called=$called, waited ${waited / 1000}s)")
        step(note)
    }

    private fun canRecoverMissingPeer(f: CarFinder, name: String, generation: Long): Boolean =
        mode == 1 && finder === f && bt.open && bt.connectionGeneration == generation &&
            wirelessBluetoothGeneration == generation && _state.value.carWifi == name &&
            !_state.value.wifiDirect && wifiLink?.connected != true && !f.joined &&
            !f.preparingRetry && startupRetry?.isActive != true && !f.inSight(name) &&
            session?.projecting != true

    internal suspend fun askCarAgain(now: Long = android.os.SystemClock.elapsedRealtime()) {
        val request = withContext(Dispatchers.Main) {
            val f = finder
            val name = _state.value.carWifi
            val generation = wirelessBluetoothGeneration
            if (f == null || name == null) {
                peerWatch.reset()
                return@withContext null
            }
            val action = peerWatch.next(generation, name, canRecoverMissingPeer(f, name, generation),
                now) ?: return@withContext null
            if (action == WirelessPeerWatch.Action.RECONNECT_BLUETOOTH) {
                // Main also admits peers/groups/CMD. No working transport can
                // be admitted between this last check and retiring the socket.
                if (canRecoverMissingPeer(f, name, generation)) {
                    DiagLog.w(tag, "WiFi + BL: '$name' is still absent after four name requests; reopening the Bluetooth conversation")
                    bt.disconnect(generation)
                }
                null
            } else Triple(f, name, generation)
        } ?: return
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            synchronized(wireless) {
                job.ensureActive()
                if (canRecoverMissingPeer(request.first, request.second, request.third)) {
                    DiagLog.i(tag, "WiFi + BL: '${request.second}' is absent, asking for a fresh name over bluetooth")
                    wireless.askName()
                }
            }
        }
    }

    private fun stopLink() {
        CarAssistantPanel.close(this)
        stopAndroidAuto("the car link closed")
        mirrorClose()
        p2pWatch?.cancel()
        p2pWatch = null
        startupRetry?.cancel()
        startupRetry = null
        startupWatch.reset()
        peerWatch.reset()
        beacon.stop()
        discovery.stop()
        // Cancel pending retry preparation before closing/resetting its transport.
        stopFinder()
        bt.stop()
        bt.onCar = null
        bt.onFrame = null
        vehicleCandidates.clear()
        wireless.reset()
        p2pIface = null
        carP2pIp = null
        session?.stop()
        session = null
        sessionLink = null
        wifiCommandGroup = null
        AaHeadUnitService.onCarMicWanted = null
        AaHeadUnitService.onNavigation = null
        gearForget()
        stateJob?.cancel()
        stateJob = null
        musicJob?.cancel()
        musicJob = null
        wifiLink?.stop()
        wifiLink = null
        usbLink?.stop()
        usbLink = null
        usbAccessory = null
        hotspotClients.stop()
        busyPorts.clear()
        pendingLaunch = null
        carDisplay.stop()
        _state.update {
            it.copy(
                link = "", session = CarLifeSession.State.Idle, listening = false, aaOverlay = false,
                mirroring = false, mirrorPackage = "", btCar = null, carWifi = null, wifiDirect = false,
                beacon = false, step = "", refused = 0, waitingForShare = false, usb = false,
                blocked = 0, hotspotClients = null, joinTimedOutSeconds = 0
            )
        }
    }

    private fun startFinder() {
        if (finder != null) return
        val f = CarFinder(this, prefs, scope)
        finder = f
        f.onJoinTimeout = { seconds ->
            _state.update { it.copy(joinTimedOutSeconds = seconds).withWirelessAttemptEnded() }
        }
        f.onVehicleIdentified = { confirmWirelessVehicle() }
        f.onJoined = { l ->
            wifiCommandGroup = null
            _state.update { it.copy(joinTimedOutSeconds = 0) }
            DiagLog.i(tag, "on the car's WiFi Direct group ${l.name.ifBlank { l.groupOwnerIp }} (${l.iface ?: "?"}, car ${if (l.weAreOwner) "joined this phone" else "at " + l.groupOwnerIp})")
            step("WiFi Direct connected")
            p2pIface = l.iface
            carP2pIp = if (l.weAreOwner) null else l.groupOwnerIp
            ipJob?.cancel()
            ipJob = scope.launch {
                var ip: String? = null
                var tries = 0
                while (ip == null && tries++ < 40) {
                    ip = NetUtil.wifiDirectIpv4(l.iface)
                    if (ip == null) delay(500)
                }
                val got = ip
                if (got == null) {
                    DiagLog.w(tag, "WiFi Direct is up but this phone has no address on it yet")
                    return@launch
                }
                DiagLog.i(tag, "this phone is $got on WiFi Direct, calling the car there")
                _state.update { it.copy(wifiDirect = true, ip = got) }
                wireless.wifiDirectReady(got)
                updateBeacon()
            }
        }
        f.onRetry = { _, attempt -> step("Still joining the car's WiFi Direct, trying again (try $attempt)") }
        f.onRetryReady = { groupKept -> prepareWirelessRetry(f, groupKept) }
        f.onLeft = {
            wifiCommandGroup = null
            ipJob?.cancel()
            p2pIface = null
            carP2pIp = null
            wireless.wifiDirectReady(null)
            _state.update { it.copy(wifiDirect = false, ip = null).withWirelessAttemptEnded() }
            updateBeacon()
            step("WiFi Direct dropped, looking for the car again")
            f.search()
            scope.launch {
                if (mode == 1 && finder === f && !_state.value.wifiDirect && bt.open) {
                    wireless.begin()
                }
            }
        }
        f.start()
    }

    private fun stopFinder() {
        ipJob?.cancel()
        ipJob = null
        finder?.stop()
        finder = null
    }

    internal suspend fun prepareWirelessRetry(f: CarFinder, groupKept: Boolean): Boolean {
        val reply = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            // A Bluetooth write can hold this monitor. Never wait for it on Main,
            // and recheck cancellation after acquiring it, not only before waiting.
            synchronized(wireless) {
                job.ensureActive()
                if (mode != 1 || finder !== f || wifiLink?.connected == true) return@synchronized null
                DiagLog.i(tag, "preparing a new wireless exchange after WiFi Direct recovery; groupKept=$groupKept bluetoothOpen=${bt.open}")
                if (bt.open) {
                    step("Asking the car again over bluetooth")
                    val pending = wireless.restartAwaitingName(keepIp = groupKept)
                    Triple(pending, wirelessBluetoothGeneration, wireless.exchange)
                } else {
                    wireless.reset(keepIp = groupKept)
                    null
                }
            }
        }
        // Resume on the caller's Main dispatcher. A cancelled old worker must
        // never restart the Bluetooth listener after stopLink has closed it.
        currentCoroutineContext().ensureActive()
        if (mode != 1 || finder !== f || wifiLink?.connected == true) return false
        if (reply == null) {
            if (!groupKept) _state.update { it.withWirelessAttemptEnded() }
            step("Waiting for the car to call over bluetooth; open CarLife on the car")
            // start() is idempotent and preserves the existing RFCOMM listeners.
            bt.start()
            wakeCarBluetooth()
            return false
        }
        // An existing group does not depend on a repeated name announcement.
        if (groupKept) return true
        val ready = withTimeoutOrNull(8_000) { reply.first.await() } == true &&
            bt.connectionGeneration == reply.second && wireless.exchange == reply.third
        currentCoroutineContext().ensureActive()
        if (mode != 1 || finder !== f || wifiLink?.connected == true) return false
        if (!ready) step("Waiting for the car's WiFi Direct name over bluetooth")
        return ready
    }

    private fun retry() {
        if (mode != 1 || wifiLink?.connected == true) return
        DiagLog.i(tag, "manual connection retry requested")
        _state.update { it.copy(joinTimedOutSeconds = 0) }
        startFinder()
        finder?.retry()
        updateBeacon()
    }

    private fun onWifiDirectSide(iface: String): Boolean =
        iface.startsWith("p2p") || iface == p2pIface

    private fun carInterface(iface: String): Boolean =
        finder?.joined == true && !p2pIface.isNullOrBlank() && iface == p2pIface

    private fun beaconTargets(): Collection<java.net.InetAddress> {
        if (mode != 1) {
            val out = LinkedHashSet<java.net.InetAddress>(NetUtil.broadcastAddresses { !onWifiDirectSide(it) })
            _state.value.hotspotClients?.forEach { ip -> runCatching { java.net.InetAddress.getByName(ip) }.getOrNull()?.let(out::add) }
            return out
        }
        if (finder?.joined != true) return emptyList()
        val out = LinkedHashSet<java.net.InetAddress>(NetUtil.broadcastAddresses { carInterface(it) })
        carP2pIp?.let { ip -> runCatching { java.net.InetAddress.getByName(ip) }.getOrNull()?.let(out::add) }
        return out
    }

    private fun carGroupOwner(): java.net.InetAddress? =
        if (mode == 1) carP2pIp?.let { ip -> runCatching { java.net.InetAddress.getByName(ip) }.getOrNull() } else null

    @SuppressLint("MissingPermission")
    private fun helloForCar(): String = runCatching {
        val bt = runCatching { (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name }.getOrNull().orEmpty()
        val info = org.json.JSONObject().put("bluetoothName", bt).put("wifiP2pName", finder?.myName.orEmpty())
        org.json.JSONObject().put("carlifehost", "carlife").put("deviceInfo", info).toString()
    }.getOrDefault("")

    private fun sameWay(from: java.net.InetAddress): Boolean {
        val iface = NetUtil.interfaceFor(from) ?: return false
        if (iface == "lo") return true
        return if (mode == 1) carInterface(iface) else !onWifiDirectSide(iface)
    }

    private fun wrongWay(socket: java.net.Socket): String? {
        val local = socket.localAddress ?: return null
        if (local.isLoopbackAddress) return null
        val iface = runCatching { java.net.NetworkInterface.getByInetAddress(local)?.name }.getOrNull().orEmpty()
        val side = onWifiDirectSide(iface)
        val why = if (mode == 1) {
            if (carInterface(iface)) null else "The connection is outside the car's confirmed WiFi Direct group"
        } else {
            if (!side) null else "The car tried WiFi Direct, but FT is set to Hotspot"
        }
        val shown = if (mode == 1) R.string.refused_hotspot else R.string.refused_direct
        if (why != null && _state.value.refused != shown) {
            DiagLog.w(tag, "$why ($iface), refused")
            _state.update { it.copy(refused = shown) }
        }
        return why
    }

    private fun updateBeacon() {
        val busy = wifiLink?.connected == true
        val ready = when (mode) {
            0 -> true
            1 -> _state.value.wifiDirect
            else -> false
        }
        val want = wifiLink != null && !busy && ready
        if (want) beacon.start() else beacon.stop()
        _state.update { it.copy(beacon = want) }
    }

    private fun confirmWirelessVehicle() {
        scope.launch(Dispatchers.Main.immediate) { confirmWirelessVehicleOnMain() }
    }

    private fun confirmWirelessVehicleOnMain() {
        if (mode != 1 || wifiLink == null || sessionLink !== wifiLink ||
            session?.state?.value !is CarLifeSession.State.Projecting) return
        val found = finder ?: return
        val commandGroup = wifiCommandGroup ?: return
        if (!found.joined || commandGroup.first !== found ||
            commandGroup.second != found.connectionGeneration) return
        // Group identity may arrive after projection; only its own accepted BT
        // exchange may confirm it, never a later unrelated caller.
        val candidate = vehicleCandidates[found.connectedBluetoothAddress.uppercase(java.util.Locale.ROOT)] ?: return
        val prefs = this.prefs
        if (candidate.address.equals(prefs.carLifeVehicleAddress, true) &&
            found.connectedPeerAddress.equals(prefs.carLifeWifiAddress, true)) return
        if (prefs.confirmCarLifeVehicle(candidate.address, candidate.name, found.connectedPeerAddress, candidate.revision)) {
            DiagLog.i(tag, "CarLife vehicle confirmed: '${candidate.name}' (${candidate.address})")
        }
    }

    private fun attachSession(l: CarLifeLink) {
        session?.stop()
        val s = CarLifeSession(this, l, prefs, scope)
        s.fullRateStart = mode != 1
        session = s
        sessionLink = l
        songInfoAt = 0L
        s.allowSongInfo(!songInfoSuspended)
        s.onVideoConfig = { plan -> onVideoConfig(plan) }
        s.onFrameRate = { fps -> carDisplay.setFrameRate(VideoPlans.withinCap(fps, thermalCap)) }
        s.onNeedKeyframe = { carDisplay.requestKeyframe() }
        s.onVideoBack = { carDisplay.wake() }
        s.onGear = { g -> onGear(g) }
        s.onStopVideo = { onStopVideo() }
        s.onTouch = { t -> routeTouch(t) }
        s.onHardKey = { k -> onHardKey(k) }
        s.onVoiceAudio = { pcm -> AaHeadUnitService.current?.feedCarMicrophone(pcm) }
        AaHeadUnitService.onCarMicWanted = { on -> runCatching { s.carMicrophone(on) } }
        AaHeadUnitService.onNavigation = { info -> s.sendNavigation(info) }
        s.onPauseMedia = { carMusic(false) }
        s.onMusicControl = { play -> carMusic(play) }
        s.onSongInfo = { songInfoAt = android.os.SystemClock.elapsedRealtime() }
        s.onClosed = { reason ->
            if (l === wifiLink) {
                wifiCommandGroup = null
                if (mode == 1) _state.update { it.withWirelessAttemptEnded() }
            }
            DiagLog.i(tag, "link closed: $reason")
            val sinceSong = android.os.SystemClock.elapsedRealtime() - songInfoAt
            if (songInfoAt > 0 && sinceSong < 3000 && prefs.carSongInfo) {
                songInfoSuspended = true
                s.allowSongInfo(false)
                DiagLog.w(tag, "the car dropped the connection ${sinceSong}ms after song info; metadata paused until this service stops or the user changes the setting; the saved preference is unchanged")
            }
            songInfoAt = 0
            carPaused = false
            gearForget()
            stopAndroidAuto("the car disconnected")
            mediaReady = false
            routePlayer()
            stopSoundWatch()
            soundDeclined = false
            blockedSoundNoticed = false
            updateBeacon()
            if (l === usbLink) scope.launch(Dispatchers.Main) { if (l === usbLink) usbGone(reason) }
        }
        musicJob?.cancel()
        musicJob = scope.launch { AaHeadUnitService.music.collect { s.sendAaMusic(it) } }
        stateJob?.cancel()
        stateJob = scope.launch {
            s.state.collect { st ->
                _state.update { it.withSessionState(st, l.name) }
                updateBeacon()
                if (st is CarLifeSession.State.Projecting && !mediaReady) {
                    mediaReady = true
                    confirmWirelessVehicle()
                    routePlayer()
                    startSoundWatch()
                    scope.launch {
                        delay(3000)
                        if (mediaReady) CarPlayer.state.value.track?.let { t -> if (!t.video) tellCarTheSong(t) }
                    }
                } else if (st is CarLifeSession.State.Idle && mediaReady) {
                    mediaReady = false
                    routePlayer()
                    stopSoundWatch()
                }
                if (st is CarLifeSession.State.Projecting && prefs.aaAutoStart && !aaAutoLaunched) {
                    aaAutoLaunched = true
                    DiagLog.i(tag, "auto-starting Android Auto after connection")
                    startAndroidAuto()
                }
                if (st is CarLifeSession.State.Projecting && !autoOpened) {
                    autoOpened = true
                    openOnConnect()
                    if (prefs.supercastOn && !SuperCast.ready.value) {
                        val request = SuperCast.prepareStart(prefs = prefs)
                        try {
                            scope.launch(Dispatchers.IO) {
                                DiagLog.i(tag, "starting the apps-on-the-car helper after connection")
                                request.start(applicationContext)
                            }.invokeOnCompletion { request.close() }
                        } catch (error: Throwable) {
                            request.close()
                            throw error
                        }
                    }
                }
            }
        }
        s.start()
    }

    private fun onAudioMode(m: Int) {
        val calling = m == android.media.AudioManager.MODE_RINGTONE ||
            m == android.media.AudioManager.MODE_IN_CALL ||
            m == android.media.AudioManager.MODE_IN_COMMUNICATION
        if (calling == CarAudioBus.inCall) return
        if (calling) {
            callRecovery = null
            callRecoveryJob?.cancel()
            resumePhoneAudio = if (audio.active) projection?.let { it to audio.startedAt } else null
        }
        CarAudioBus.inCall = calling
        DiagLog.i(tag, if (calling) "phone call started, the car's voice channel stays closed until it ends" else "phone call ended")
        val resume = resumePhoneAudio
        if (!calling && resume != null) {
            resumePhoneAudio = null
            val ticket = Any()
            callRecovery = ticket
            recoverCallAudio(resume, ticket, 0)
        }
    }

    private fun recoverCallAudio(resume: Pair<MediaProjection, Long>, ticket: Any, attempt: Int) {
        soundLater {
            if (callRecovery !== ticket || session?.projecting != true || CarAudioBus.inCall ||
                projection !== resume.first || audio.startedAt != resume.second) return@soundLater
            if (attempt == 0) audio.stop() else if (audio.active) return@soundLater
            val result = startAudioToCar()
            if (result != AudioStart.SKIPPED && attempt < 3) {
                val next = resume.first to audio.startedAt
                callRecoveryJob = scope.launch {
                    delay(750)
                    recoverCallAudio(next, ticket, attempt + 1)
                }
            }
        }
    }

    private fun watchCalls() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        onAudioMode(am.mode)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = android.media.AudioManager.OnModeChangedListener { m -> onAudioMode(m) }
            runCatching { am.addOnModeChangedListener(mainExecutor, l) }
                .onSuccess { callWatch = l }
                .onFailure { DiagLog.w(tag, "could not watch the call audio mode, polling instead: ${it.message}") }
        }
        if (callWatch == null) {
            callPoll = scope.launch {
                while (true) {
                    delay(1000)
                    onAudioMode(am.mode)
                }
            }
        }
    }

    private fun stopWatchingCalls() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= 31) {
            (callWatch as? android.media.AudioManager.OnModeChangedListener)?.let { runCatching { am.removeOnModeChangedListener(it) } }
        }
        callWatch = null
        callPoll?.cancel()
        callPoll = null
        resumePhoneAudio = null
        callRecovery = null
        callRecoveryJob?.cancel()
        callRecoveryJob = null
        CarAudioBus.inCall = false
    }

    private fun onVideoConfig(plan: VideoPlan) {
        val s = session ?: return
        carDisplay.start(
            plan,
            onConfig = { cfg -> s.videoConfig(cfg) },
            onFrame = { frame, key -> s.videoFrame(frame, key) }
        ) {
            CarTheme { CarScreen() }
        }
        if (thermalCap > 0) carDisplay.setFrameRate(VideoPlans.withinCap(plan.fps, thermalCap))
        val w = plan.streamWidth
        val h = plan.streamHeight
        updateNotification({ Lang.str(R.string.note_projecting, w, h) })
        soundToCar()
    }

    private fun carMusic(play: Boolean) {
        if (!play) {
            if (CarPlayer.playing) {
                CarPlayer.pause()
                carPaused = true
                DiagLog.i(tag, "FT's music paused for the car")
            }
            return
        }
        if (!carPaused) return
        carPaused = false
        if (CarPlayer.hasTrack && !CarPlayer.playing) {
            CarPlayer.resume()
            DiagLog.i(tag, "FT's music playing again for the car")
        }
    }

    private fun onStopVideo() {
        mirrorClose()
        CarPlayer.stopVideo()
        carDisplay.stop()
        aaAutoLaunched = false
        autoOpened = false
        _state.update { it.copy(aaOverlay = false) }
        updateNotification(waitingNote())
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
        bt.onFrame = if (on) { generation, frame -> bt.send(frame, generation) } else null
        DiagLog.i(tag, "bluetooth echo ${if (on) "on" else "off"}")
    }

    @SuppressLint("MissingPermission")
    private fun triggerAaWireless() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val locked = prefs.carLifeVehicleAddress
        val wantAddress = locked.ifBlank { prefs.carBtAddress }.trim()
        val want = prefs.carBtName.trim()
        val byAddress = bonded.firstOrNull { d -> wantAddress.isNotEmpty() && d.address.equals(wantAddress, true) }
        val byName = bonded.firstOrNull { d -> locked.isBlank() && want.isNotEmpty() && runCatching { d.name }.getOrNull()?.contains(want, true) == true }
        val byClass = bonded.filter { d -> locked.isBlank() && runCatching { d.bluetoothClass?.deviceClass }.getOrNull() == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO }
        val device = byAddress ?: byName ?: byClass.singleOrNull()
        DiagLog.i(tag, "car for Android Auto over bluetooth: " + when {
            byAddress != null -> "the car that called FT (${byAddress.address})"
            locked.isNotBlank() -> "none, the confirmed car is not paired"
            byName != null -> "the paired device named like '$want'"
            byClass.size == 1 -> "the only paired device that says it is a car"
            byClass.size > 1 -> "none, ${byClass.size} paired devices say they are cars and FT does not know which one"
            else -> "none, no paired device says it is a car and FT has not heard from one yet"
        } + if (prefs.showOtherDevicesInLogs) " (paired: ${bonded.joinToString { d -> "'${runCatching { d.name }.getOrNull() ?: "?"}' class ${runCatching { Integer.toHexString(d.bluetoothClass?.deviceClass ?: 0) }.getOrNull()}" }})" else "")
        if (device == null) {
            DiagLog.w(tag, "no bonded bluetooth device to hand Android Auto")
            return
        }
        val name = runCatching { device.name }.getOrNull() ?: device.address
        DiagLog.d(tag, "car bluetooth present as '$name'")
        askAndroidAutoToConnect()
    }

    private fun askAndroidAutoToConnect() {
        val port = prefs.aaPort
        val i = Intent("com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START")
            .setComponent(
                android.content.ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
                )
            )
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("ip_address", "127.0.0.1")
            .putExtra("projection_port", port)
        runCatching { sendBroadcast(i) }
            .onSuccess { DiagLog.i(tag, "asked Android Auto to project onto FT at 127.0.0.1:$port") }
            .onFailure { DiagLog.w(tag, "Android Auto would not take the request: ${it.message}") }
    }

    private fun matchCarScreen() {
        if (!prefs.aaMatchCar) return
        val s = _state.value.session
        val size = when (s) {
            is CarLifeSession.State.Projecting -> Triple(s.width, s.height, s.fps)
            is CarLifeSession.State.Negotiated -> Triple(s.width, s.height, s.fps)
            else -> null
        } ?: return
        if (size.first <= 0 || size.second <= 0) return
        if (prefs.aaWidth == size.first && prefs.aaHeight == size.second) return
        prefs.aaWidth = size.first
        prefs.aaHeight = size.second
        if (size.third > 0) prefs.aaFps = size.third
        DiagLog.i(tag, "Android Auto will draw at the car's own ${size.first}x${size.second}")
    }

    private fun startAndroidAuto() {
        val pkg = prefs.aaPackage
        if (runCatching { packageManager.getPackageInfo(pkg, 0) }.isFailure) {
            DiagLog.w(tag, "Android Auto ($pkg) is not installed on this phone")
            return
        }
        mirrorStop()
        matchCarScreen()
        if (AaHeadUnitService.current == null && !AaHeadUnitService.state.value.listening) {
            AaHeadUnitService.start(this, prefs.aaBluetooth)
        }
        DiagLog.i(tag, "bridging this phone's Android Auto onto the car, waiting for it to start projecting")
        aaStartJob?.cancel()
        val reused = AaHeadUnitService.requestVideoFocus(true, "CarLife start")
        aaStartJob = if (reused) null else scope.launch {
            if (!AaInstaller.serverRunning(prefs.aaSelfPort)) {
                DiagLog.i(tag, if (Root.granted) "starting Android Auto's head unit server with root" else "starting Android Auto's head unit server through accessibility")
                runInterruptible(Dispatchers.IO) { AaKeep.switchOn(this@CarLifeService) }
            }
            delay(1500)
            askAndroidAutoToConnect()
        }
        aaWatch?.cancel()
        aaWatch = scope.launch {
            var shown = _state.value.aaOverlay
            resumedMedia = reused
            AaHeadUnitService.state.collect { s ->
                if (s.phase == AaSession.Phase.STREAMING && !resumedMedia) {
                    resumedMedia = true
                    val session = AaHeadUnitService.current
                    scope.launch {
                        delay(2500)
                        if (session != null && AaHeadUnitService.current === session &&
                            session.videoFocus.value == AaVideoFocus.PROJECTED) {
                            DiagLog.i(tag, "asking Android Auto to pick up where it left off")
                            session.sendKey(126)
                        }
                    }
                }
                if (s.connected) {
                    aaReturn?.cancel()
                    aaReturn = null
                    if (s.showProjection && !_state.value.aaOverlay) {
                        DiagLog.i(tag, "Android Auto is projecting, showing it on the car")
                        shown = true
                        aaOverlay(true)
                    } else if (!s.showProjection && _state.value.aaOverlay) {
                        DiagLog.i(tag, "Android Auto yielded video focus, returning to FT without disconnecting")
                        aaOverlay(false)
                    }
                } else if (s.videoFocus == AaVideoFocus.NATIVE) {
                    aaReturn?.cancel()
                    aaReturn = null
                    if (_state.value.aaOverlay) aaOverlay(false)
                } else if (shown && aaReturn?.isActive != true) {
                    aaReturn = scope.launch {
                        DiagLog.i(tag, "Android Auto went quiet, waiting for it to come back")
                        step("Android Auto went quiet, waiting for it")
                        var wait = 1200L
                        repeat(6) {
                            delay(wait)
                            if (AaHeadUnitService.state.value.connected) return@launch
                            askAndroidAutoToConnect()
                            wait = (wait * 2).coerceAtMost(8000L)
                        }
                        if (!AaHeadUnitService.state.value.connected) {
                            shown = false
                            stopAndroidAuto("Android Auto did not come back")
                        }
                    }
                }
            }
        }
    }

    private val aaGestureViewport = AaTouchGestureViewport()

    private fun routeTouch(raw: CarTouch) {
        val st = _state.value
        val (x, y) = carDisplay.toContent(raw.x, raw.y)
        val second = if (raw.x2 >= 0 && raw.y2 >= 0) carDisplay.toContent(raw.x2, raw.y2) else -1 to -1
        val t = raw.copy(x = x, y = y, x2 = second.first, y2 = second.second)
        val assistantTouch = CarAssistantPanel.routesTouch(this, t.action)
        if (t.action == CarTouch.DOWN) {
            touchFt = assistantTouch || CarTouchZones.hit(x, y)
            DiagLog.i(tag, "car touched ${raw.x},${raw.y} of its picture, ${x},${y} on FT's screen")
        }
        if (touchFt) {
            aaGestureViewport.reset()
            carDisplay.dispatchTouch(t)
            return
        }
        val action = when (t.action) {
            CarTouch.DOWN, CarTouch.UP, CarTouch.MOVE -> t.action
            else -> -1
        }
        val aa = AaHeadUnitService.current
        if (st.aaOverlay && aa != null && aa.phase.value >= AaSession.Phase.DISCOVERED) {
            if (action < 0) {
                aaGestureViewport.reset()
                return
            }
            val p = prefs
            val display = aa.displayConfig
            val point = aaGestureViewport.map(
                action, x, y, AaTouchViewport.bounds,
                display?.width ?: p.aaWidth, display?.height ?: p.aaHeight
            ) ?: return
            aa.sendTouch(point.first, point.second, action)
            return
        }
        // The mirror/FT route may resume without a new AA DOWN.
        aaGestureViewport.reset()
        if (st.mirroring) {
            val svc = FTTouchService.instance
            if (svc != null && mirror.active) {
                if (action < 0) return
                if (mirror.ownDisplay) {
                    svc.inject(action, x.toFloat().coerceIn(0f, mirror.width - 1f), y.toFloat().coerceIn(0f, mirror.height - 1f), mirror.displayId)
                } else {
                    val w = carDisplay.width.coerceAtLeast(1).toFloat()
                    val h = carDisplay.height.coerceAtLeast(1).toFloat()
                    val scale = minOf(w / mirror.width.coerceAtLeast(1), h / mirror.height.coerceAtLeast(1))
                    val offX = (w - mirror.width * scale) / 2f
                    val offY = (h - mirror.height * scale) / 2f
                    if (action == CarTouch.DOWN) {
                        touchBeside = x < offX - BESIDE_SLOP || x > w - offX + BESIDE_SLOP || y < offY - BESIDE_SLOP || y > h - offY + BESIDE_SLOP
                        if (touchBeside) DiagLog.i(tag, "car touched beside the phone picture, the phone is left alone")
                    }
                    if (touchBeside) return
                    svc.inject(action, ((x - offX) / scale).coerceIn(0f, mirror.width - 1f), ((y - offY) / scale).coerceIn(0f, mirror.height - 1f))
                }
                return
            }
        }
        carDisplay.dispatchTouch(t)
    }

    @SuppressLint("MissingPermission")
    private fun carBtDevice(): android.bluetooth.BluetoothDevice? {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter ?: return null
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty()
        val locked = prefs.carLifeVehicleAddress.orEmpty()
        val want = locked.ifBlank { prefs.carBtAddress }.trim()
        if (want.isNotEmpty()) bonded.firstOrNull { it.address.equals(want, true) }?.let { return it }
        if (locked.isNotBlank()) return null
        val name = prefs.carBtName.trim()
        if (name.isNotEmpty()) {
            bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains(name, true) == true }?.let { return it }
        }
        return null
    }

    private fun wakeCarBluetooth() {
        scope.launch {
            btAudio.open()
            delay(1500)
            val car = btAudio.theCar(prefs.carBtName, prefs.carLifeVehicleAddress.orEmpty().ifBlank { prefs.carBtAddress }, prefs.carLifeVehicleAddress.orEmpty().isNotBlank()) ?: carBtDevice()
            if (car == null) {
                DiagLog.i(tag, "FT does not know which paired device is the car, so it cannot wake it over bluetooth")
                return@launch
            }
            val who = try {
                car.name ?: car.address
            } catch (_: SecurityException) {
                DiagLog.w(tag, "bluetooth permission was denied; cannot wake the car")
                return@launch
            }
            if (btAudio.somethingIsPlaying() != null) {
                DiagLog.i(tag, "'$who' is already connected over bluetooth")
                return@launch
            }
            step("Connecting to '$who' over bluetooth")
            if (btAudio.askCarToPlay(car)) DiagLog.i(tag, "asked '$who' to connect over bluetooth")
        }
    }

    private fun onCarRaisedWifiDirect(name: String) {
        if (mode != 1) return
        prefs.carP2pName = name
        _state.update { it.copy(carWifi = name) }
        if (wifiLink?.connected == true) {
            // Refresh identity without reconnecting or regressing the projection status.
            finder?.connectByName(name)
            DiagLog.i(tag, "already connected to the car, leaving WiFi Direct alone")
            return
        }
        step("Joining the car's WiFi Direct '$name'")
        startFinder()
        finder?.search()
        finder?.connectByName(name)
    }

    private fun keyName(code: Int) = when (code) {
        87 -> "next track"
        88 -> "previous track"
        85 -> "play or pause"
        126 -> "play"
        127 -> "pause"
        84 -> "voice"
        3 -> "home"
        4 -> "back"
        else -> "key $code"
    }

    private fun onHardKey(key: Int) {
        val swap = prefs.swapTrackKeys
        val mapped = when (key) {
            15 -> if (swap) 87 else 88
            16 -> if (swap) 88 else 87
            14 -> 85
            231, 219 -> 84
            79 -> 85
            else -> key
        }
        val extra = when (mapped) {
            87 -> prefs.wheelLongNext
            88 -> prefs.wheelLongPrev
            else -> WheelAction.NONE
        }
        if (extra == WheelAction.NONE) {
            handleHardKey(key, mapped)
            return
        }
        val pending = wheelPending
        if (pending != null && mapped == wheelKey) {
            pending.cancel()
            wheelPending = null
            wheelKey = -1
            DiagLog.i(tag, "steering wheel ${keyName(mapped)} pressed again after ${android.os.SystemClock.uptimeMillis() - wheelAt} ms, doing the second action instead")
            runWheelAction(extra)
            return
        }
        wheelPending?.cancel()
        wheelKey = mapped
        wheelAt = android.os.SystemClock.uptimeMillis()
        wheelPending = scope.launch {
            delay(WHEEL_AGAIN_MS)
            wheelPending = null
            wheelKey = -1
            handleHardKey(key, mapped)
        }
    }

    private fun openOnConnect() {
        val wanted = prefs.openOnConnect.trim()
        if (wanted.isEmpty()) return
        if (prefs.aaAutoStart && wanted == "aa") return
        scope.launch {
            delay(1500)
            DiagLog.i(tag, "opening $wanted because the car connected")
            when {
                wanted == "aa" -> startAndroidAuto()
                wanted == "maps" -> launch(prefs.mapsPackage)
                wanted == "phone" -> phoneScreen()
                wanted.startsWith("app:") -> launch(wanted.removePrefix("app:"))
                else -> _openView.tryEmit(wanted)
            }
        }
    }

    @Volatile private var gearHidProjection = false
    private val gearSound = GearSound()
    private var gearSettle: Job? = null
    private var gearHeld: Job? = null
    private var gearLook: Job? = null
    private var gearVolumeHold: Job? = null
    @Volatile private var gearShown = -1
    private var gearPausedFt = false
    private var gearVolumeBefore = -1
    private var gearVolumeSet = -1

    private fun onGear(gear: Int) {
        if (gear !in 1..5) DiagLog.w(tag, "the car sent gear $gear, which is not a gear FT knows, so it counts only if it lasts")
        gearHeld?.cancel()
        gearHeld = null
        val stopped = gearShown == GearAction.GEAR_REVERSE || gearShown == GearAction.GEAR_PARK
        val stopping = gear == GearAction.GEAR_REVERSE || gear == GearAction.GEAR_PARK
        if (stopped && !stopping) {
            gearHeld = scope.launch {
                delay(GEAR_HOLD_MS)
                applyGear(gear)
            }
            return
        }
        applyGear(gear)
    }

    private fun applyGear(gear: Int) {
        if (gear == gearShown) return
        gearShown = gear
        val p = prefs
        val action = when (gear) {
            GearAction.GEAR_REVERSE -> p.gearReverse
            GearAction.GEAR_PARK -> p.gearPark
            else -> GearAction.NONE
        }
        when (gear) {
            GearAction.GEAR_REVERSE -> gearSoundFor(gear, p.gearReverseSound, p.gearReverseLevel)
            GearAction.GEAR_PARK -> gearSoundFor(gear, p.gearParkSound, p.gearParkLevel)
            else -> gearSoundFor(gear, GearAction.SOUND_KEEP, 100)
        }
        if (action == GearAction.NONE) {
            if (gearHidProjection) {
                gearHidProjection = false
                DiagLog.i(tag, "car left the gear, bringing FT back to the car")
                session?.requestForeground()
            }
            return
        }
        DiagLog.i(tag, "car shifted to ${gearName(gear)}, running the chosen action")
        if (GearAction.restores(action)) gearHidProjection = true
        runGearAction(action)
    }

    private fun gearSoundFor(gear: Int, sound: Int, lowerTo: Int) = synchronized(gearSound) {
        gearSettle?.cancel()
        gearSettle = null
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val ftPlaying = CarPlayer.playing
        val phonePlaying = !ftPlaying && am.isMusicActive
        val before = gearSound.level
        val move = gearSound.shift(sound, lowerTo, ftPlaying || phonePlaying)
        if (move.level != before) {
            CarAudioBus.soundLevel = move.level
            gearPhoneVolume(am, move.level)
            if (move.level < 1f) DiagLog.i(tag, "car in ${gearName(gear)}, sound to the car down to ${(move.level * 100).toInt()}%")
            else DiagLog.i(tag, "car in ${gearName(gear)}, sound to the car back to normal")
        }
        gearLook?.cancel()
        gearLook = null
        if (sound != GearAction.SOUND_KEEP) {
            gearLook = scope.launch {
                delay(GEAR_LOOK_MS)
                DiagLog.i(tag, "car in ${gearName(gear)} for a moment: ${gearSoundReport(am)}")
            }
        }
        if (move.pause) {
            gearPausedFt = ftPlaying
            if (ftPlaying) CarPlayer.pause() else phoneMediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            DiagLog.i(tag, "car in ${gearName(gear)}, paused ${if (ftPlaying) "FT's player" else "the app playing on the phone"}")
            if (move.level < 1f) {
                gearSettle = scope.launch {
                    delay(GEAR_SETTLE_MS)
                    synchronized(gearSound) {
                        if (gearSound.settle()) {
                            CarAudioBus.soundLevel = 1f
                            gearPhoneVolume(am, 1f)
                            DiagLog.i(tag, "car in ${gearName(gear)}, paused, sound to the car back to normal")
                        }
                    }
                }
            }
        }
        if (move.resume) {
            if (gearPausedFt) {
                if (CarPlayer.hasTrack && !CarPlayer.playing) CarPlayer.resume()
            } else {
                phoneMediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            DiagLog.i(tag, "car in ${gearName(gear)}, playing again what the gear paused")
        }
    }

    private fun gearSoundReport(am: android.media.AudioManager): String {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val volume = runCatching { "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}" }.getOrDefault("?")
        val bluetooth = runCatching {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { device ->
                val known = prefs.carLifeVehicleAddress.ifBlank { prefs.carBtAddress }
                if (prefs.showOtherDevicesInLogs || known.isNotBlank() && device.address.equals(known, true))
                    device.productName.toString() else "Bluetooth audio"
            }
        }.getOrNull()
        val route = if (CarPlayer.toCar || !prefs.soundOverBluetooth) "the CarLife sound channel" else "bluetooth"
        return "${CarAudioBus.describe()}, sound goes over $route, phone media volume $volume" +
            (if (savedVolume >= 0) " (phone speaker silenced by FT)" else "") +
            (if (bluetooth != null) ", bluetooth sound connected to '$bluetooth'${if (am.isMusicActive) " and playing" else ""}" else ", no bluetooth sound connected")
    }

    private fun gearPhoneVolume(am: android.media.AudioManager, level: Float) {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val now = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
        if (now < 0) return
        if (gearVolumeSet >= 0 && now != gearVolumeSet) {
            gearVolumeBefore = -1
            gearVolumeSet = -1
        }
        val hearsPhone = savedVolume < 0 && prefs.soundOverBluetooth
        val target = if (level < 1f && hearsPhone) (am.getStreamMaxVolume(stream) * level).toInt() else Int.MAX_VALUE
        val base = if (gearVolumeSet >= 0) gearVolumeBefore else now
        if (target < base) {
            if (gearVolumeSet < 0) gearVolumeBefore = now
            runCatching { am.setStreamVolume(stream, target, 0) }
            gearVolumeSet = target
            holdPhoneVolume(am, target)
        } else if (gearVolumeSet >= 0) {
            gearVolumeHold?.cancel()
            gearVolumeHold = null
            runCatching { am.setStreamVolume(stream, gearVolumeBefore, 0) }
            gearVolumeBefore = -1
            gearVolumeSet = -1
        }
    }

    private fun holdPhoneVolume(am: android.media.AudioManager, target: Int) {
        gearVolumeHold?.cancel()
        gearVolumeHold = scope.launch {
            val stream = android.media.AudioManager.STREAM_MUSIC
            val until = android.os.SystemClock.elapsedRealtime() + GEAR_VOLUME_HOLD_MS
            var said = false
            while (android.os.SystemClock.elapsedRealtime() < until) {
                delay(250)
                synchronized(gearSound) {
                    if (gearVolumeSet != target) return@launch
                    val now = runCatching { am.getStreamVolume(stream) }.getOrDefault(target)
                    if (now > target) {
                        runCatching { am.setStreamVolume(stream, target, 0) }
                        if (!said) DiagLog.i(tag, "the car turned the phone's volume back up to $now, FT turned it down to $target again")
                        said = true
                    }
                }
            }
        }
    }

    private fun phoneMediaKey(am: android.media.AudioManager, key: Int): Boolean =
        runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        }.onFailure { DiagLog.w(tag, "could not pass ${keyName(key)} to the phone: ${it.message}") }.isSuccess

    private fun gearForget() = synchronized(gearSound) {
        gearSettle?.cancel()
        gearSettle = null
        gearHeld?.cancel()
        gearHeld = null
        gearLook?.cancel()
        gearLook = null
        gearVolumeHold?.cancel()
        gearVolumeHold = null
        gearShown = -1
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (gearSound.level < 1f) {
            CarAudioBus.soundLevel = 1f
            gearPhoneVolume(am, 1f)
        }
        gearSound.forget()
        gearHidProjection = false
    }

    private fun gearName(gear: Int): String = when (gear) {
        1 -> "neutral"; 2 -> "park"; 3 -> "drive"; 4 -> "low"; 5 -> "reverse"; else -> "gear $gear"
    }

    private fun runGearAction(action: Int) {
        when (action) {
            GearAction.CAR_SCREEN -> carHome()
            GearAction.FT_HOME -> _screenKeys.tryEmit(3)
            GearAction.MAPS -> launch(prefs.mapsPackage)
            GearAction.ANDROID_AUTO -> startAndroidAuto()
        }
    }

    private fun runWheelAction(action: Int) {
        when (action) {
            WheelAction.ANDROID_AUTO -> startAndroidAuto()
            WheelAction.CAR_HOME -> _screenKeys.tryEmit(3)
            WheelAction.MAPS -> launch(prefs.mapsPackage)
            WheelAction.PHONE_SCREEN -> phoneScreen()
            WheelAction.PLAY_PAUSE -> mediaKey(85)
            WheelAction.VOICE -> voiceAssistant.press()
        }
    }

    private fun handleHardKey(key: Int, mapped: Int) {
        if ((mapped == 3 || mapped == 4) && CarAssistantPanel.close(this)) return
        if (mapped == 84) {
            voiceAssistant.press()
            return
        }
        val st = _state.value
        when {
            st.aaOverlay -> {
                val aa = AaHeadUnitService.current
                when {
                    aa == null -> Unit
                    mapped in AA_KEYS -> {
                        DiagLog.i(tag, "steering wheel key $key sent to Android Auto as ${keyName(mapped)}")
                        aa.sendKey(mapped)
                        if (mapped == 87 || mapped == 88) catchUp(keyName(mapped))
                    }
                    else -> DiagLog.w(tag, "steering wheel key $key has no Android Auto action yet")
                }
            }
            st.mirroring -> when (mapped) {
                4 -> FTTouchService.instance?.back()
                3 -> mirrorStop()
                in MEDIA_KEYS -> mediaKey(mapped)
                else -> DiagLog.i(tag, "steering wheel key $key ignored while mirroring")
            }
            else -> when (mapped) {
                in MEDIA_KEYS -> mediaKey(mapped)
                3, 4 -> _screenKeys.tryEmit(mapped)
                else -> DiagLog.i(tag, "steering wheel key $key has nothing to do on FT's screen")
            }
        }
    }

    private fun mediaKey(key: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (CarPlayer.playing || (CarPlayer.hasTrack && !am.isMusicActive)) {
            when (key) {
                85 -> CarPlayer.toggle()
                126 -> CarPlayer.resume()
                127 -> CarPlayer.pause()
                87 -> CarPlayer.next()
                88 -> CarPlayer.previous()
            }
            DiagLog.i(tag, "steering wheel ${keyName(key)} for FT's player")
            return
        }
        if (phoneMediaKey(am, key)) DiagLog.i(tag, "steering wheel ${keyName(key)} sent to the app playing on the phone")
    }

    private fun aaOverlay(on: Boolean) {
        if (_state.value.aaOverlay != on) aaGestureViewport.reset()
        if (on) mirrorStop()
        _state.update { it.copy(aaOverlay = on) }
        DiagLog.i(tag, "android auto overlay ${if (on) "on" else "off"}")
    }

    private fun launch(pkg: String) {
        val ticket = launchTicket.incrementAndGet()
        soundLater {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                DiagLog.w(tag, "no launcher for $pkg")
                return@soundLater
            }
            launchIntent(intent, pkg, ticket = ticket)
        }
    }

    private fun carHome() {
        val s = session
        if (s == null || !mediaReady) {
            DiagLog.i(tag, "no car connected to send to its own screen")
            return
        }
        scope.launch { s.goToCarHome() }
    }

    private fun phoneScreen() {
        val ticket = launchTicket.incrementAndGet()
        soundLater {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val pkg = runCatching { packageManager.resolveActivity(home, 0)?.activityInfo?.packageName }.getOrNull() ?: "android"
            launchIntent(home, pkg, phone = true, ticket = ticket)
        }
    }

    private fun launchIntent(intent: Intent, pkg: String, phone: Boolean = intent.hasCategory(Intent.CATEGORY_HOME), ticket: Int = launchTicket.get()) {
        if (ticket != launchTicket.get()) {
            DiagLog.i(tag, "$pkg was not opened because something else was chosen on the car first")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!mirror.ready && projection == null && (app.mirrorResultCode == 0 || app.mirrorData == null)) {
            pendingLaunch = intent to pkg
            if (canShareQuietly()) {
                _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
                shareQuietly()
                return
            }
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg, waitingForShare = true, shareForSound = false) }
            askForScreenShare()
            return
        }
        if (phone && mirror.ready && mirror.ownDisplay) mirror.close()
        if (!mirror.ready && !mirrorOpen(pkg, ownDisplay = !phone)) {
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
            return
        }
        if (ticket != launchTicket.get()) {
            DiagLog.i(tag, "$pkg was not opened because something else was chosen on the car first")
            return
        }
        var placed = false
        if (!phone && mirror.ownDisplay && mirror.displayId > 0) {
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
        mirrorStart(pkg, ticket)
    }

    private fun mirrorStart(pkg: String, ticket: Int) {
        if (!mirror.ready && !mirrorOpen(pkg, ownDisplay = true)) {
            _state.update { it.copy(mirroring = false, mirrorPackage = pkg) }
            return
        }
        if (ticket != launchTicket.get()) return
        aaGestureViewport.reset() // AA -> mirror may occur without a touch event.
        _state.update { it.copy(aaOverlay = false, mirroring = true, mirrorPackage = pkg) }
        watchTurning()
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            MirrorSink.surface.collect { surface ->
                try {
                    if (surface != null) mirror.show(surface) else mirror.hide()
                } catch (t: Throwable) {
                    DiagLog.e(tag, "mirror surface failed", t)
                }
            }
        }
    }

    private fun ensureProjection(reason: () -> String, quiet: Boolean = false): MediaProjection? {
        return synchronized(projectionLock) {
            projection?.let { return it }
            val code = app.mirrorResultCode
            val data = app.mirrorData
            if (code == 0 || data == null) {
                if (!quiet) DiagLog.i(tag, "screen sharing has not been allowed yet")
                return null
            }
            try {
                foreground(reason, projection = true, microphone = canRecord())
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(code, data)
                forgetGrant(data)
                if (mp == null) {
                    DiagLog.w(tag, "media projection unavailable")
                    app.mirrorGranted.value = false
                    return null
                }
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        soundLater {
                            if (projection !== mp) {
                                DiagLog.d(tag, "an older screen share ended, the current one keeps running")
                                return@soundLater
                            }
                            DiagLog.i(tag, "screen sharing ended")
                            projection = null
                            app.mirrorGranted.value = false
                            audio.stop()
                            mirrorJob?.cancel()
                            mirrorJob = null
                            mirror.close()
                            _state.update { it.copy(mirroring = false, mirrorPackage = "") }
                        }
                    }
                }, Handler(Looper.getMainLooper()))
                val previous = projection
                projection = mp
                if (previous != null && previous !== mp) runCatching { previous.stop() }
                mp
            } catch (t: Throwable) {
                DiagLog.e(tag, "could not start screen sharing", t)
                forgetGrant(data)
                app.mirrorGranted.value = false
                null
            }
        }
    }

    private fun forgetGrant(data: Intent) {
        if (app.mirrorData !== data) return
        app.mirrorResultCode = 0
        app.mirrorData = null
    }

    private fun soundLater(block: () -> Unit) {
        runCatching { soundQueue.execute { runCatching { block() }.onFailure { DiagLog.e(tag, "a step of the car sound failed", it) } } }
            .onFailure { DiagLog.d(tag, "the car sound queue has closed, a late sound step was skipped") }
    }

    private fun soundToCar() = soundLater { startAudioToCar() }

    private enum class AudioStart { RUNNING, FAILED, SKIPPED }

    private fun startAudioToCar(): AudioStart {
        if (audio.active) return AudioStart.RUNNING
        if (carPlaysOurSound()) return AudioStart.SKIPPED
        if (!canRecord()) {
            DiagLog.w(tag, "no microphone permission, the car will get picture without sound")
            return AudioStart.SKIPPED
        }
        val mp = ensureProjection({ Lang.str(R.string.note_sound) }, quiet = true) ?: return AudioStart.SKIPPED
        return if (audio.start(mp)) {
            silencePhone()
            AudioStart.RUNNING
        } else AudioStart.FAILED
    }

    private fun carPlaysOurSound(): Boolean {
        if (!prefs.soundOverBluetooth) return false
        btAudio.open()
        if (!btAudio.ready()) return false
        val playing = btAudio.somethingIsPlaying()
        if (playing != null) {
            val who = try {
                if (prefs.showOtherDevicesInLogs || playing.address.equals(prefs.carLifeVehicleAddress.orEmpty().ifBlank { prefs.carBtAddress }, true))
                    playing.name ?: playing.address else "Bluetooth audio device"
            } catch (_: SecurityException) {
                "car"
            }
            restorePhone()
            audio.stop()
            DiagLog.i(tag, "'$who' is already playing this phone's sound over bluetooth, so FT will not send it again")
            return true
        }
        val car = btAudio.theCar(prefs.carBtName, prefs.carLifeVehicleAddress.orEmpty().ifBlank { prefs.carBtAddress }, prefs.carLifeVehicleAddress.orEmpty().isNotBlank())
        if (car != null && btAudio.askCarToPlay(car)) {
            restorePhone()
            return true
        }
        DiagLog.i(tag, "nothing is taking this phone's sound over bluetooth, FT will stream it to the car instead")
        return false
    }

    private fun silencePhone() {
        if (!prefs.muteWhileProjecting || savedVolume >= 0) return
        if (phoneVolume.mute()) DiagLog.i(tag, "phone speaker silenced, sound plays on the car only (phone volume was $savedVolume)")
    }

    private fun restorePhone() {
        if (phoneVolume.restore()) DiagLog.i(tag, "phone speaker volume recovered; any user change was kept")
    }

    private fun canShareQuietly() = !quietShareFailed && QuietShare.allowed(this)

    private fun shareQuietly() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - quietShareAt < 10_000) return
        quietShareAt = now
        runCatching {
            startActivity(
                Intent(this, ShareActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }.onFailure { DiagLog.w(tag, "could not start screen sharing quietly: ${it.message}") }
    }

    private fun askForScreenShare() {
        DiagLog.i(tag, "asking on the phone for screen sharing, the car is waiting")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("mirror", true)
            )
        }.onFailure { DiagLog.w(tag, "could not open FT to ask for screen sharing: ${it.message}") }
    }

    fun volume(up: Boolean) = soundLater {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (savedVolume >= 0) {
            if (phoneVolume.adjust(up)) DiagLog.i(tag, "phone volume for when you disconnect: $savedVolume (use the car's own volume for the car)")
            return@soundLater
        }
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI
        )
    }

    private fun mirrorOpen(pkg: String, ownDisplay: Boolean): Boolean {
        val shownName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val mp = ensureProjection({ Lang.str(R.string.note_showing_app, shownName) }) ?: return false
        return try {
            val useCar = ownDisplay && prefs.carSizedApps && carDisplay.width > 0 && carDisplay.height > 0
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = if (Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds
            } else {
                val size = android.graphics.Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(size)
                android.graphics.Rect(0, 0, size.x, size.y)
            }
            val w = if (useCar) carDisplay.width else metrics.width()
            val h = if (useCar) carDisplay.height else metrics.height()
            val d = if (useCar) prefs.carDensity else resources.displayMetrics.densityDpi
            val ok = mirror.open(mp, w, h, d, useCar, carDisplay.width, carDisplay.height)
            if (ok && !audio.active) soundToCar()
            ok
        } catch (t: Throwable) {
            DiagLog.e(tag, "mirror start failed", t)
            false
        }
    }

    private fun mirrorStop() {
        launchTicket.incrementAndGet()
        mirrorJob?.cancel()
        mirrorJob = null
        val was = mirror.active
        mirror.hide()
        if (_state.value.mirroring) _state.update { it.copy(mirroring = false, mirrorPackage = "") }
        if (was) DiagLog.i(tag, "mirror stopped")
    }

    private fun mirrorClose() {
        mirrorStop()
        stopWatchingTurning()
        restoreRotation()
        soundLater {
            audio.stop()
            restorePhone()
            mirror.close()
            val released = synchronized(projectionLock) { projection.also { projection = null } }
            if (released != null) {
                runCatching { released.stop() }
                app.mirrorGranted.value = false
                DiagLog.i(tag, "screen sharing released, allow it again on the phone for the next drive")
            }
        }
    }

    private fun home() {
        mirrorStop()
        if (AaHeadUnitService.requestVideoFocus(false, "CarLife FT button")) return
        stopAndroidAuto("you went back to FT")
    }

    private fun stopAndroidAuto(reason: String) {
        aaStartJob?.cancel()
        aaStartJob = null
        aaReturn?.cancel()
        aaReturn = null
        val wasOn = _state.value.aaOverlay
        val wasRunning = AaHeadUnitService.current != null || AaHeadUnitService.state.value.listening
        if (!wasOn && !wasRunning) return
        aaWatch?.cancel()
        aaWatch = null
        if (wasOn) aaOverlay(false)
        CarAudioBus.clear(CarAudioBus.LANE_MEDIA)
        CarAudioBus.clear(CarAudioBus.LANE_SPEECH)
        CarAudioBus.clear(CarAudioBus.LANE_SYSTEM)
        if (wasRunning) AaHeadUnitService.stop(this)
        DiagLog.i(tag, "Android Auto stopped because $reason")
    }

    private fun teardown() {
        pendingUsb = null
        preparation.cancel()
        aaStartJob?.cancel()
        aaStartJob = null
        if (AaHeadUnitService.running) runCatching { AaHeadUnitService.stop(this) }
        if (SuperCast.ready.value) SuperCast.later {
            SuperCast.phoneScreen(true)
            SuperCast.keepAwake(false)
        }
        stopLink()
        stopSoundWatch()
        stopWatchingTurning()
        restoreRotation()
        mediaReady = false
        soundDeclined = false
        blockedSoundNoticed = false
        routePlayer()
        aaAutoLaunched = false
        autoOpened = false
        carPaused = false
        quietShareFailed = false
        quietShareAt = 0L
        aaWatch?.cancel()
        aaWatch = null
        mode = -1
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        releaseWifi()
        _state.value = CarState()
    }

    private fun routePlayer() {
        val linked = mediaReady && session != null && CarAudioBus.open
        val overBluetooth = linked && prefs.soundOverBluetooth && runCatching {
            btAudio.open()
            btAudio.ready() && btAudio.somethingIsPlaying() != null
        }.getOrDefault(false)
        val want = linked && !overBluetooth
        if (CarPlayer.toCar == want) return
        if (!want && CarPlayer.playing) {
            CarPlayer.pause()
            DiagLog.i(tag, "the car is gone, FT's music is paused instead of moving to the phone speaker")
        }
        CarPlayer.toCar = want
        DiagLog.i(tag, if (want) "FT's player plays on the car" else if (overBluetooth) "FT's player plays over bluetooth" else "FT's player plays on the phone")
    }

    private val placeholderCover: ByteArray? by lazy {
        runCatching { resources.openRawResource(R.raw.song_placeholder).use { it.readBytes() } }.getOrNull()
    }

    private suspend fun tellCarTheSong(t: Track) {
        val s = session ?: return
        if (songInfoSuspended || !prefs.carSongInfo || !CarPlayer.toCar) return
        if (AaHeadUnitService.music.value.let { it.playing && it.track != null }) return
        val art = MediaLibrary.artBytes(this, t, 240)?.let { big ->
            if (big.size <= 24_000) big else MediaLibrary.artBytes(this, t, 160)
        }?.takeIf { it.size <= 30_000 } ?: placeholderCover
        if (session !== s || songInfoSuspended || !prefs.carSongInfo || !CarPlayer.toCar) return
        if (AaHeadUnitService.music.value.let { it.playing && it.track != null }) return
        val st = CarPlayer.state.value
        runCatching { s.sendSong(t.title, t.artist, t.album, art, t.durationMs, st.count, t.id.toString()) }
            .onFailure { DiagLog.w(tag, "could not tell the car the song: ${it.message}") }
    }

    private fun songPosition(st: CarPlayer.State) {
        if (AaHeadUnitService.music.value.let { it.playing && it.track != null }) return
        val t = st.track ?: return
        if (t.video || !st.playing || !st.toCar || songInfoSuspended || !prefs.carSongInfo) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSongPositionAt < 1000) return
        lastSongPositionAt = now
        runCatching { session?.sendSongPosition(st.positionMs) }
    }

    private fun startSoundWatch() {
        if (soundWatch?.isActive == true) return
        soundWatch = scope.launch {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            var heard = 0
            while (true) {
                delay(800)
                val st = _state.value
                checkBlockedSound(am, st)
                val busy = st.waitingForShare || st.aaOverlay || st.mirroring || audio.active || CarPlayer.playing ||
                    CarAudioBus.inCall || soundDeclined || AaHeadUnitService.current != null || session?.projecting != true
                if (busy || !am.isMusicActive) {
                    heard = 0
                    continue
                }
                if (++heard < 2) continue
                heard = 0
                routePhoneSound("the phone is playing")
            }
        }
    }

    private fun routePhoneSound(why: String) {
        if (audio.active) return
        if (prefs.soundOverBluetooth && btAudio.ready() && btAudio.somethingIsPlaying() != null) return
        if (!canRecord()) return
        if (projection != null || (app.mirrorResultCode != 0 && app.mirrorData != null)) {
            DiagLog.i(tag, "$why, sending its sound to the car")
            soundToCar()
            return
        }
        if (canShareQuietly()) {
            DiagLog.i(tag, "$why, sharing its sound with the car without asking")
            shareQuietly()
            return
        }
        DiagLog.i(tag, "$why sound the car cannot hear yet, asking on the phone")
        _state.update { it.copy(waitingForShare = true, shareForSound = true, mirrorPackage = "") }
        askForScreenShare()
    }

    internal fun soundSoon() {
        val st = _state.value
        if (st.waitingForShare || st.aaOverlay || st.mirroring || audio.active) return
        if (CarAudioBus.inCall || soundDeclined || AaHeadUnitService.current != null) return
        if (session?.projecting != true) return
        routePhoneSound("a screen that plays opened on the car")
    }

    private fun checkBlockedSound(am: android.media.AudioManager, st: CarState) {
        if (blockedSoundNoticed || !audio.active) return
        val now = android.os.SystemClock.elapsedRealtime()
        val blocked = SoundCheck.blocked(
            captureRunningMs = now - audio.startedAt,
            msSinceLoud = now - audio.lastLoudAt,
            phonePlaying = am.isMusicActive,
            ftPlaying = CarPlayer.playing,
            androidAuto = st.aaOverlay || AaHeadUnitService.current != null,
            inCall = CarAudioBus.inCall
        )
        if (!blocked) return
        blockedSoundNoticed = true
        DiagLog.i(tag, "the phone is playing but the car hears silence: that app does not let its sound be shared")
        notice(R.string.notice_sound_blocked)
    }

    private fun notice(text: Int) {
        _state.update { it.copy(notice = text) }
        scope.launch {
            delay(8000)
            _state.update { if (it.notice == text) it.copy(notice = 0) else it }
        }
    }

    private fun stopSoundWatch() {
        soundWatch?.cancel()
        soundWatch = null
        if (_state.value.shareForSound) _state.update { it.copy(waitingForShare = false, shareForSound = false) }
    }

    @Suppress("DEPRECATION")
    private fun phoneSize(): Pair<Int, Int>? {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return null
        val p = android.graphics.Point()
        d.getRealSize(p)
        return if (p.x > 0 && p.y > 0) p.x to p.y else null
    }

    private fun watchTurning() {
        if (turnWatch != null || mirror.ownDisplay) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY || mirror.ownDisplay) return
                val (w, h) = phoneSize() ?: return
                mirror.resize(w, h)
            }
        }
        dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        turnWatch = l
        phoneSize()?.let { (w, h) -> mirror.resize(w, h) }
    }

    private fun stopWatchingTurning() {
        val l = turnWatch ?: return
        turnWatch = null
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun rotate() {
        if (!android.provider.Settings.System.canWrite(this)) {
            DiagLog.i(tag, "turning the phone needs 'Modify system settings' for FT, asking on the phone")
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { DiagLog.w(tag, "could not open the setting: ${it.message}") }
            val back = _state.value.mirrorPackage
            rotateJob?.cancel()
            rotateJob = scope.launch {
                repeat(120) {
                    delay(1000)
                    if (android.provider.Settings.System.canWrite(this@CarLifeService)) {
                        DiagLog.i(tag, "FT may turn the phone now")
                        turnPhone()
                        if (back.isNotBlank() && _state.value.mirroring) {
                            packageManager.getLaunchIntentForPackage(back)?.let { i ->
                                runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                        return@launch
                    }
                }
            }
            return
        }
        turnPhone()
    }

    private fun turnPhone() {
        val cr = contentResolver
        runCatching {
            if (savedRotation == null) {
                savedRotation = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) to
                    android.provider.Settings.System.getInt(cr, android.provider.Settings.System.USER_ROTATION, 0)
            }
            val was = phoneSize()
            val (w, h) = was ?: (0 to 0)
            val upright = h >= w
            val next = if (upright) android.view.Surface.ROTATION_90 else android.view.Surface.ROTATION_0
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.USER_ROTATION, next)
            DiagLog.i(tag, "asked the phone to turn to ${if (upright) "landscape" else "portrait"}")
            turnJob?.cancel()
            turnJob = scope.launch { watchTheTurn(was) }
        }.onFailure { DiagLog.w(tag, "could not turn the phone: ${it.message}") }
    }

    private suspend fun watchTheTurn(was: Pair<Int, Int>?) {
        repeat(20) {
            delay(100)
            val now = phoneSize()
            if (now != null && now != was) {
                DiagLog.i(tag, "phone turned to ${if (now.first > now.second) "landscape" else "portrait"}")
                mirror.resize(now.first, now.second)
                return
            }
        }
        DiagLog.w(tag, "the phone would not turn: the app on its screen only works one way up")
    }

    private fun restoreRotation() {
        rotateJob?.cancel()
        rotateJob = null
        turnJob?.cancel()
        turnJob = null
        val (auto, user) = savedRotation ?: return
        savedRotation = null
        runCatching {
            val cr = contentResolver
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.USER_ROTATION, user)
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, auto)
            DiagLog.i(tag, "phone rotation set back the way it was")
        }
    }

    private var btWatch: android.content.BroadcastReceiver? = null
    private var usbWatch: android.content.BroadcastReceiver? = null
    @Volatile private var envLoggedAt = -1L
    @Volatile private var directStartedAt = 0L
    @Volatile private var blockerNote = ""
    @Volatile private var lastOtherWifi = -1
    @Volatile private var hotspotStoppedForDirect = false
    @Volatile private var usbStartedAt = 0L
    @Volatile private var usbQuietUntil = 0L
    private var lastUsb = ""

    private fun watchUsb() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    "android.hardware.usb.action.USB_STATE" -> {
                        val ex = i.extras
                        val flags = ex?.keySet()?.sorted()?.filter { ex.getBoolean(it, false) }.orEmpty()
                        val line = "connected=${ex?.getBoolean("connected") == true} configured=${ex?.getBoolean("configured") == true} on: ${flags.joinToString().ifBlank { "nothing" }}"
                        if (line == lastUsb) return
                        lastUsb = line
                        DiagLog.i("USB", "usb $line")
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                        DiagLog.i("USB", "usb accessory detached: ${usbAccessory(i)}")
                        val detached = accessoryFrom(i)
                        if (pendingUsb != null && pendingUsb == detached) {
                            pendingUsb = null
                            preparation.cancel()
                            _state.update { it.copy(aaPreparation = null) }
                        }
                        if (mode == 2 && usbLink != null) usbGone("the cable was unplugged")
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED, android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        DiagLog.i("USB", "usb device ${if (i.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) "attached" else "detached"}")
                }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            usbWatch = r
        }.onFailure { DiagLog.w(tag, "cannot watch usb: ${it.message}") }
        val um = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        runCatching { um?.accessoryList?.forEach { a -> DiagLog.i("USB", "usb accessory present: ${a.manufacturer} ${a.model} ${a.version} ${a.description}") } }
    }

    private fun accessoryFrom(i: Intent): android.hardware.usb.UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY, android.hardware.usb.UsbAccessory::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY)

    private fun usbAccessory(i: Intent): String {
        val a = accessoryFrom(i)
        return a?.let { "${it.manufacturer} ${it.model} ${it.version} ${it.description}" } ?: "unknown"
    }

    @SuppressLint("MissingPermission")
    private fun watchCarBluetoothAudio() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val device: android.bluetooth.BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                }
                val known = prefs.carLifeVehicleAddress.orEmpty().ifBlank { prefs.carBtAddress }
                if (!prefs.showOtherDevicesInLogs && (known.isBlank() || device?.address?.equals(known, true) != true)) return
                val who = runCatching { device?.name }.getOrNull() ?: device?.address ?: "a device"
                val what = if (i.action == android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "audio" else "calls"
                val state = when (i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)) {
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> "connected"
                    android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "connecting"
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                    else -> return
                }
                val st = _state.value
                val carLife = when (st.session) {
                    is CarLifeSession.State.Projecting -> "on screen"
                    is CarLifeSession.State.Idle -> "off"
                    else -> "connecting"
                }
                val aa = if (st.aaOverlay) "on the car" else if (AaHeadUnitService.current != null) "starting" else "off"
                DiagLog.i("CarBT", "bluetooth $what for '$who' $state (CarLife $carLife, Android Auto $aa)")
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            btWatch = r
        }.onFailure { DiagLog.w(tag, "cannot watch the car's bluetooth audio: ${it.message}") }
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("FT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: () -> String) {
        note = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text()))
    }

    override fun onDestroy() {
        preparationScope.cancel()
        bluetoothReconnect?.close(); bluetoothReconnect = null
        prefs.unregisterListener(navigationPreferenceListener)
        teardown()
        soundQueue.shutdown()
        runCatching { soundQueue.awaitTermination(SOUND_DRAIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        releaseWifi()
        btWatch?.let { runCatching { unregisterReceiver(it) } }
        btWatch = null
        usbWatch?.let { runCatching { unregisterReceiver(it) } }
        usbWatch = null
        usbAsk?.let { runCatching { unregisterReceiver(it) } }
        usbAsk = null
        testCableServer?.let { runCatching { it.close() } }
        testCableServer = null
        testCable?.cancel()
        testCable = null
        progressJob?.cancel()
        CarPlayer.onTrack = null
        CarPlayer.onBreak = null
        CarAudioBus.onBreak = null
        runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(heatListener) }
        scope.cancel()
        writer?.interrupt()
        writer = null
        outbound.clear()
        queuedBytes.set(0)
        CarAudioBus.voice = null
        stopWatchingCalls()
        voiceWriter?.interrupt()
        voiceWriter = null
        voiceOut.clear()
        voiceQueued.set(0)
        btAudio.close()
        CarAudioBus.sink = null
        AaHeadUnitService.onNavigation = null
        instance = null
        _state.value = CarState()
        CarConnection.lifecycleChanged()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
