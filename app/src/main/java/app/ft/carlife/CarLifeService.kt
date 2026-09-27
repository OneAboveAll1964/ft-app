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
import app.ft.R
import app.ft.FTApp
import app.ft.FTTouchService
import app.ft.MainActivity
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.core.CarAudioBus
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
    val beacon: Boolean = false,
    val step: String = ""
)

class CarLifeService : Service() {
    companion object {
        const val ACTION_WIFI = "app.ft.carlife.WIFI"
        const val ACTION_AUTO = "app.ft.carlife.AUTO"
        const val ACTION_STOP = "app.ft.carlife.STOP"
        const val ACTION_AUDIO = "app.ft.carlife.AUDIO"
        const val EXTRA_BT_ADDR = "btAddr"
        const val CORNER = 96
        private val AA_KEYS = setOf(3, 4, 19, 20, 21, 22, 23, 84, 85, 86, 87, 88, 126, 127)
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
    private val audio = AudioCapture { pcm -> CarAudioBus.write(CarAudioBus.LANE_PHONE, pcm) }
    private val outbound = java.util.concurrent.ArrayBlockingQueue<ByteArray>(24)
    @Volatile private var writer: Thread? = null
    private val carAudio: (ByteArray) -> Unit = { pcm ->
        startWriter()
        if (!outbound.offer(pcm)) {
            outbound.poll()
            outbound.offer(pcm)
        }
    }

    private fun startWriter() {
        if (writer?.isAlive == true) return
        writer = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val pcm = runCatching { outbound.take() }.getOrNull() ?: break
                session?.sendAudio(pcm)
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; name = "ft-car-audio-out"; start() }
    }
    @Volatile private var aaAutoLaunched = false
    @Volatile private var resumedMedia = false
    private var carNetwork: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var askedForShare = false
    private var savedVolume = -1
    private val app get() = application as FTApp
    private val beacon by lazy { CarBeacon(scope) { app.prefs.carName } }
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
            send = { bytes -> bt.write(bytes) },
            localIp = { NetUtil.localIpv4() },
            onCarWifiName = { name -> onCarRaisedWifiDirect(name) },
            progress = { text -> step(text) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CarAudioBus.mixTogether = app.prefs.mixGuidance
        CarAudioBus.sink = carAudio
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
                val direct = app.prefs.linkMode == 1
                foreground(if (direct) "Asking the car for WiFi Direct" else "Waiting for the car on this network")
                startWifiLink()
                beacon.onlyTarget = direct
                if (direct) {
                    startWirelessSetup()
                    bt.start(intent.getStringExtra(EXTRA_BT_ADDR))
                    ble.start(null)
                    wakeCarBluetooth()
                    startFinder()
                } else {
                    step("Waiting for the car to reach this phone")
                    _state.update { it.copy(p2p = "off, the car joins this phone instead") }
                }
            }
        }
        return START_STICKY
    }

    private fun foreground(text: String, projection: Boolean = false, microphone: Boolean = false) {
        val keepProjection = projection || this.projection != null
        val keepMicrophone = microphone || audio.active
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (keepProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (keepMicrophone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
        _state.update { it.copy(p2p = "waiting") }
        finderFallback = scope.launch {
            delay(25_000)
            if (wifiLink?.connected != true && finder == null) {
                step("The car has not reached FT, looking for its WiFi Direct group")
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
        s.onVoiceAudio = { pcm -> AaHeadUnitService.current?.feedCarMicrophone(pcm) }
        s.onClosed = { reason ->
            DiagLog.i(tag, "link closed: $reason")
            stopAndroidAuto("the car disconnected")
            updateBeacon()
        }
        stateJob?.cancel()
        stateJob = scope.launch {
            s.state.collect { st ->
                _state.update { it.copy(link = l.name, session = st) }
                updateBeacon()
                if (st !is CarLifeSession.State.Idle) stopFinder("car is on this network, WiFi Direct not needed")
                else if (app.prefs.linkMode == 1 && finder == null && finderFallback?.isActive != true) scheduleFinderFallback()
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
                if (s.connected && !_state.value.aaOverlay) {
                    DiagLog.i(tag, "Android Auto is projecting, showing it on the car")
                    shown = true
                    aaOverlay(true)
                } else if (!s.connected && shown) {
                    shown = false
                    stopAndroidAuto("Android Auto closed on the phone")
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

    private fun startWirelessSetup() {
        step("Looking for the car over bluetooth")
        wireless.reset()
        bt.onFrame = { frame -> wireless.feed(frame) }
        bt.onProbe = { wireless.reset(); wireless.hello() }
        bt.onStep = { text -> step(text) }
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
            val who = runCatching { car.name }.getOrNull() ?: car.address
            if (btAudio.somethingIsPlaying() != null) {
                DiagLog.i(tag, "'$who' is already connected over bluetooth")
                return@launch
            }
            step("Connecting to '$who' over bluetooth")
            if (btAudio.askCarToPlay(car)) DiagLog.i(tag, "asked '$who' to connect over bluetooth")
        }
    }

    private fun onCarOfferedNetwork(offer: CarWifiOffer) {
        DiagLog.i(tag, "car network '${offer.ssid}' at ${offer.ip}:${offer.port}")
        app.prefs.carP2pName = offer.ssid
        if (offer.ip.isNotBlank()) _state.update { it.copy(carIp = offer.ip) }
        step("Joining the car's network '${offer.ssid}'")
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
                step("On the car's network, waiting for the head unit")
                runCatching { cm.bindProcessToNetwork(network) }
            }

            override fun onUnavailable() {
                DiagLog.w(tag, "could not join the car's network '${offer.ssid}'")
                step("Could not join the car's network")
            }

            override fun onLost(network: android.net.Network) {
                DiagLog.i(tag, "left the car's network")
                runCatching { cm.bindProcessToNetwork(null) }
            }
        }
        carNetwork = cb
        runCatching { cm.requestNetwork(request, cb) }
            .onFailure { DiagLog.e(tag, "could not ask to join the car's network", it) }
    }

    private fun onCarRaisedWifiDirect(name: String) {
        app.prefs.carP2pName = name
        _state.update { it.copy(p2p = "car raised $name") }
        if (wifiLink?.connected == true) {
            DiagLog.i(tag, "already connected to the car, leaving WiFi Direct alone")
            return
        }
        step("Joining the car's WiFi Direct group '$name'")
        finder?.connectByName(name) ?: run {
            startFinder()
            scope.launch {
                delay(1200)
                finder?.connectByName(name)
            }
        }
    }

    private fun keyName(code: Int) = when (code) {
        87 -> "next track"
        88 -> "previous track"
        85 -> "play or pause"
        84 -> "voice"
        3 -> "home"
        4 -> "back"
        else -> "key $code"
    }

    private fun onHardKey(key: Int) {
        val st = _state.value
        val swap = app.prefs.swapTrackKeys
        val mapped = when (key) {
            15 -> if (swap) 87 else 88
            16 -> if (swap) 88 else 87
            14 -> 85
            231, 219 -> 84
            79 -> 85
            else -> key
        }
        when {
            st.aaOverlay -> {
                val aa = AaHeadUnitService.current
                when {
                    aa == null -> Unit
                    mapped in AA_KEYS -> {
                        DiagLog.i(tag, "steering wheel key $key sent to Android Auto as ${keyName(mapped)}")
                        aa.sendKey(mapped)
                    }
                    else -> DiagLog.w(tag, "steering wheel key $key has no Android Auto action yet")
                }
            }
            st.mirroring -> when (mapped) {
                4 -> FTTouchService.instance?.back()
                3 -> mirrorStop()
                else -> DiagLog.i(tag, "steering wheel key $key ignored while mirroring")
            }
            else -> Unit
        }
    }

    private fun aaOverlay(on: Boolean) {
        if (on) mirrorStop()
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
                    if (projection !== mp) {
                        DiagLog.d(tag, "an older screen share ended, the current one keeps running")
                        return
                    }
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
            val previous = projection
            projection = mp
            if (previous != null && previous !== mp) runCatching { previous.stop() }
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
        if (carPlaysOurSound()) return
        if (!canRecord()) {
            DiagLog.w(tag, "no microphone permission, the car will get picture without sound")
            return
        }
        val mp = ensureProjection("Sending sound to the car")
        if (mp == null) {
            askForScreenShare()
            return
        }
        if (audio.start(mp)) silencePhone()
    }

    private fun carPlaysOurSound(): Boolean {
        if (!app.prefs.audioOverBluetooth) return false
        btAudio.open()
        if (!btAudio.ready()) return false
        val playing = btAudio.somethingIsPlaying()
        if (playing != null) {
            val who = runCatching { playing.name }.getOrNull() ?: playing.address
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
        if (savedVolume >= 0) {
            val max = runCatching { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(15)
            savedVolume = (savedVolume + if (up) 1 else -1).coerceIn(0, max)
            DiagLog.i(tag, "phone volume for when you disconnect: $savedVolume (use the car's own volume for the car)")
            return
        }
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
        restorePhone()
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
        stopAndroidAuto("you went back to FT")
    }

    private fun stopAndroidAuto(reason: String) {
        val wasOn = _state.value.aaOverlay
        val wasRunning = AaHeadUnitService.current != null || AaHeadUnitService.state.value.listening
        if (!wasOn && !wasRunning) return
        aaWatch?.cancel()
        aaWatch = null
        aaAutoLaunched = false
        if (wasOn) aaOverlay(false)
        CarAudioBus.clear(CarAudioBus.LANE_MEDIA)
        CarAudioBus.clear(CarAudioBus.LANE_SPEECH)
        CarAudioBus.clear(CarAudioBus.LANE_SYSTEM)
        if (wasRunning) AaHeadUnitService.stop(this)
        DiagLog.i(tag, "Android Auto stopped because $reason")
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
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    override fun onDestroy() {
        teardown()
        scope.cancel()
        ble.stop()
        carNetwork?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        carNetwork = null
        writer?.interrupt()
        writer = null
        outbound.clear()
        btAudio.close()
        CarAudioBus.sink = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
