package app.ft.carlife

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CarLifeSession(
    private val context: Context,
    private val link: CarLifeLink,
    private val prefs: Prefs,
    private val scope: CoroutineScope
) {
    sealed class State {
        data object Idle : State()
        data class Linked(val via: String) : State()
        data class Negotiated(val via: String, val width: Int, val height: Int, val fps: Int) : State()
        data class Projecting(val via: String, val width: Int, val height: Int, val fps: Int) : State()
    }

    private val tag = "CarLife"
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    var onVideoConfig: ((width: Int, height: Int, fps: Int) -> Unit)? = null
    var onStartVideo: (() -> Unit)? = null
    var onStopVideo: (() -> Unit)? = null
    var onKeyFrameRequest: (() -> Unit)? = null
    var onFrameRate: ((fps: Int) -> Unit)? = null
    var onTouch: ((action: Int, x: Int, y: Int) -> Unit)? = null
    var onHardKey: ((keyCode: Int) -> Unit)? = null
    var onVoiceAudio: ((ByteArray) -> Unit)? = null
    private var voicePackets = 0L
    private var voiceBytes = 0L
    var onLaunchMode: ((mode: String) -> Unit)? = null
    var onClosed: ((reason: String) -> Unit)? = null

    @Volatile var width = 1280; private set
    @Volatile var height = 720; private set
    @Volatile var fps = 30; private set
    @Volatile var projecting = false; private set
    private var heartbeat: Job? = null
    private var encryptProbe: Job? = null
    private val crypto = CarLifeCrypto()
    @Volatile private var matched = false
    @Volatile private var initSeen = false
    private var frames = 0L

    fun start() {
        DiagLog.i(tag, "session start via ${link.name}")
        link.start(scope, ::onMessage, { DiagLog.i(tag, it) }, ::linked, ::closed)
    }

    fun stop() {
        projecting = false
        heartbeat?.cancel()
        encryptProbe?.cancel()
        crypto.reset()
        matched = false
        link.stop()
        _state.value = State.Idle
    }

    fun sendVideo(frame: ByteArray) {
        if (!projecting) return
        frames++
        if (frames <= 3 || frames % 300 == 0L) DiagLog.d(tag, "video frame #$frames ${frame.size} bytes${if (crypto.active) " (encrypted)" else ""}")
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_DATA, crypto.encryptOut(frame)))
    }

    fun sendAudio(pcm: ByteArray) {
        link.send(CarLifeProtocol.CH_MEDIA, CarLifeFraming.stream(CarLifeProtocol.MEDIA_DATA, pcm))
    }

    private fun cmd(serviceId: Int, payload: ByteArray = ByteArray(0)) {
        val inner = CarLifeFraming.cmd(serviceId, crypto.encryptOut(payload))
        DiagLog.tx(tag, CarLifeProtocol.name(serviceId), inner)
        link.send(CarLifeProtocol.CH_CMD, inner)
    }

    private fun linked() {
        DiagLog.i(tag, "head unit connected over ${link.name}")
        if (_state.value == State.Idle) _state.value = State.Linked(link.name)
    }

    private fun closed(reason: String) {
        DiagLog.w(tag, reason)
        projecting = false
        heartbeat?.cancel()
        encryptProbe?.cancel()
        crypto.reset()
        matched = false
        initSeen = false
        onStopVideo?.invoke()
        _state.value = State.Idle
        onClosed?.invoke(reason)
    }

    private fun onMessage(channel: Int, head: ByteArray, body: ByteArray) {
        when (channel) {
            CarLifeProtocol.CH_CMD -> {
                val c = CarLifeFraming.parseCmd(head, body)
                handleCmd(c.copy(payload = crypto.decryptIn(c.payload)))
            }
            CarLifeProtocol.CH_CTRL -> {
                val c = CarLifeFraming.parseCmd(head, body)
                handleCtrl(c.copy(payload = crypto.decryptIn(c.payload)))
            }
            CarLifeProtocol.CH_VIDEO -> {
                val s = CarLifeFraming.parseStream(head, body)
                DiagLog.d(tag, "video channel ${CarLifeProtocol.name(s.serviceId)} len=${s.payload.size}")
            }
            CarLifeProtocol.CH_VR -> {
                val s = CarLifeFraming.parseStream(head, body)
                if (s.payload.isNotEmpty()) {
                    voicePackets++
                    voiceBytes += s.payload.size
                    if (voicePackets == 1L || voicePackets % 50L == 0L) {
                        DiagLog.i(tag, "car microphone sending: $voicePackets packets, $voiceBytes bytes, service ${CarLifeProtocol.name(s.serviceId)}")
                    }
                    onVoiceAudio?.invoke(s.payload)
                } else {
                    DiagLog.i(tag, "voice channel ${CarLifeProtocol.name(s.serviceId)} (empty)")
                }
            }
            else -> {
                val sid = CarLifeFraming.serviceId(channel, head)
                DiagLog.i(tag, "${CarLifeProtocol.channelName(channel)} ${CarLifeProtocol.name(sid)} len=${body.size}")
            }
        }
    }

    private fun handleCmd(c: CarLifeFraming.Cmd) {
        DiagLog.rx(tag, CarLifeProtocol.name(c.serviceId), c.payload)
        when (c.serviceId) {
            CarLifeProtocol.CMD_HU_PROTOCOL_VERSION -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU protocol ${r.int(1)}.${r.int(2)}")
                cmd(CarLifeProtocol.CMD_PROTOCOL_VERSION_MATCH_STATUS, ProtoWriter().int32(1, 1).toByteArray())
                matched = true
                initSeen = false
                if (_state.value == State.Idle) _state.value = State.Linked(link.name)
                startHeartbeat()
                cmd(CarLifeProtocol.CMD_MD_FEATURE_CONFIG_REQUEST)
                armEncryptProbe()
            }
            CarLifeProtocol.CMD_HU_INFO -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU info: ${r.fields.keys.joinToString { k -> "$k=${r.string(k) ?: r.int(k)}" }}")
                cmd(CarLifeProtocol.CMD_MD_INFO, deviceInfo())
            }
            CarLifeProtocol.CMD_HU_FEATURE_CONFIG_RESPONSE -> {
                val r = ProtoReader(c.payload)
                val features = r.messages(2).associate { m -> (m.string(1) ?: "") to m.int(2) }
                DiagLog.i(tag, "HU features: " + features.entries.joinToString { "${it.key}=${it.value}" })
                when (features["CONTENT_ENCRYPTION"]) {
                    1 -> {
                        DiagLog.i(tag, "head unit requires content encryption")
                        startEncryption()
                    }
                    0 -> {
                        encryptProbe?.cancel()
                        DiagLog.i(tag, "content encryption off on this head unit")
                    }
                    else -> Unit
                }
            }
            CarLifeProtocol.CMD_HU_RSA_PUBLIC_KEY_RESPONSE -> {
                val pub = ProtoReader(c.payload).string(1) ?: ""
                val request = crypto.aesKeyRequest(pub)
                if (request == null) {
                    DiagLog.e(tag, "head unit RSA key unusable (${pub.length} chars), staying in plaintext")
                } else {
                    crypto.armIncoming()
                    cmd(CarLifeProtocol.CMD_MD_AES_KEY_SEND_REQUEST, ProtoWriter().string(1, request).toByteArray())
                    DiagLog.i(tag, "AES session key sent, wrapped with the head unit RSA key")
                }
            }
            CarLifeProtocol.CMD_HU_AES_REC_RESPONSE -> {
                crypto.enableOutgoing()
                cmd(CarLifeProtocol.CMD_MD_ENCRYPT_READY)
                DiagLog.i(tag, "content encryption on")
            }
            CarLifeProtocol.CMD_HU_BT_PAIR_INFO -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU bluetooth pair info status=${r.int(7, -1)} addr=${r.string(1)} name=${r.string(6)}")
                cmd(CarLifeProtocol.CMD_MD_BT_PAIR_INFO, btPairInfo(1))
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT -> {
                initSeen = true
                encryptProbe?.cancel()
                val r = ProtoReader(c.payload)
                var w = r.int(1, width)
                var h = r.int(2, height)
                val asked = r.int(3, 0)
                var f = if (asked > 0) asked else 30
                if (prefs.forceWidth > 0 && prefs.forceHeight > 0) { w = prefs.forceWidth; h = prefs.forceHeight }
                if (prefs.forceFps > 0) f = prefs.forceFps else if (prefs.minFps > 0) f = maxOf(f, prefs.minFps)
                width = w.coerceIn(320, 4096)
                height = h.coerceIn(240, 2160)
                fps = f.coerceIn(1, 60)
                DiagLog.i(tag, "video encoder init ${width}x${height}@$fps" + if (asked != fps) " (head unit asked $asked)" else "")
                cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE, c.payload)
                cmd(CarLifeProtocol.CMD_FOREGROUND)
                cmd(CarLifeProtocol.CMD_MODULE_STATUS, moduleStatus())
                _state.value = State.Negotiated(link.name, width, height, fps)
                onVideoConfig?.invoke(width, height, fps)
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_START -> {
                link.send(CarLifeProtocol.CH_MEDIA, CarLifeFraming.stream(CarLifeProtocol.MEDIA_INIT, ProtoWriter().int32(1, 48000).int32(2, 2).int32(3, 16).toByteArray()))
                projecting = true
                frames = 0
                _state.value = State.Projecting(link.name, width, height, fps)
                if (heartbeat?.isActive != true) startHeartbeat()
                onStartVideo?.invoke()
                onKeyFrameRequest?.invoke()
                DiagLog.i(tag, "projection started")
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE -> {
                projecting = false
                _state.value = State.Negotiated(link.name, width, height, fps)
                DiagLog.i(tag, "projection paused by HU")
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_RESET -> {
                onKeyFrameRequest?.invoke()
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val f = ProtoReader(c.payload).int(1, fps).coerceIn(1, 60)
                if (f != fps) {
                    fps = f
                    onFrameRate?.invoke(f)
                }
                cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, c.payload)
            }
            CarLifeProtocol.CMD_STATISTIC_INFO -> {
                cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESULT, ProtoWriter().bool(1, true).toByteArray())
            }
            CarLifeProtocol.CMD_HU_AUTHEN_REQUEST -> {
                val random = ProtoReader(c.payload).string(1) ?: ""
                DiagLog.w(tag, "HU authentication challenge random='$random' (replying best-effort)")
                cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESPONSE, ProtoWriter().string(1, random).toByteArray())
            }
            CarLifeProtocol.CMD_HU_AUTHEN_RESULT -> {
                DiagLog.i(tag, "HU authen result ${ProtoReader(c.payload).bool(1)}")
            }
            CarLifeProtocol.CMD_GO_TO_FOREGROUND -> {
                cmd(CarLifeProtocol.CMD_GO_TO_FOREGROUND_RESPONSE)
                cmd(CarLifeProtocol.CMD_FOREGROUND)
                onKeyFrameRequest?.invoke()
            }
            CarLifeProtocol.CMD_LAUNCH_MODE_NORMAL -> onLaunchMode?.invoke("normal")
            CarLifeProtocol.CMD_LAUNCH_MODE_PHONE -> onLaunchMode?.invoke("phone")
            CarLifeProtocol.CMD_LAUNCH_MODE_MAP -> onLaunchMode?.invoke("map")
            CarLifeProtocol.CMD_LAUNCH_MODE_MUSIC -> onLaunchMode?.invoke("music")
            CarLifeProtocol.CMD_MODULE_CONTROL, CarLifeProtocol.CMD_PAUSE_MEDIA, CarLifeProtocol.CMD_CAR_VELOCITY,
            CarLifeProtocol.CMD_CAR_GPS, CarLifeProtocol.CMD_CAR_GYROSCOPE, CarLifeProtocol.CMD_CAR_ACCELERATION,
            CarLifeProtocol.CMD_CAR_OIL, CarLifeProtocol.CMD_ERROR_CODE, CarLifeProtocol.CMD_BT_HFP_INDICATION,
            CarLifeProtocol.CMD_BT_HFP_CONNECTION, CarLifeProtocol.CMD_BT_HFP_RESPONSE, CarLifeProtocol.CMD_BT_HFP_STATUS_RESPONSE,
            CarLifeProtocol.CMD_BT_START_IDENTIFY_REQ, CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE,
            CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_START, CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_STOP,
            CarLifeProtocol.CMD_VIDEO_ENCODER_JPEG -> Unit
            else -> DiagLog.w(tag, "unhandled cmd ${CarLifeProtocol.name(c.serviceId)}")
        }
    }

    private fun handleCtrl(c: CarLifeFraming.Cmd) {
        when (c.serviceId) {
            CarLifeProtocol.TOUCH_ACTION -> {
                val r = ProtoReader(c.payload)
                val action = r.int(1, -1)
                val x = r.int(2, -1)
                val y = r.int(3, -1)
                if (action >= 0 && x >= 0 && y >= 0) onTouch?.invoke(action, x, y)
            }
            CarLifeProtocol.CAR_HARD_KEY_CODE -> {
                val r2 = ProtoReader(c.payload)
                val key = r2.int(1, -1)
                DiagLog.i(tag, "steering wheel key $key (payload ${c.payload.joinToString(" ") { b -> "%02X".format(b) }})")
                if (key >= 0) onHardKey?.invoke(key)
            }
            else -> DiagLog.rx(tag, "ctrl ${CarLifeProtocol.name(c.serviceId)}", c.payload)
        }
    }

    private fun startEncryption() {
        if (crypto.started) return
        crypto.started = true
        encryptProbe?.cancel()
        cmd(CarLifeProtocol.CMD_MD_RSA_PUBLIC_KEY_REQUEST)
    }

    private fun armEncryptProbe() {
        encryptProbe?.cancel()
        encryptProbe = scope.launch {
            delay(2500)
            if (matched && !initSeen && !crypto.started) {
                DiagLog.w(tag, "no VIDEO_ENCODER_INIT after the match, trying the content encryption handshake")
                startEncryption()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_HEARTBEAT, ByteArray(0)))
                delay(1000)
            }
        }
    }

    private fun btPairInfo(status: Int): ByteArray {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val address = runCatching { adapter?.address }.getOrNull().orEmpty()
        val name = runCatching { adapter?.name }.getOrNull().orEmpty().ifBlank { prefs.carName }
        return ProtoWriter()
            .string(1, address)
            .string(2, "")
            .string(5, "00001101-0000-1000-8000-00805F9B34FB")
            .string(6, name)
            .int32(7, status)
            .toByteArray()
    }

    private fun deviceInfo(): ByteArray {
        val abis = Build.SUPPORTED_ABIS
        val abi1 = abis.getOrNull(0) ?: "arm64-v8a"
        val abi2 = abis.getOrNull(1) ?: abi1
        val cid = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull() ?: "ft"
        return ProtoWriter()
            .string(1, "Android")
            .string(2, Build.BOARD)
            .string(3, Build.BOOTLOADER)
            .string(4, Build.BRAND)
            .string(5, abi1)
            .string(6, abi2)
            .string(7, Build.DEVICE)
            .string(8, Build.DISPLAY)
            .string(9, Build.FINGERPRINT)
            .string(10, Build.HARDWARE)
            .string(11, Build.HOST)
            .string(12, cid)
            .string(13, Build.MANUFACTURER)
            .string(14, Build.MODEL)
            .string(15, Build.PRODUCT)
            .string(16, "unknown")
            .string(17, Build.VERSION.CODENAME)
            .string(18, Build.VERSION.INCREMENTAL)
            .string(19, Build.VERSION.RELEASE)
            .string(20, Build.VERSION.SDK_INT.toString())
            .int32(21, Build.VERSION.SDK_INT)
            .string(22, prefs.carName)
            .toByteArray()
    }

    private fun moduleStatus(): ByteArray {
        val modules = listOf(
            CarLifeProtocol.MODULE_PHONE to 0,
            CarLifeProtocol.MODULE_NAVI to 0,
            CarLifeProtocol.MODULE_MUSIC to 0,
            CarLifeProtocol.MODULE_VR to 0,
            CarLifeProtocol.MODULE_MIC to 0,
            CarLifeProtocol.MODULE_CONNECT to 1
        )
        val w = ProtoWriter().int32(1, modules.size)
        for ((id, st) in modules) w.message(2, ProtoWriter().int32(1, id).int32(2, st))
        return w.toByteArray()
    }
}
