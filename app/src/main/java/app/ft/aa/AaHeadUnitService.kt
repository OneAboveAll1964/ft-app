package app.ft.aa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import app.ft.R
import app.ft.FTApp
import app.ft.MainActivity
import app.ft.carlife.NetUtil
import app.ft.core.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import app.ft.core.Lang

data class AaState(
    val listening: Boolean = false,
    val port: Int = 0,
    val connected: Boolean = false,
    val phase: AaSession.Phase? = null,
    val deviceName: String = "",
    val bluetooth: String = "idle",
    val selfServer: Boolean = false,
    val hotspot: HotspotInfo? = null
)

class AaHeadUnitService : Service() {
    companion object {
        const val ACTION_START = "app.ft.aa.START"
        const val ACTION_STOP = "app.ft.aa.STOP"
        const val EXTRA_BLUETOOTH = "bluetooth"
        private const val CHANNEL = "ft_aa"
        private const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow(AaState())
        val state: StateFlow<AaState> = _state
        private val disconnectHelp = AaDisconnectHelp()
        val disconnectHelpPending: StateFlow<Boolean> = disconnectHelp.pending
        fun dismissDisconnectHelp() = disconnectHelp.dismiss()
        @Volatile var current: AaSession? = null
            private set
        @Volatile var micReady = false
            private set
        @Volatile var running = false
            private set
        @Volatile var lastDisconnect = "none"
            private set

        @Volatile var onCarMicWanted: ((Boolean) -> Unit)? = null
        @Volatile var onNavigation: ((app.ft.core.NavigationInfo) -> Unit)? = null

        fun start(context: Context, bluetooth: Boolean) {
            if (!FTApp.instance.prefs.autoConnect) {
                DiagLog.i("AA", "FT is switched off, Android Auto is not started")
                return
            }
            runCatching { context.startForegroundService(Intent(context, AaHeadUnitService::class.java).setAction(ACTION_START).putExtra(EXTRA_BLUETOOTH, bluetooth)) }
                .onFailure { DiagLog.w("AA", "Android would not let FT start Android Auto right now (${it.javaClass.simpleName})") }
        }

        fun feedCarMicrophone(pcm: ByteArray) {
            current?.feedCarMicrophone(pcm)
        }

        fun stop(context: Context) {
            disconnectHelp.stop()
            runCatching { context.startService(Intent(context, AaHeadUnitService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private val tag = "AA"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var dialJob: Job? = null
    private var surfaceJob: Job? = null
    private var bt: AaBluetoothAdvertiser? = null
    private var decoder: AaVideoDecoder? = null
    private val connections = AaConnectionOwner<AaSession>()
    private val reconnect = AaReconnect()
    private var sessionId = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, Lang.str(R.string.channel_aa), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                foreground(Lang.str(R.string.note_aa_ready))
                listen(intent.getBooleanExtra(EXTRA_BLUETOOTH, false))
            }
        }
        return START_STICKY
    }

    private fun foreground(text: String) {
        val note = notification(text)
        val canRecord = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canRecord) {
            val withMic = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (runCatching { startForeground(NOTIFICATION_ID, note, withMic) }.isSuccess) {
                micReady = true
                return
            }
        }
        micReady = false
        runCatching { startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            .onFailure { DiagLog.w(tag, "could not keep the head unit in the foreground: ${it.message}") }
    }

    private fun listen(bluetooth: Boolean) {
        if (decoder != null) return
        val app = application as FTApp
        val port = app.prefs.aaPort
        val dec = AaVideoDecoder(app.prefs.aaWidth, app.prefs.aaHeight)
        decoder = dec
        val run = connections.start()
        disconnectHelp.start()
        surfaceJob = scope.launch {
            AaVideoSink.surface.collect { s -> dec.setSurface(s) }
        }
        if (bluetooth && bt == null) {
            DiagLog.i(tag, "advertising the Android Auto wireless service so it can find this head unit")
            val advertiser = AaBluetoothAdvertiser(this, port).also { it.start(scope) }
            bt = advertiser
            scope.launch { advertiser.status.collect { s -> connections.withRun(run) { _state.update { it.copy(bluetooth = s) } } } }
            scope.launch { advertiser.info.collect { i -> connections.withRun(run) { _state.update { it.copy(hotspot = i) } } } }
        }
        acceptJob = scope.launch {
            try {
                val ss = NetUtil.listen(port) { isActive } ?: return@launch
                if (!connections.withRun(run) {
                    server = ss
                    _state.update { it.copy(listening = true, port = port) }
                }) {
                    ss.close()
                    return@launch
                }
                DiagLog.i(tag, "head unit waiting for Android Auto on tcp:$port")
                while (isActive) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    DiagLog.i(tag, "Android Auto connected from ${s.inetAddress.hostAddress}")
                    val token = connections.idleGeneration(run)
                    if (token == null) s.close() else onPhone(s, dec, token, false)
                }
            } catch (t: Throwable) {
                connections.withRun(run) {
                    if (server != null) DiagLog.w(tag, "head unit port closed: ${t.message}")
                    _state.update { it.copy(listening = false) }
                }
            }
        }
        dialJob = scope.launch {
            var moaned = false
            reconnect.run {
                val token = connections.idleGeneration(run)
                if (token != null) {
                    val candidate = Socket()
                    val s = runCatching {
                        candidate.apply {
                            tcpNoDelay = true
                            keepAlive = true
                            connect(InetSocketAddress("127.0.0.1", app.prefs.aaSelfPort), 1200)
                        }
                    }.getOrElse { runCatching { candidate.close() }; null }
                    if (s != null) {
                        if (onPhone(s, dec, token, true)) moaned = false
                    } else {
                        AaKeep.poke(this@AaHeadUnitService)
                        if (!moaned) {
                            DiagLog.d(tag, "Android Auto's head unit server is off; FT will connect as soon as it is switched on")
                            moaned = true
                        }
                    }
                    s != null
                } else {
                    true
                }
            }
        }
    }

    private fun onPhone(s: Socket, dec: AaVideoDecoder, token: Long, selfServer: Boolean): Boolean {
        val accepted = runCatching {
            connections.attach(token, create = {
                val id = ++sessionId
                val since = SystemClock.elapsedRealtime()
                val source = if (selfServer) "developer-server:${s.port}" else "inbound:${s.inetAddress.hostAddress}"
                val sessionJob = SupervisorJob(scope.coroutineContext[Job])
                val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
                lateinit var session: AaSession
                session = AaSession(this, s.getInputStream(), s.getOutputStream(), (application as FTApp).prefs, sessionScope, dec) { reason ->
                    val details = "#$id $source ${SystemClock.elapsedRealtime() - since}ms: $reason; ${phoneState()}"
                    DiagLog.i(tag, "phone session over: $details")
                    runCatching { s.close() }
                    sessionJob.cancel()
                    connections.release(session) { enabled ->
                        disconnectHelp.closed(session.hasStreamed, reason, enabled && FTApp.instance.prefs.autoConnect, session.remoteBye)
                        lastDisconnect = "${java.util.Date()} $details"
                        current = null
                        _state.update { it.copy(connected = false, phase = null, deviceName = "", selfServer = false) }
                        if (enabled) {
                            updateNotification(Lang.str(R.string.note_aa_ready))
                            reconnect.wake()
                        }
                    }
                }
                sessionScope.launch {
                    session.phase.collect { p ->
                        connections.withCurrent(session) {
                            _state.update { it.copy(phase = p) }
                            if (p == AaSession.Phase.STREAMING) {
                                DiagLog.i(tag, "session #$id streaming after ${SystemClock.elapsedRealtime() - since}ms ($source)")
                                updateNotification(Lang.str(R.string.note_aa_streaming))
                            }
                        }
                    }
                }
                sessionScope.launch {
                    session.deviceName.collect { n -> connections.withCurrent(session) { _state.update { it.copy(deviceName = n) } } }
                }
                DiagLog.i(tag, "session #$id connected ($source); ${phoneState()}")
                session
            }) { session ->
                current = session
                _state.update { it.copy(connected = true, phase = AaSession.Phase.CONNECTED, deviceName = "", selfServer = selfServer) }
                session.start()
            }
        }.getOrElse {
            DiagLog.w(tag, "could not attach Android Auto: ${it.javaClass.simpleName} ${it.message}")
            false
        }
        if (!accepted) {
            DiagLog.d(tag, "discarded a redundant or stopped Android Auto connection")
            runCatching { s.close() }
        }
        return accepted
    }

    private fun phoneState(): String = runCatching {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val usb = registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        "interactive=${power.isInteractive} keyguard=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} " +
            "usbConnected=${usb?.getBooleanExtra("connected", false)} usbConfigured=${usb?.getBooleanExtra("configured", false)} usbUnlocked=${usb?.getBooleanExtra("unlocked", false)}"
    }.getOrElse { "phone state unavailable: ${it.javaClass.simpleName}" }

    private fun shutdown() {
        disconnectHelp.stop()
        val closing = connections.stop()
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        dialJob?.cancel()
        surfaceJob?.cancel()
        closing?.close("service stopped")
        bt?.stop()
        bt = null
        decoder?.reset()
        decoder = null
        _state.value = AaState()
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
        shutdown()
        scope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
