package app.ft.iccoa

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.ft.FTApp
import app.ft.MainActivity
import app.ft.R
import app.ft.aa.AaHeadUnitService
import app.ft.core.CarProtocol
import app.ft.core.DiagLog
import app.ft.core.ProtoWriter
import app.ft.core.VehicleTelemetry
import app.ft.core.VehicleLocation
import app.ft.core.NavigationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class IccoaState(
    val running: Boolean = false,
    val discovery: IccoaDiscoveryState = IccoaDiscoveryState(),
    val status: String = "",
    val pinRequired: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false
)

/** Independent ICCOA connection, control and WFD lifecycle. */
class IccoaService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = Any()
    private val rememberedCar by lazy { IccoaRememberedCar(this) }
    private var pendingCar: IccoaPeer? = null
    private var autoConnectAttempted = false
    private val dropRecovery = IccoaDropRecovery()
    private var recoveryScanScheduled = false
    private val recoveryScan = Runnable {
        recoveryScanScheduled = false
        if (dropRecovery.active && _state.value.running &&
            !_state.value.connecting && !_state.value.connected)
            discovery?.start()
    }
    private fun cancelRecoveryScan() {
        main.removeCallbacks(recoveryScan)
        recoveryScanScheduled = false
    }
    private fun scheduleRecoveryScan(number: Int) {
        if (recoveryScanScheduled) return
        val delayMs = when (number) { 1 -> 0L; 2 -> 3_000L; else -> 6_000L }
        recoveryScanScheduled = true
        DiagLog.i("ICCOA", "Remembered-car transport recovery attempt $number/${IccoaDropRecovery.MAX_ATTEMPTS}")
        main.postDelayed(recoveryScan, delayMs)
    }
    @Volatile private var generation = 0
    private var discovery: IccoaDiscovery? = null
    @Volatile private var session: IccoaSession? = null
    @Volatile private var media: IccoaMediaTransport? = null
    private var projection: IccoaProjection? = null
    private var microphone: IccoaMicrophone? = null
    private var microphoneHook: ((Boolean) -> Unit)? = null
    private var navigationHook: ((NavigationInfo) -> Unit)? = null
    private var latestNavigation = NavigationInfo()
    private var navigationRetries = 0
    // OEM SensorMgr is enabled only while Android Auto is connected or real navigation runs.
    // This is an experimentally verifiable activation hint, never a fabricated GPS fix.
    private var aaSensorAssist = false
    // OEM GPS is also needed for FT's own coordinate/course widgets.
    // Only enabled while an actual ICCOA video projection is established.
    private var ftProjectionSensorAssist = false
    private val navigationSend = object : Runnable {
        override fun run() {
            val connection = session ?: return
            val activation = IccoaNavigation.sensorActivation(latestNavigation, aaSensorAssist, ftProjectionSensorAssist)
            if (connection.sendControl(IccoaNavigation.CONTROL_METHOD,
                    IccoaNavigation.encode(activation))) {
                navigationRetries = 0
                DiagLog.i("ICCOA", "OEM sensor activation request: active=${activation.active} " +
                    "guided=${latestNavigation.active} aaConnected=$aaSensorAssist " +
                    "ftProjected=$ftProjectionSensorAssist")
            } else if (navigationRetries++ < 8) {
                main.postDelayed(this, 500)
            }
        }
    }

    private val microphoneRetry = object : Runnable {
        override fun run() {
            if (microphone?.flush() == true) main.postDelayed(this, 500)
        }
    }
    private var projecting = false
    private var foregroundMode: Boolean? = null
    private var lastVideoConfig: IccoaVideoConfig? = null
    private val audioPlayerState = IccoaAudioPlayerState()
    private val audioStateRetry = Runnable { flushAudioState() }
    private var gpsFrameCount = 0L
    private var gpsFixCount = 0L
    private var gearSpeedFrames = 0L
    private var gearSpeedValid = 0L
    private var lastCarGps: IccoaGpsFix? = null
    private fun onSensorMessage(method: Int, body: ByteArray) {
        if (method == IccoaGearWire.SENSOR_METHOD) {
            gearSpeedFrames++
            if (IccoaGearWire.accept(body)) gearSpeedValid++
            if (gearSpeedFrames <= 3 || gearSpeedFrames and (gearSpeedFrames - 1) == 0L) {
                DiagLog.i("ICCOA", "SENSOR GearInfo count=$gearSpeedFrames valid=$gearSpeedValid " +
                    "payloadBytes=${body.size} unit=unconfirmed " +
                    "rawGear=${VehicleTelemetry.current.value?.gearRaw ?: "NA"} " +
                    "rawSpeed=${VehicleTelemetry.current.value?.gearSpeedRaw ?: "NA"}")
            }
            return
        }
        if (method != IccoaGpsWire.GPS_METHOD) return
        gpsFrameCount++
        val fix = IccoaGpsWire.decode(body)
        if (fix != null) {
            gpsFixCount++
            lastCarGps = fix
            VehicleTelemetry.publish(VehicleLocation(
                source = "ICCOA", receivedAtMs = System.currentTimeMillis(),
                latitude = fix.latitude, longitude = fix.longitude,
                // OEM CarLinkSensorMgr reads Android Location.getLatitude/Longitude.
                // WGS84 is the Android API contract; confirm actual HU values on-road.
                coordinatesUnit = "ANDROID_LOCATION_WGS84",
                speedRaw = fix.speedRaw, headingRaw = fix.headingRaw,
                satellitesUsed = fix.satellitesUsed, satellitesVisible = fix.satellitesVisible,
                deviceTimestampRaw = fix.timestampRaw
            ))
        }
        if (gpsFrameCount <= 3 || gpsFrameCount and (gpsFrameCount - 1) == 0L) {
            DiagLog.i("ICCOA", "SENSOR GPS received=$gpsFrameCount valid=$gpsFixCount " +
                "payloadBytes=${body.size} fixPresent=${fix != null} " +
                "satellitesUsed=${fix?.satellitesUsed ?: -1} " +
                "satellitesVisible=${fix?.satellitesVisible ?: -1}")
        }
    }

    private var configScheduled = false
    private val requestConfig = object : Runnable {
        override fun run() {
            if (session?.sendControl(24, byteArrayOf()) == true) {
                DiagLog.i("ICCOA", "Car config request queued")
            } else if (session != null) main.postDelayed(this, 500)
        }
    }
    private val connectionTimeout = Runnable {
        if (_state.value.connecting) connectionFailed("Connection timed out. Open ICCOA on the car and try again.")
    }

    private var bluetoothCalls: app.ft.core.BluetoothCalls? = null
    private var bluetoothReconnect: app.ft.core.CarBluetoothReconnect? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ICCOA Carlink", NotificationManager.IMPORTANCE_LOW)
        )
        discovery = IccoaDiscovery(this) { result ->
            _state.update { it.copy(discovery = result) }
            maybeAutoConnect(result)
        }
        projection = IccoaProjection(this, ::promoteProjection, {
            session?.sendControl(5, byteArrayOf())
        }, { pcm -> media?.audio(pcm) }, onFailure = ::connectionFailed,
            onAaConnectionChanged = { connected ->
                main.post {
                    if (aaSensorAssist != connected) {
                        aaSensorAssist = connected
                        navigationRetries = 0
                        main.removeCallbacks(navigationSend)
                        DiagLog.i("ICCOA", "AA projection sensor assist changed: connected=$connected")
                        navigationSend.run()
                    }
                }
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = FTApp.instance.prefs
        if (intent?.action == STOP || prefs.carProtocol != CarProtocol.ICCOA || !prefs.autoConnect) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermission()) {
            _state.value = IccoaState(discovery = IccoaDiscoveryState(phase = IccoaPhase.PERMISSION_REQUIRED))
            stopSelf()
            return START_NOT_STICKY
        }
        try { foreground(projecting) } catch (e: SecurityException) {
            _state.value = IccoaState(status = "Android permission required: open FT and allow Nearby devices.",
                discovery = IccoaDiscoveryState(phase = IccoaPhase.PERMISSION_REQUIRED))
            stopSelf()
            return START_NOT_STICKY
        }
        if (bluetoothCalls == null) {
            bluetoothCalls = app.ft.core.BluetoothCalls(this).also { it.start() }
        }
        if (bluetoothReconnect == null) {
            bluetoothReconnect = app.ft.core.CarBluetoothReconnect(this, prefs).also { it.start() }
        }
        val wasRunning = _state.value.running
        _state.update { it.copy(running = true) }
        when (intent?.action) {
            CONNECT -> intent.getStringExtra("address")?.let { address ->
                cancelRecoveryScan()
                dropRecovery.onExplicitOwnerRequest()
                connectTo(address)
            }
            PIN -> intent.getStringExtra("pin")?.let { pin ->
                if (_state.value.pinRequired && pin.matches(Regex("[0-9]{6}"))) {
                    _state.update { it.copy(pinRequired = false, status = "Confirming the code with the car…") }
                    session?.submitPin(pin)
                }
            }
            DISCONNECT -> {
                cancelRecoveryScan()
                dropRecovery.onExplicitOwnerRequest()
                endConnection()
                _state.update { it.copy(status = "Disconnected") }
            }
            INSPECT -> if (!_state.value.connecting && !_state.value.connected) intent.getStringExtra("address")?.let { discovery?.inspect(it) }
            SCAN -> {
                cancelRecoveryScan()
                dropRecovery.onExplicitOwnerRequest()
                endConnection()
                autoConnectAttempted = false
                _state.update { it.copy(status = "") }
                discovery?.start()
            }
            else -> if (!wasRunning) discovery?.start()
        }
        return START_NOT_STICKY
    }

    private fun maybeAutoConnect(result: IccoaDiscoveryState) {
        val prefs = FTApp.instance.prefs
        if (!prefs.autoConnect || prefs.carProtocol != CarProtocol.ICCOA ||
            autoConnectAttempted || _state.value.connecting || _state.value.connected) return
        // Failed discovery consumes only a bounded transport-recovery slot.
        if (result.phase == IccoaPhase.FAILED) {
            if (dropRecovery.active && !recoveryScanScheduled) {
                val next = dropRecovery.onFailure(false, true, true)
                if (next != null) scheduleRecoveryScan(next)
                else DiagLog.w("ICCOA", "BLE discovery exhausted transport recovery")
            }
            return
        }
        if (result.phase != IccoaPhase.SCANNING &&
            result.phase != IccoaPhase.SCAN_COMPLETE) return
        val car = rememberedCar.select(result.peers, result.phase == IccoaPhase.SCAN_COMPLETE)
        if (car == null) {
            if (dropRecovery.active && result.phase == IccoaPhase.SCAN_COMPLETE &&
                !recoveryScanScheduled) {
                val next = dropRecovery.onFailure(false, true, true)
                if (next != null) scheduleRecoveryScan(next)
                else DiagLog.w("ICCOA", "Remembered car not found; retry budget exhausted")
            }
            return
        }
        autoConnectAttempted = true
        val attempt = dropRecovery.onInitialRememberedCarAutoSelect()
        DiagLog.i("ICCOA", "Auto-select remembered vehicle attempt $attempt/${IccoaDropRecovery.MAX_ATTEMPTS}")
        connectTo(car.address)
    }

    private fun connectTo(address: String) {
        pendingCar = _state.value.discovery.peers.singleOrNull { it.address.equals(address, ignoreCase = true) }
        val known = rememberedCar.matches(pendingCar, _state.value.discovery.peers)
        val trustedCarName = pendingCar?.name?.trim().orEmpty()
        endConnection()
        // Mark connecting before stopping discovery: stop can publish SCAN_COMPLETE.
        _state.update { it.copy(connecting = true, status = "Connecting to the car…") }
        discovery?.stop()
        val token = generation
        main.postDelayed(connectionTimeout, 180_000)
        fun current(action: () -> Unit) { main.post { if (generation == token) action() } }
        lateinit var connection: IccoaSession
        val carMicrophone = IccoaMicrophone(
            sendControl = { method, body -> connection.sendControl(method, body) },
            onPcm = { pcm -> if (generation == token) AaHeadUnitService.feedCarMicrophone(pcm) },
            log = { DiagLog.i("ICCOA", it) },
            phoneStateBody = ::phoneState,
            gain = { FTApp.instance.prefs.iccoaMicGain })
        microphone = carMicrophone
        val hook: (Boolean) -> Unit = { wanted ->
            carMicrophone.setWanted(wanted)
            main.post {
                if (generation == token) {
                    main.removeCallbacks(microphoneRetry)
                    microphoneRetry.run()
                }
            }
        }
        val navHook: (NavigationInfo) -> Unit = { info ->
            main.post {
                if (generation == token) {
                    latestNavigation = info
                    navigationRetries = 0
                    main.removeCallbacks(navigationSend)
                    navigationSend.run()
                }
            }
        }
        connection = IccoaSession(this,
            onStatus = { text -> current { _state.update { it.copy(status = text) } } },
            onPinRequired = { current { _state.update { it.copy(pinRequired = true, status = "Enter the six-digit code shown by the car") } } },
            onReady = { network, peer ->
                // AuthConfirm must only advertise our address after these listeners are bound.
                synchronized(lifecycle) {
                    check(generation == token) { "Connection cancelled" }
                    val transport = IccoaMediaTransport(network, peer,
                        decryptInput = { session?.decryptInput(it) ?: error("Session ended") },
                        onPlay = { config -> current { play(config) } },
                        onPause = { current { pauseProjection() } },
                        onKeyframe = { current { projection?.requestKeyFrame() } },
                        onTouch = { touch -> current { projection?.touch(touch) } },
                        onKey = { code -> current { projection?.key(code) } },
                        onClosed = { reason -> current { connectionFailed(reason) } })
                    transport.start()
                    media = transport
                }
                current {
                    _state.update { it.copy(pinRequired = false, status = "Paired. Waiting for the car to start projection…") }
                    navigationRetries = 0
                    navigationSend.run()
                }
            },
            onMessage = { category, method, body ->
                if (category == 4) {
                    if (generation == token) carMicrophone.receive(method, body)
                } else current {
                    if (category == IccoaGpsWire.SENSOR_CATEGORY) onSensorMessage(method, body)
                    else control(category, method, body)
                }
            },
            onClosed = { reason -> current { connectionFailed(reason) } },
            onControlReady = { current {
                navigationRetries = 0
                main.removeCallbacks(navigationSend)
                navigationSend.run()
            } },
            trustedCarName = trustedCarName,
            tryRememberedPin = known)
        session = connection
        main.postDelayed({
            if (generation == token && session === connection && gearSpeedFrames > 0L && gpsFrameCount == 0L)
                DiagLog.w("ICCOA", "Sensor lane alive (GearInfo=$gearSpeedFrames), but zero OEM GPS frames; " +
                    "real guided navigation may be required to activate the car's GPS producer")
        }, 20_000L)
        // Publish only after the captured session has been initialized: AA callbacks
        // arrive on its reader thread and can run as soon as the hook is visible.
        microphoneHook = hook
        navigationHook = navHook
        AaHeadUnitService.onCarMicWanted = hook
        AaHeadUnitService.onNavigation = navHook
        runCatching { connection.start(address) }.onFailure { connectionFailed(it.message ?: "Connection failed") }
    }

    private fun play(config: IccoaVideoConfig) {
        lastVideoConfig = config
        val plan = IccoaVideoPlan.from(config)
        val output = media ?: return
        DiagLog.i("ICCOA", "video profile ${plan.streamWidth}x${plan.streamHeight}@${plan.fps} CBR ${plan.bitrate / 1000} kbps; receiver asked ${config.fps} fps")
        projection?.start(plan, output::configure, output::frame)
        if (media !== output) return // A synchronous projection failure has already ended this connection.
        if (!ftProjectionSensorAssist) {
            ftProjectionSensorAssist = true
            navigationRetries = 0
            main.removeCallbacks(navigationSend)
            DiagLog.i("ICCOA", "FT projection ready; requesting OEM GPS for coordinate widgets")
            navigationSend.run()
        }
        main.removeCallbacks(connectionTimeout)
        output.resume()
        audioPlayerState.play()
        flushAudioState()
        if (!configScheduled) {
            configScheduled = true
            main.postDelayed(requestConfig, 3_000)
        }
        pendingCar?.let { car ->
            if (rememberedCar.remember(car)) DiagLog.i("ICCOA", "Authenticated vehicle selection remembered for next connection")
        }
        _state.update { it.copy(connecting = false, connected = true, pinRequired = false,
            status = "ICCOA linked · awaiting H.264 video · ${plan.streamWidth}×${plan.streamHeight} · ${plan.fps} fps") }
    }

    /** Reference 7.62 phone state: no experimental call-state field is advertised. */
    private fun phoneState(voice: Boolean): ByteArray {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val battery = getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val thermal = getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        return ProtoWriter().bool(2, locked).bool(4, voice)
            .bool(5, capacity in 0..15 && !charging)
            .bool(6, thermal >= PowerManager.THERMAL_STATUS_SEVERE).toByteArray()
    }

    private fun control(category: Int, method: Int, body: ByteArray) {
        if (category != 1) return
        when (method) {
            IccoaPortNegotiation.REQUEST -> {
                IccoaPortNegotiation.responseFor(body)?.let { response ->
                    if (session?.sendControl(IccoaPortNegotiation.RESPONSE, response) == true)
                        DiagLog.i("ICCOA", "OEM SENSOR GetPortRequest answered with bound listener 57239")
                }
            }
            4 -> lastVideoConfig?.let(::play)
            5, 41 -> pauseProjection()
            16 -> projection?.key(84) // Same AA voice/search key as the CarLife voice button.
            23 -> connectionFailed("The car disconnected")
            25 -> runCatching { IccoaCarConfig.describe(body) }
                .onSuccess { DiagLog.i("ICCOA", "Car config: $it") }
                .onFailure { DiagLog.w("ICCOA", "Car config unreadable: bytes=${body.size} ${it.javaClass.simpleName}") }
            26 -> session?.sendControl(27, byteArrayOf())
        }
    }

    private fun pauseProjection() {
        // The car can hide its projection surface without ending capture or Android Auto.
        media?.pause()
        audioPlayerState.pause()
        flushAudioState()
        _state.update { it.copy(status = "Car paused projection") }
    }

    private fun flushAudioState() {
        main.removeCallbacks(audioStateRetry)
        audioPlayerState.flush { session?.sendControl(8, it) == true }?.let {
            DiagLog.i("ICCOA", "AAC player state $it queued (48 kHz stereo)")
        }
        if (audioPlayerState.pending && session != null) main.postDelayed(audioStateRetry, 500)
    }

    private fun connectionFailed(reason: String) {
        DiagLog.w("ICCOA", reason)
        endConnection()
        _state.update { it.copy(status = "$reason. Обновляю список устройств для новой попытки") }
        // XA60 changes its private BLE address after a failed handshake. Reusing the old
        // card times out without GATT callbacks. Clear stale peers and discover afresh.
        if (_state.value.running) discovery?.start()
    }

    private fun endConnection() {
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(audioStateRetry)
        main.removeCallbacks(requestConfig)
        main.removeCallbacks(microphoneRetry)
        main.removeCallbacks(navigationSend)
        if (AaHeadUnitService.onNavigation === navigationHook) AaHeadUnitService.onNavigation = null
        navigationHook = null
        latestNavigation = NavigationInfo()
        aaSensorAssist = false
        ftProjectionSensorAssist = false
        navigationRetries = 0
        if (AaHeadUnitService.onCarMicWanted === microphoneHook) AaHeadUnitService.onCarMicWanted = null
        microphoneHook = null
        microphone?.close()
        microphone = null
        audioPlayerState.reset()
        configScheduled = false
        synchronized(lifecycle) {
            generation++
            media?.close(); media = null
            session?.stop(); session = null
        }
        projection?.stop()
        lastVideoConfig = null
        lastCarGps = null
        VehicleTelemetry.clear("ICCOA")
        _state.update { it.copy(pinRequired = false, connecting = false, connected = false) }
    }

    private fun foreground(withProjection: Boolean) {
        if (foregroundMode == withProjection) return
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_ft)
            .setContentTitle("FT · ICCOA Carlink").setContentText("Experimental wireless projection")
            .setContentIntent(launch).setOngoing(true).build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION, note, type)
        foregroundMode = withProjection
    }

    private fun promoteProjection() { foreground(true); projecting = true }
    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroy() {
        bluetoothReconnect?.close(); bluetoothReconnect = null
        bluetoothCalls?.close(); bluetoothCalls = null
        discovery?.stop(); discovery = null
        endConnection()
        projection?.close(); projection = null
        _state.update { it.copy(running = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundMode = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "iccoa_projection"
        private const val NOTIFICATION = 43
        private const val START = "app.ft.iccoa.START"
        private const val STOP = "app.ft.iccoa.STOP"
        private const val SCAN = "app.ft.iccoa.SCAN"
        private const val INSPECT = "app.ft.iccoa.INSPECT"
        private const val CONNECT = "app.ft.iccoa.CONNECT"
        private const val PIN = "app.ft.iccoa.PIN"
        private const val DISCONNECT = "app.ft.iccoa.DISCONNECT"
        private val _state = MutableStateFlow(IccoaState())
        val state: StateFlow<IccoaState> = _state
        private var instance: IccoaService? = null
        fun start(context: Context) = send(context, START)
        fun scanAgain(context: Context) = send(context, SCAN)
        fun inspect(context: Context, address: String) = send(context, INSPECT, address = address)
        fun connect(context: Context, address: String) = send(context, CONNECT, address = address)
        fun submitPin(context: Context, pin: String) = send(context, PIN, pin = pin)
        fun disconnect(context: Context) = send(context, DISCONNECT)
        fun calibrateTouch() { if (_state.value.connected) instance?.projection?.startTouchCalibration() }
        fun resetTouchCalibration() { if (_state.value.connected) instance?.projection?.resetTouchCalibration() }
        fun stop(context: Context) { if (instance != null) send(context, STOP) }
        private fun send(context: Context, action: String, address: String? = null, pin: String? = null) {
            val intent = Intent(context, IccoaService::class.java).setAction(action)
                .putExtra("address", address).putExtra("pin", pin)
            runCatching {
                if (action == STOP) context.startService(intent) else context.startForegroundService(intent)
            }.onFailure { DiagLog.w("ICCOA", "Android refused $action: ${it.javaClass.simpleName}; open FT and try again") }
        }
    }
}
