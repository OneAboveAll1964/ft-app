package app.ft.carlife

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
    var onTouch: ((action: Int, x: Int, y: Int) -> Unit)? = null
    var onHardKey: ((keyCode: Int) -> Unit)? = null
    var onLaunchMode: ((mode: String) -> Unit)? = null
    var onClosed: ((reason: String) -> Unit)? = null

    @Volatile var width = 1280; private set
    @Volatile var height = 720; private set
    @Volatile var fps = 30; private set
    @Volatile var projecting = false; private set
    private var heartbeat: Job? = null
    private var frames = 0L

    fun start() {
        DiagLog.i(tag, "session start via ${link.name}")
        link.start(scope, ::onMessage, { DiagLog.i(tag, it); if (_state.value == State.Idle) _state.value = State.Linked(link.name) }, ::closed)
    }

    fun stop() {
        projecting = false
        heartbeat?.cancel()
        link.stop()
        _state.value = State.Idle
    }

    fun sendVideo(frame: ByteArray) {
        if (!projecting) return
        frames++
        if (frames <= 3 || frames % 300 == 0L) DiagLog.d(tag, "video frame #$frames ${frame.size} bytes")
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_DATA, frame))
    }

    fun sendAudio(pcm: ByteArray) {
        link.send(CarLifeProtocol.CH_MEDIA, CarLifeFraming.stream(CarLifeProtocol.MEDIA_DATA, pcm))
    }

    private fun cmd(serviceId: Int, payload: ByteArray = ByteArray(0)) {
        val inner = CarLifeFraming.cmd(serviceId, payload)
        DiagLog.tx(tag, CarLifeProtocol.name(serviceId), inner)
        link.send(CarLifeProtocol.CH_CMD, inner)
    }

    private fun closed(reason: String) {
        DiagLog.w(tag, reason)
        projecting = false
        heartbeat?.cancel()
        onStopVideo?.invoke()
        _state.value = State.Idle
        onClosed?.invoke(reason)
    }

    private fun onMessage(channel: Int, head: ByteArray, body: ByteArray) {
        when (channel) {
            CarLifeProtocol.CH_CMD -> handleCmd(CarLifeFraming.parseCmd(head, body))
            CarLifeProtocol.CH_CTRL -> handleCtrl(CarLifeFraming.parseCmd(head, body))
            CarLifeProtocol.CH_VIDEO -> {
                val s = CarLifeFraming.parseStream(head, body)
                DiagLog.d(tag, "video channel ${CarLifeProtocol.name(s.serviceId)} len=${s.payload.size}")
            }
            else -> {
                val sid = CarLifeFraming.serviceId(channel, head)
                DiagLog.d(tag, "${CarLifeProtocol.channelName(channel)} ${CarLifeProtocol.name(sid)} len=${body.size}")
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
                _state.value = State.Linked(link.name)
            }
            CarLifeProtocol.CMD_HU_INFO -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU info: ${r.fields.keys.joinToString { k -> "$k=${r.string(k) ?: r.int(k)}" }}")
                cmd(CarLifeProtocol.CMD_MD_INFO, deviceInfo())
            }
            CarLifeProtocol.CMD_HU_BT_PAIR_INFO -> {
                cmd(CarLifeProtocol.CMD_MD_BT_PAIR_INFO, ProtoWriter().string(1, "").string(2, "FT").toByteArray())
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT -> {
                val r = ProtoReader(c.payload)
                var w = r.int(1, width)
                var h = r.int(2, height)
                var f = r.int(3, fps)
                if (prefs.forceWidth > 0 && prefs.forceHeight > 0) { w = prefs.forceWidth; h = prefs.forceHeight }
                if (prefs.forceFps > 0) f = prefs.forceFps
                width = w.coerceIn(320, 4096)
                height = h.coerceIn(240, 2160)
                fps = f.coerceIn(10, 60)
                DiagLog.i(tag, "video encoder init ${width}x${height}@$fps")
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
                startHeartbeat()
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
                val f = ProtoReader(c.payload).int(1, fps)
                fps = f.coerceIn(10, 60)
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
            CarLifeProtocol.CMD_HU_FEATURE_CONFIG_RESPONSE -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU features: " + r.messages(2).joinToString { m -> "${m.string(1)}=${m.int(2)}" })
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
                val key = ProtoReader(c.payload).int(1, -1)
                DiagLog.i(tag, "hard key $key")
                if (key >= 0) onHardKey?.invoke(key)
            }
            else -> DiagLog.rx(tag, "ctrl ${CarLifeProtocol.name(c.serviceId)}", c.payload)
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (isActive && projecting) {
                delay(1000)
                link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_HEARTBEAT, ByteArray(0)))
            }
        }
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
