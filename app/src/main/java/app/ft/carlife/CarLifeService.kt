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
import app.ft.FTTouchService
import app.ft.MainActivity
import app.ft.ShareActivity
import app.ft.aa.AaKeep
import app.ft.aa.AaInstaller
import app.ft.aa.AaTouchViewport
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.core.CarAudioBus
import app.ft.core.DiagLog
import app.ft.supercast.SuperCast
import app.ft.core.DiagSnapshot
import app.ft.core.Root
import app.ft.core.RootPrep
import app.ft.media.CarPlayer
import app.ft.projection.CarDisplay
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.ft.core.Lang
import kotlinx.coroutines.flow.drop

data class CarState(
    val running: Boolean = false,
    val direct: Boolean = false,
    val link: String = "",
    val session: CarLifeSession.State = CarLifeSession.State.Idle,
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
)

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

        fun startWifi(context: Context) = startAuto(context)

        fun startAuto(context: Context) {
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
            send(context, ACTION_STOP, foreground = false)
        }

        fun useUsb(context: Context, accessory: android.hardware.usb.UsbAccessory) {
            val intent = Intent(context, CarLifeService::class.java).setAction(ACTION_USB).putExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY, accessory)
            runCatching { context.startForegroundService(intent) }
                .onFailure { DiagLog.w("CarLife", "Android would not let FT start for the car's USB (${it.javaClass.simpleName}), open FT to connect") }
        }

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, CarLifeService::class.java).setAction(action)
            runCatching { if (foreground) context.startForegroundService(intent) else context.startService(intent) }
                .onFailure { DiagLog.w("CarLife", "Android would not let FT start right now (${it.javaClass.simpleName}), open FT to connect") }
        }

        fun soundSoon() {
            instance?.let { svc -> svc.scope.launch { svc.soundSoon() } }
        }

        fun startAudio(context: Context) {
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
    private lateinit var carDisplay: CarDisplay
    private val mirror = PhoneMirror()
    private var session: CarLifeSession? = null
    private var wifiLink: WifiChannelLink? = null
    private var usbLink: AccessoryLink? = null
    @Volatile private var usbAccessory: android.hardware.usb.UsbAccessory? = null
    private var usbAsk: android.content.BroadcastReceiver? = null
    private var testCable: Job? = null
    private var languageWatch: Job? = null
    @Volatile private var testCableServer: java.net.ServerSocket? = null
    private var finder: CarFinder? = null
    private var stateJob: Job? = null
    @Volatile private var mirrorJob: Job? = null
    private var ipJob: Job? = null
    private var aaWatch: Job? = null
    @Volatile private var mode = -1
    private var callWatch: Any? = null
    private var callPoll: Job? = null
    private var p2pWatch: Job? = null
    @Volatile private var note: () -> String = { Lang.str(R.string.app_name) }
    @Volatile private var carNetworkIface: String? = null
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

    private class VoiceCmd(val kind: Int, val rate: Int = 0, val channels: Int = 0, val pcm: ByteArray? = null)
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
        voiceOut.offer(c)
    }

    private fun startVoiceWriter() {
        if (voiceWriter?.isAlive == true) return
        voiceWriter = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (true) {
                    val c = voiceOut.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (c == null) {
                        CarAudioBus.tick()
                        continue
                    }
                    c.pcm?.let { voiceQueued.addAndGet(-it.size.toLong()) }
                    val s = session ?: continue
                    when (c.kind) {
                        VOICE_START -> {
                            s.sendVoiceStart(c.rate, c.channels)
                            DiagLog.i(tag, "voice on the car's own voice channel, ${c.rate} Hz")
                        }
                        VOICE_DATA -> c.pcm?.let { s.sendVoice(it) }
                        else -> {
                            s.sendVoiceEnd()
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
    private var carNetwork: android.net.ConnectivityManager.NetworkCallback? = null
    private var aaReturn: Job? = null
    @Volatile private var pendingLaunch: Pair<Intent, String>? = null
    @Volatile private var soundDeclined = false
    @Volatile private var blockedSoundNoticed = false
    @Volatile private var songInfoAt = 0L
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
    @Volatile private var savedVolume = -1
    private val app get() = application as FTApp
    private val beacon by lazy { CarBeacon(scope, { beaconTargets() }, { carGroupOwner() }, { helloForCar() }) }
    private val hotspotClients by lazy {
        HotspotClients(scope, { NetUtil.hotspotAddresses(this) }, { mode == 0 && wifiLink?.connected != true }) { list -> onHotspotClients(list) }
    }
    private val busyPorts: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val discovery by lazy { CarDiscovery(scope) { from -> sameWay(from) } }
    @Volatile private var p2pIface: String? = null
    @Volatile private var carP2pIp: String? = null
    private val bt by lazy { CarBluetooth(this, app.prefs, scope) }
    private val btAudio by lazy { CarBtAudio(this) }
    private val ble by lazy {
        CarIccoaBle(
            this,
            onOffer = { offer -> onCarOfferedNetwork(offer) },
            onStep = { text -> step(text) }
        )
    }
    private fun step(text: String) {
        DiagLog.i(tag, text)
        _state.update { it.copy(step = text) }
    }

    private val wireless by lazy {
        CarWirelessSetup(
            send = { bytes -> bt.send(bytes) },
            onCarWifiName = { name -> onCarRaisedWifiDirect(name) },
            progress = { text -> step(text) },
            onCarReady = { if (mode == 1 && wifiLink?.connected != true) finder?.carReady() }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CarAudioBus.mixTogether = true
        CarAudioBus.guidance = app.prefs.guidanceMode
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
        watchUsb()
        progressJob = scope.launch {
            CarPlayer.state.collect { st -> songPosition(st) }
        }
        carDisplay = CarDisplay(this)
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
        if (intent == null && !app.prefs.autoConnect) {
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
                    pendingLaunch?.let { (_, pkg) ->
                        _state.update { it.copy(mirroring = false, mirrorPackage = pkg, waitingForShare = true, shareForSound = false) }
                        askForScreenShare()
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
                DiagLog.i("Env", "connecting via ${wayName(if (mode == 2) 2 else app.prefs.linkMode)}, what FT sees on this phone:")
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
        listenForTestCable()
        if (mode == 2 && usbLink != null) {
            foreground(note)
            DiagLog.i(tag, "the car is on the USB cable, staying on it")
            return
        }
        if (cableFirst()) return
        val wanted = when (app.prefs.linkMode) {
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
                    if (granted && a != null && app.prefs.autoConnect) useUsb(a)
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

    private fun useUsb(acc: android.hardware.usb.UsbAccessory) {
        if (mode == 2 && usbLink != null) DiagLog.i(tag, "USB: the car attached again, starting the cable link over")
        DiagLog.i(tag, "USB: the car opened CarLife over the cable (${UsbCar.describe(acc)}), using the cable")
        val um = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        startCable(acc, "the car's ${acc.model ?: "CarLife"} over the cable") {
            val pfd = um.openAccessory(acc) ?: return@startCable null
            AccessoryLink.Pipe(java.io.FileInputStream(pfd.fileDescriptor), java.io.FileOutputStream(pfd.fileDescriptor)) { pfd.close() }
        }
    }

    private fun startCable(acc: android.hardware.usb.UsbAccessory?, what: String, open: () -> AccessoryLink.Pipe?) {
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
        if (app.prefs.autoConnect) startChosenWay()
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
        val p = app.prefs
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
            onBound = { port -> portBound(port) }
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

    private fun startDirect() {
        wireless.reset()
        bt.onFrame = { frame -> wireless.feed(frame) }
        bt.onStep = { text -> step(text) }
        bt.onCar = { name ->
            if (name != null) {
                _state.update { it.copy(btCar = name) }
                step("'$name' called over bluetooth")
                wireless.reset()
                wireless.begin()
            }
        }
        step("Waiting for the car to call over bluetooth")
        bt.start()
        ble.start(null)
        wakeCarBluetooth()
        startFinder()
        if (app.prefs.carP2pName.isNotBlank()) finder?.search()
        watchWifiDirect()
    }

    private fun watchWifiDirect() {
        p2pWatch?.cancel()
        directStartedAt = android.os.SystemClock.elapsedRealtime()
        blockerNote = ""
        lastOtherWifi = -1
        hotspotStoppedForDirect = false
        p2pWatch = scope.launch {
            var ticks = 0
            while (mode == 1) {
                delay(2000)
                if (wifiLink?.connected == true) continue
                if (ticks++ % 5 == 0) checkDirectBlockers()
                askCarAgain()
                val ip = NetUtil.wifiDirectIpv4(p2pIface)
                val up = _state.value.wifiDirect
                if (ip != null && !up) {
                    DiagLog.i(tag, "WiFi Direct is up at $ip, picking it up")
                    finder?.checkExisting()
                    _state.update { it.copy(wifiDirect = true, ip = ip, joinTimedOutSeconds = 0) }
                    wireless.wifiDirectReady(ip)
                    updateBeacon()
                } else if (ip == null && up && carNetworkIface == null) {
                    DiagLog.i(tag, "WiFi Direct is gone")
                    _state.update { it.copy(wifiDirect = false) }
                    wireless.wifiDirectReady(null)
                    updateBeacon()
                }
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

    private fun askCarAgain() {
        val name = _state.value.carWifi ?: return
        if (mode != 1 || !bt.open || _state.value.wifiDirect || wifiLink?.connected == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        val inSight = finder?.inSight(name) == true
        if (inSight || carSeenAt == 0L) carSeenAt = now
        if (!JoinWatch.askCarAgain(now - carSeenAt, inSight)) return
        DiagLog.i(tag, "WiFi + BL: the car's WiFi Direct '$name' has not shown up for ${(now - carSeenAt) / 1000}s, asking the car for it again over bluetooth")
        carSeenAt = now
        wireless.askName()
    }

    private fun stopLink() {
        stopAndroidAuto("the car link closed")
        mirrorClose()
        p2pWatch?.cancel()
        p2pWatch = null
        beacon.stop()
        discovery.stop()
        bt.stop()
        bt.onCar = null
        bt.onFrame = null
        ble.stop()
        wireless.reset()
        stopFinder()
        p2pIface = null
        carP2pIp = null
        leaveCarNetwork()
        session?.stop()
        session = null
        AaHeadUnitService.onCarMicWanted = null
        gearForget()
        stateJob?.cancel()
        stateJob = null
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
        val f = CarFinder(this, app.prefs, scope)
        finder = f
        f.onJoinTimeout = { seconds -> _state.update { it.copy(joinTimedOutSeconds = seconds) } }
        f.onJoined = { l ->
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
        f.onLeft = {
            ipJob?.cancel()
            p2pIface = null
            carP2pIp = null
            wireless.wifiDirectReady(null)
            _state.update { it.copy(wifiDirect = false) }
            updateBeacon()
            step("WiFi Direct dropped, looking for the car again")
        }
        f.start()
    }

    private fun stopFinder() {
        ipJob?.cancel()
        ipJob = null
        finder?.stop()
        finder = null
    }

    private fun retry() {
        if (mode != 1) return
        DiagLog.i(tag, "trying the car again, keeping whatever is already connected")
        if (bt.open) {
            step("Asking the car again over bluetooth")
            wireless.reset()
            wireless.begin()
        } else {
            bt.start()
            wakeCarBluetooth()
        }
        startFinder()
        finder?.checkExisting()
        updateBeacon()
    }

    private fun onWifiDirectSide(iface: String): Boolean =
        iface.startsWith("p2p") || iface == p2pIface || (carNetworkIface != null && iface == carNetworkIface)

    private fun beaconTargets(): Collection<java.net.InetAddress> {
        if (mode != 1) {
            val out = LinkedHashSet<java.net.InetAddress>(NetUtil.broadcastAddresses { !onWifiDirectSide(it) })
            _state.value.hotspotClients?.forEach { ip -> runCatching { java.net.InetAddress.getByName(ip) }.getOrNull()?.let(out::add) }
            return out
        }
        val out = LinkedHashSet<java.net.InetAddress>(NetUtil.broadcastAddresses { onWifiDirectSide(it) })
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
        return if (mode == 1) onWifiDirectSide(iface) else !onWifiDirectSide(iface)
    }

    private fun wrongWay(socket: java.net.Socket): String? {
        val local = socket.localAddress ?: return null
        if (local.isLoopbackAddress) return null
        val iface = runCatching { java.net.NetworkInterface.getByInetAddress(local)?.name }.getOrNull().orEmpty()
        val side = onWifiDirectSide(iface)
        val why = if (mode == 1) {
            if (side) null else "The car tried the hotspot, but FT is set to WiFi + BL"
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

    private fun attachSession(l: CarLifeLink) {
        session?.stop()
        val s = CarLifeSession(this, l, app.prefs, scope)
        s.fullRateStart = mode != 1
        session = s
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
        s.onPauseMedia = { carMusic(false) }
        s.onMusicControl = { play -> carMusic(play) }
        s.onClosed = { reason ->
            DiagLog.i(tag, "link closed: $reason")
            val sinceSong = android.os.SystemClock.elapsedRealtime() - songInfoAt
            if (songInfoAt > 0 && sinceSong < 3000 && app.prefs.carSongInfo) {
                app.prefs.carSongInfo = false
                DiagLog.w(tag, "the car dropped the connection ${sinceSong}ms after it was told the song, so FT stops sending song info (Settings, Car screen)")
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
        stateJob?.cancel()
        stateJob = scope.launch {
            s.state.collect { st ->
                _state.update { it.copy(link = l.name, session = st, refused = if (st !is CarLifeSession.State.Idle) 0 else it.refused) }
                updateBeacon()
                if (st is CarLifeSession.State.Projecting && !mediaReady) {
                    mediaReady = true
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
                if (st is CarLifeSession.State.Projecting && app.prefs.aaAutoStart && !aaAutoLaunched) {
                    aaAutoLaunched = true
                    DiagLog.i(tag, "auto-starting Android Auto after connection")
                    startAndroidAuto()
                }
                if (st is CarLifeSession.State.Projecting && !autoOpened) {
                    autoOpened = true
                    openOnConnect()
                    if (app.prefs.supercastOn && !SuperCast.ready.value) {
                        scope.launch(Dispatchers.IO) {
                            DiagLog.i(tag, "starting the apps-on-the-car helper after connection")
                            SuperCast.start(applicationContext)
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
        CarAudioBus.inCall = calling
        DiagLog.i(tag, if (calling) "phone call started, the car's voice channel stays closed until it ends" else "phone call ended")
    }

    private fun watchCalls() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        onAudioMode(am.mode)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = android.media.AudioManager.OnModeChangedListener { m -> onAudioMode(m) }
            runCatching { am.addOnModeChangedListener(mainExecutor, l) }
                .onSuccess { callWatch = l }
        } else {
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
        bt.onFrame = if (on) { frame -> bt.send(frame) } else null
        DiagLog.i(tag, "bluetooth echo ${if (on) "on" else "off"}")
    }

    @SuppressLint("MissingPermission")
    private fun triggerAaWireless() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val wantAddress = app.prefs.carBtAddress.trim()
        val want = app.prefs.carBtName.trim()
        val byAddress = bonded.firstOrNull { d -> wantAddress.isNotEmpty() && d.address.equals(wantAddress, true) }
        val byName = bonded.firstOrNull { d -> want.isNotEmpty() && runCatching { d.name }.getOrNull()?.contains(want, true) == true }
        val byClass = bonded.filter { d -> runCatching { d.bluetoothClass?.deviceClass }.getOrNull() == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO }
        val device = byAddress ?: byName ?: byClass.singleOrNull()
        DiagLog.i(tag, "car for Android Auto over bluetooth: " + when {
            byAddress != null -> "the car that called FT (${byAddress.address})"
            byName != null -> "the paired device named like '$want'"
            byClass.size == 1 -> "the only paired device that says it is a car"
            byClass.size > 1 -> "none, ${byClass.size} paired devices say they are cars and FT does not know which one"
            else -> "none, no paired device says it is a car and FT has not heard from one yet"
        } + " (paired: ${bonded.joinToString { d -> "'${runCatching { d.name }.getOrNull() ?: "?"}' class ${runCatching { Integer.toHexString(d.bluetoothClass?.deviceClass ?: 0) }.getOrNull()}" }})")
        if (device == null) {
            DiagLog.w(tag, "no bonded bluetooth device to hand Android Auto")
            return
        }
        val name = runCatching { device.name }.getOrNull() ?: device.address
        DiagLog.d(tag, "car bluetooth present as '$name'")
        askAndroidAutoToConnect()
    }

    private fun askAndroidAutoToConnect() {
        val port = app.prefs.aaPort
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
        if (!app.prefs.aaMatchCar) return
        val s = _state.value.session
        val size = when (s) {
            is CarLifeSession.State.Projecting -> Triple(s.width, s.height, s.fps)
            is CarLifeSession.State.Negotiated -> Triple(s.width, s.height, s.fps)
            else -> null
        } ?: return
        if (size.first <= 0 || size.second <= 0) return
        if (app.prefs.aaWidth == size.first && app.prefs.aaHeight == size.second) return
        app.prefs.aaWidth = size.first
        app.prefs.aaHeight = size.second
        if (size.third > 0) app.prefs.aaFps = size.third
        DiagLog.i(tag, "Android Auto will draw at the car's own ${size.first}x${size.second}")
    }

    private fun startAndroidAuto() {
        val pkg = app.prefs.aaPackage
        if (runCatching { packageManager.getPackageInfo(pkg, 0) }.isFailure) {
            DiagLog.w(tag, "Android Auto ($pkg) is not installed on this phone")
            return
        }
        mirrorStop()
        matchCarScreen()
        if (AaHeadUnitService.current == null && !AaHeadUnitService.state.value.listening) {
            AaHeadUnitService.start(this, app.prefs.aaBluetooth)
        }
        DiagLog.i(tag, "bridging this phone's Android Auto onto the car, waiting for it to start projecting")
        scope.launch {
            if (!AaInstaller.serverRunning(app.prefs.aaSelfPort)) {
                DiagLog.i(tag, if (Root.granted) "starting Android Auto's head unit server with root" else "starting Android Auto's head unit server through accessibility")
                AaKeep.switchOn(this@CarLifeService)
            }
            delay(1500)
            askAndroidAutoToConnect()
        }
        aaWatch?.cancel()
        aaWatch = scope.launch {
            var shown = false
            resumedMedia = false
            AaHeadUnitService.state.collect { s ->
                if (s.phase == AaSession.Phase.STREAMING && !resumedMedia) {
                    resumedMedia = true
                    scope.launch {
                        delay(2500)
                        DiagLog.i(tag, "asking Android Auto to pick up where it left off")
                        AaHeadUnitService.current?.sendKey(126)
                    }
                }
                if (s.connected) {
                    aaReturn?.cancel()
                    aaReturn = null
                    if (!_state.value.aaOverlay) {
                        DiagLog.i(tag, "Android Auto is projecting, showing it on the car")
                        shown = true
                        aaOverlay(true)
                    }
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

    private fun routeTouch(raw: CarTouch) {
        val st = _state.value
        val (x, y) = carDisplay.toContent(raw.x, raw.y)
        val second = if (raw.x2 >= 0 && raw.y2 >= 0) carDisplay.toContent(raw.x2, raw.y2) else -1 to -1
        val t = raw.copy(x = x, y = y, x2 = second.first, y2 = second.second)
        if (t.action == CarTouch.DOWN) {
            touchFt = CarTouchZones.hit(x, y)
            DiagLog.i(tag, "car touched ${raw.x},${raw.y} of its picture, ${x},${y} on FT's screen")
        }
        if (touchFt) {
            carDisplay.dispatchTouch(t)
            return
        }
        val action = when (t.action) {
            CarTouch.DOWN, CarTouch.UP, CarTouch.MOVE -> t.action
            else -> -1
        }
        val aa = AaHeadUnitService.current
        if (st.aaOverlay && aa != null && aa.phase.value >= AaSession.Phase.DISCOVERED) {
            if (action < 0) return
            val p = app.prefs
            val viewport = AaTouchViewport.bounds ?: return
            if (action == CarTouch.DOWN) touchBeside = !viewport.contains(x, y)
            if (touchBeside) return
            val point = viewport.map(x, y, p.aaWidth, p.aaHeight)
            aa.sendTouch(point.first, point.second, action)
            return
        }
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
        val want = app.prefs.carBtAddress.trim()
        if (want.isNotEmpty()) bonded.firstOrNull { it.address.equals(want, true) }?.let { return it }
        val name = app.prefs.carBtName.trim()
        if (name.isNotEmpty()) {
            bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains(name, true) == true }?.let { return it }
        }
        return null
    }

    private fun wakeCarBluetooth() {
        scope.launch {
            btAudio.open()
            delay(1500)
            val car = btAudio.theCar(app.prefs.carBtName, app.prefs.carBtAddress) ?: carBtDevice()
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

    private fun onCarOfferedNetwork(offer: CarWifiOffer) {
        if (mode != 1) return
        DiagLog.i(tag, "car network '${offer.ssid}' at ${offer.ip}:${offer.port}")
        _state.update { it.copy(carWifi = offer.ssid) }
        step("Joining the car's WiFi '${offer.ssid}'")
        joinCarNetwork(offer)
    }

    private fun joinCarNetwork(offer: CarWifiOffer) {
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(offer.ssid)
            .apply { if (offer.psk.isNotBlank()) setWpa2Passphrase(offer.psk) }
            .build()
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        carNetwork?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                DiagLog.i(tag, "joined the car's network '${offer.ssid}'")
                carNetworkIface = runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
                _state.update { it.copy(wifiDirect = true) }
                updateBeacon()
                step("On the car's WiFi, waiting for the car")
                runCatching { cm.bindProcessToNetwork(network) }
            }

            override fun onUnavailable() {
                DiagLog.w(tag, "could not join the car's network '${offer.ssid}'")
                step("Could not join the car's network")
            }

            override fun onLost(network: android.net.Network) {
                DiagLog.i(tag, "left the car's network")
                carNetworkIface = null
                _state.update { it.copy(wifiDirect = false) }
                updateBeacon()
                runCatching { cm.bindProcessToNetwork(null) }
            }
        }
        carNetwork = cb
        runCatching { cm.requestNetwork(request, cb) }
            .onFailure { DiagLog.e(tag, "could not ask to join the car's network", it) }
    }

    private fun leaveCarNetwork() {
        carNetwork?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(cb) }
            runCatching { cm.bindProcessToNetwork(null) }
        }
        carNetwork = null
        carNetworkIface = null
    }

    private fun onCarRaisedWifiDirect(name: String) {
        if (mode != 1) return
        carSeenAt = android.os.SystemClock.elapsedRealtime()
        app.prefs.carP2pName = name
        _state.update { it.copy(carWifi = name) }
        if (wifiLink?.connected == true) {
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
        val swap = app.prefs.swapTrackKeys
        val mapped = when (key) {
            15 -> if (swap) 87 else 88
            16 -> if (swap) 88 else 87
            14 -> 85
            231, 219 -> 84
            79 -> 85
            else -> key
        }
        val extra = when (mapped) {
            87 -> app.prefs.wheelLongNext
            88 -> app.prefs.wheelLongPrev
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
        val wanted = app.prefs.openOnConnect.trim()
        if (wanted.isEmpty()) return
        if (app.prefs.aaAutoStart && wanted == "aa") return
        scope.launch {
            delay(1500)
            DiagLog.i(tag, "opening $wanted because the car connected")
            when {
                wanted == "aa" -> startAndroidAuto()
                wanted == "maps" -> launch(app.prefs.mapsPackage)
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
        val p = app.prefs
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
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.productName?.toString()
        }.getOrNull()
        val route = if (CarPlayer.toCar || !app.prefs.soundOverBluetooth) "the CarLife sound channel" else "bluetooth"
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
        val hearsPhone = savedVolume < 0 && app.prefs.soundOverBluetooth
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

    private fun phoneMediaKey(am: android.media.AudioManager, key: Int) {
        runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        }.onFailure { DiagLog.w(tag, "could not pass ${keyName(key)} to the phone: ${it.message}") }
    }

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
            GearAction.MAPS -> launch(app.prefs.mapsPackage)
            GearAction.ANDROID_AUTO -> startAndroidAuto()
        }
    }

    private fun runWheelAction(action: Int) {
        when (action) {
            WheelAction.ANDROID_AUTO -> startAndroidAuto()
            WheelAction.CAR_HOME -> _screenKeys.tryEmit(3)
            WheelAction.MAPS -> launch(app.prefs.mapsPackage)
            WheelAction.PHONE_SCREEN -> phoneScreen()
            WheelAction.PLAY_PAUSE -> mediaKey(85)
            WheelAction.VOICE -> {
                val aa = AaHeadUnitService.current
                if (_state.value.aaOverlay && aa != null) aa.sendKey(84)
                else runCatching {
                    startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { DiagLog.w(tag, "no voice assistant to open: ${it.message}") }
            }
        }
    }

    private fun handleHardKey(key: Int, mapped: Int) {
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
        runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        }.onSuccess { DiagLog.i(tag, "steering wheel ${keyName(key)} sent to the app playing on the phone") }
            .onFailure { DiagLog.w(tag, "could not pass ${keyName(key)} to the phone: ${it.message}") }
    }

    private fun aaOverlay(on: Boolean) {
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

    private fun startAudioToCar() {
        if (audio.active) return
        if (carPlaysOurSound()) return
        if (!canRecord()) {
            DiagLog.w(tag, "no microphone permission, the car will get picture without sound")
            return
        }
        val mp = ensureProjection({ Lang.str(R.string.note_sound) }, quiet = true) ?: return
        if (audio.start(mp)) silencePhone()
    }

    private fun carPlaysOurSound(): Boolean {
        if (!app.prefs.soundOverBluetooth) return false
        btAudio.open()
        if (!btAudio.ready()) return false
        val playing = btAudio.somethingIsPlaying()
        if (playing != null) {
            val who = try {
                playing.name ?: playing.address
            } catch (_: SecurityException) {
                "car"
            }
            restorePhone()
            audio.stop()
            DiagLog.i(tag, "'$who' is already playing this phone's sound over bluetooth, so FT will not send it again")
            return true
        }
        val car = btAudio.theCar(app.prefs.carBtName, app.prefs.carBtAddress)
        if (car != null && btAudio.askCarToPlay(car)) {
            restorePhone()
            return true
        }
        DiagLog.i(tag, "nothing is taking this phone's sound over bluetooth, FT will stream it to the car instead")
        return false
    }

    private fun silencePhone() {
        if (!app.prefs.muteWhileProjecting || savedVolume >= 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val current = runCatching { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        savedVolume = current
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0) }
            .onSuccess { DiagLog.i(tag, "phone speaker silenced, sound plays on the car only (phone volume was $current)") }
            .onFailure { DiagLog.w(tag, "could not silence the phone speaker: ${it.message}"); savedVolume = -1 }
    }

    private fun restorePhone() {
        if (savedVolume < 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVolume, 0) }
        DiagLog.i(tag, "phone speaker back to $savedVolume")
        savedVolume = -1
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
            val max = runCatching { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(15)
            savedVolume = (savedVolume + if (up) 1 else -1).coerceIn(0, max)
            DiagLog.i(tag, "phone volume for when you disconnect: $savedVolume (use the car's own volume for the car)")
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
            val useCar = ownDisplay && app.prefs.carSizedApps && carDisplay.width > 0 && carDisplay.height > 0
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
            val d = if (useCar) app.prefs.carDensity else resources.displayMetrics.densityDpi
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
        stopAndroidAuto("you went back to FT")
    }

    private fun stopAndroidAuto(reason: String) {
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
        val overBluetooth = linked && app.prefs.soundOverBluetooth && runCatching {
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
        if (!app.prefs.carSongInfo || !CarPlayer.toCar) return
        val art = MediaLibrary.artBytes(this, t, 240)?.let { big ->
            if (big.size <= 24_000) big else MediaLibrary.artBytes(this, t, 160)
        }?.takeIf { it.size <= 30_000 } ?: placeholderCover
        val st = CarPlayer.state.value
        songInfoAt = android.os.SystemClock.elapsedRealtime()
        runCatching { s.sendSong(t.title, t.artist, t.album, art, t.durationMs, st.index, st.count, t.id.toString()) }
            .onFailure { DiagLog.w(tag, "could not tell the car the song: ${it.message}") }
    }

    private fun songPosition(st: CarPlayer.State) {
        val t = st.track ?: return
        if (t.video || !st.playing || !st.toCar || !app.prefs.carSongInfo) return
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
        if (app.prefs.soundOverBluetooth && btAudio.ready() && btAudio.somethingIsPlaying() != null) return
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
    @Volatile private var carSeenAt = 0L
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

    private fun usbAccessory(i: Intent): String {
        val a: android.hardware.usb.UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY, android.hardware.usb.UsbAccessory::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY)
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
        teardown()
        soundQueue.shutdown()
        runCatching { soundQueue.awaitTermination(SOUND_DRAIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        releaseWifi()
        _state.value = CarState()
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
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
