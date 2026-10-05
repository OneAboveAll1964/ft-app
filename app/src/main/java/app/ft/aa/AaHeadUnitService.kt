package app.ft.aa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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
        @Volatile var current: AaSession? = null
            private set
        @Volatile var micReady = false
            private set
        @Volatile var running = false
            private set

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

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "FT Android Auto", NotificationManager.IMPORTANCE_LOW))
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
                foreground("Android Auto head unit ready")
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
        if (acceptJob?.isActive == true) return
        val app = application as FTApp
        val port = app.prefs.aaPort
        val dec = AaVideoDecoder(app.prefs.aaWidth, app.prefs.aaHeight)
        decoder = dec
        surfaceJob = scope.launch {
            AaVideoSink.surface.collect { s -> dec.setSurface(s) }
        }
        if (bluetooth && bt == null) {
            DiagLog.i(tag, "advertising the Android Auto wireless service so it can find this head unit")
            bt = AaBluetoothAdvertiser(this, port).also { it.start(scope) }
            scope.launch { bt?.status?.collect { s -> _state.update { it.copy(bluetooth = s) } } }
            scope.launch { bt?.info?.collect { i -> _state.update { it.copy(hotspot = i) } } }
        }
        acceptJob = scope.launch {
            try {
                val ss = NetUtil.listen(port) { isActive } ?: return@launch
                server = ss
                _state.update { it.copy(listening = true, port = port) }
                DiagLog.i(tag, "head unit waiting for Android Auto on tcp:$port")
                while (isActive) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    DiagLog.i(tag, "Android Auto connected from ${s.inetAddress.hostAddress}")
                    onPhone(s, dec)
                }
            } catch (t: Throwable) {
                if (server != null) DiagLog.w(tag, "head unit port closed: ${t.message}")
                _state.update { it.copy(listening = false) }
            }
        }
        dialJob = scope.launch {
            var moaned = false
            while (isActive) {
                if (current == null) {
                    val s = runCatching {
                        Socket().apply {
                            tcpNoDelay = true
                            keepAlive = true
                            connect(InetSocketAddress("127.0.0.1", app.prefs.aaSelfPort), 1200)
                        }
                    }.getOrNull()
                    if (s != null) {
                        _state.update { it.copy(selfServer = true) }
                        DiagLog.i(tag, "Android Auto's head unit server is on, connected to it")
                        onPhone(s, dec)
                    } else {
                        if (_state.value.selfServer) _state.update { it.copy(selfServer = false) }
                        if (!moaned) {
                            DiagLog.d(tag, "Android Auto's head unit server is off; FT will connect as soon as it is switched on")
                            moaned = true
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    private fun onPhone(s: Socket, dec: AaVideoDecoder) {
        current?.close("replaced")
        val session = AaSession(this, s.getInputStream(), s.getOutputStream(), (application as FTApp).prefs, scope, dec) { reason ->
            DiagLog.i(tag, "phone session over: $reason")
            runCatching { s.close() }
            if (current != null) {
                current = null
                _state.update { it.copy(connected = false, phase = null, deviceName = "") }
                updateNotification("Android Auto head unit ready")
            }
        }
        current = session
        _state.update { it.copy(connected = true, phase = AaSession.Phase.CONNECTED) }
        scope.launch { session.phase.collect { p -> _state.update { it.copy(phase = p) }; if (p == AaSession.Phase.STREAMING) updateNotification("Android Auto streaming") } }
        scope.launch { session.deviceName.collect { n -> _state.update { it.copy(deviceName = n) } } }
        session.start()
    }

    private fun shutdown() {
        current?.close("service stopped")
        current = null
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        dialJob?.cancel()
        surfaceJob?.cancel()
        bt?.stop()
        bt = null
        decoder?.stop()
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
