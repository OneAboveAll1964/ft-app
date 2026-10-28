package app.ft.carlife

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import app.ft.projection.VideoPlan
import app.ft.projection.VideoPlans
import app.ft.projection.VideoProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CarTouch(val action: Int, val x: Int, val y: Int, val x2: Int = -1, val y2: Int = -1, val index: Int = 0) {
    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}

class CarLifeSession(
    private val context: Context,
    private val link: CarLifeLink,
    private val prefs: Prefs,
    private val scope: CoroutineScope
) {
    sealed class State {
        data object Idle : State()
        data class Linked(val via: String) : State()
        data class Negotiated(val via: String, val width: Int, val height: Int, val fps: Int, val streamWidth: Int = width, val streamHeight: Int = height) : State()
        data class Projecting(val via: String, val width: Int, val height: Int, val fps: Int, val streamWidth: Int = width, val streamHeight: Int = height) : State()
    }

    private val tag = "CarLife"
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    var onVideoConfig: ((VideoPlan) -> Unit)? = null
    var onStopVideo: (() -> Unit)? = null
    var onFrameRate: ((fps: Int) -> Unit)? = null
    var onNeedKeyframe: (() -> Unit)? = null
    var onVideoBack: (() -> Unit)? = null
    var onGear: ((gear: Int) -> Unit)? = null
    var onTouch: ((CarTouch) -> Unit)? = null
    var onHardKey: ((keyCode: Int) -> Unit)? = null
    var onVoiceAudio: ((ByteArray) -> Unit)? = null
    var onLaunchMode: ((mode: String) -> Unit)? = null
    var onClosed: ((reason: String) -> Unit)? = null
    var onPauseMedia: (() -> Unit)? = null
    var onMusicControl: ((play: Boolean) -> Unit)? = null
    private var voicePackets = 0L
    private var voiceBytes = 0L
    private var gear = -1
    private var musicAskAt = 0L
    private var musicStartSeen = false
    private var musicEndSeen = false
    private var oddTouchAt = 0L

    @Volatile var width = 1280; private set
    @Volatile var height = 720; private set
    @Volatile var streamWidth = 1280; private set
    @Volatile var streamHeight = 720; private set
    @Volatile var fps = VideoPlans.START_FPS; private set
    @Volatile var fullRateStart = false
    @Volatile private var carAskedRate = false
    @Volatile private var streamStartedAt = 0L
    @Volatile var projecting = false; private set
    private var videoTimer: Job? = null
    private var encryptProbe: Job? = null
    private var screenWatch: BroadcastReceiver? = null
    private val crypto = CarLifeCrypto()
    @Volatile private var matched = false
    @Volatile private var initSeen = false
    @Volatile private var videoReady = false
    @Volatile private var newVehicle = false
    private var frames = 0L
    private var videoProfile: VideoProfile? = null
    private var videoProfileGeneration = 0
    private val feed = VideoFeed(::videoPacket, ::videoEmpty)
    private val media = MediaStream(::mediaPacket, ::moduleUpdate)
    private val navigation = NavigationRelay(
        { action, info, total ->
            if (action != 2) DiagLog.i(tag, "instrument cluster navigation action=$action turn=${NavigationRelay.turnCode(info.maneuver)}")
            if (matched) cmd(CarLifeProtocol.CMD_NAVI_NEXT_TURN_INFO,
                NavigationRelay.payload(action, info, total, NavigationIcons.png(info.maneuver, info.exit)), quiet = true)
        },
        { active -> moduleUpdate(CarLifeProtocol.MODULE_NAVI, if (active) 1 else 0) },
        // The consumer must still run if the session scope is cancelled before it starts.
        dispatch = { task -> Dispatchers.IO.asExecutor().execute { task() } },
        enabled = prefs.aaClusterNavigation,
        onError = { DiagLog.w(tag, "instrument cluster navigation write failed: ${it.message}") }
    )

    fun sendNavigation(info: app.ft.core.NavigationInfo) = navigation.update(info)
    fun refreshNavigationPreference() = navigation.enable(prefs.aaClusterNavigation)

    fun start() {
        DiagLog.i(tag, "session start via ${link.name}")
        link.start(scope, ::onMessage, { DiagLog.i(tag, it) }, ::linked, ::closed)
    }

    fun stop() {
        link.stop()
        reset()
        _state.value = State.Idle
    }

    private fun reset() {
        videoProfile = null
        projecting = false
        gear = -1
        videoTimer?.cancel()
        videoTimer = null
        encryptProbe?.cancel()
        crypto.reset()
        matched = false
        carMicOn = false
        initSeen = false
        videoReady = false
        carAskedRate = false
        streamStartedAt = 0L
        feed.close()
        media.forget()
        navigation.reset()
        unwatchScreen()
    }

    fun videoConfig(config: ByteArray) = feed.config(config)

    fun videoFrame(frame: ByteArray, key: Boolean) = feed.frame(frame, key)

    val videoMode: VideoFeed.Mode get() = feed.mode

    private fun videoClock() = (System.currentTimeMillis() / 1000).toInt()

    private fun videoPacket(frame: ByteArray) {
        frames++
        if (frames <= 3 || frames % 300 == 0L) DiagLog.d(tag, "video frame #$frames ${frame.size} bytes${if (crypto.active) " (encrypted)" else ""}")
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(CarLifeProtocol.VIDEO_DATA, crypto.encryptOut(frame), videoClock()))
    }

    private fun videoEmpty(heartbeat: Boolean) {
        val id = if (heartbeat) CarLifeProtocol.VIDEO_HEARTBEAT else CarLifeProtocol.VIDEO_DATA
        link.send(CarLifeProtocol.CH_VIDEO, CarLifeFraming.stream(id, ByteArray(0), videoClock()))
    }

    fun sendAudio(pcm: ByteArray) {
        if (matched) media.data(pcm)
    }

    fun mediaIdle(): Boolean = media.idle()

    val musicOpen: Boolean get() = media.open

    private fun mediaPacket(serviceId: Int, payload: ByteArray) {
        val body = if (serviceId == CarLifeProtocol.MEDIA_DATA) crypto.encryptOut(payload) else payload
        if (serviceId != CarLifeProtocol.MEDIA_DATA) DiagLog.d(tag, "media channel ${CarLifeProtocol.name(serviceId)}")
        link.send(CarLifeProtocol.CH_MEDIA, CarLifeFraming.stream(serviceId, body))
    }

    fun sendVoiceStart(rate: Int, channels: Int) {
        val r = if (rate in 4000..48000) rate else 16000
        link.send(CarLifeProtocol.CH_TTS, CarLifeFraming.stream(CarLifeProtocol.TTS_INIT, ProtoWriter().int32(1, r).int32(2, channels.coerceIn(1, 2)).int32(3, 16).toByteArray()))
        navigation.voiceAndWait(true)
    }

    fun sendVoice(pcm: ByteArray) {
        link.send(CarLifeProtocol.CH_TTS, CarLifeFraming.stream(CarLifeProtocol.TTS_DATA, crypto.encryptOut(pcm)))
    }

    fun sendVoiceEnd() {
        link.send(CarLifeProtocol.CH_TTS, CarLifeFraming.stream(CarLifeProtocol.TTS_END, ByteArray(0)))
        navigation.voiceAndWait(false)
    }

    @Volatile private var carMicOn = false

    fun carMicrophone(on: Boolean) {
        if (!matched || on == carMicOn) return
        carMicOn = on
        if (on) {
            moduleUpdate(CarLifeProtocol.MODULE_MIC, 1)
            cmd(CarLifeProtocol.CMD_MIC_RECORD_RECOG_START)
            DiagLog.i(tag, "asked the car to record from its own microphone")
        } else {
            cmd(CarLifeProtocol.CMD_MIC_RECORD_END)
            moduleUpdate(CarLifeProtocol.MODULE_VR, 0)
            moduleUpdate(CarLifeProtocol.MODULE_MIC, 0)
            DiagLog.i(tag, "told the car to stop recording from its microphone and put its voice light out")
        }
    }

    fun requestForeground() {
        if (matched) cmd(CarLifeProtocol.CMD_REQUEST_GO_TO_FOREGROUND)
    }

    fun goodbye() {
        if (matched) cmd(CarLifeProtocol.CMD_MD_MANUAL_DISCONNECT)
    }

    private fun moduleUpdate(module: Int, state: Int) {
        if (!matched) return
        cmd(CarLifeProtocol.CMD_MODULE_STATUS, ProtoWriter().int32(1, 1).message(2, ProtoWriter().int32(1, module).int32(2, state)).toByteArray())
    }

    private fun cmd(serviceId: Int, payload: ByteArray = ByteArray(0), quiet: Boolean = false) {
        val inner = CarLifeFraming.cmd(serviceId, crypto.encryptOut(payload))
        if (!quiet) DiagLog.tx(tag, CarLifeProtocol.name(serviceId), inner)
        link.send(CarLifeProtocol.CH_CMD, inner)
    }

    fun sendSong(title: String, artist: String, album: String, art: ByteArray?, durationMs: Long, index: Int, count: Int, id: String) {
        if (!projecting && _state.value !is State.Negotiated) return
        cmd(CarLifeProtocol.CMD_MEDIA_INFO, songPayload(title, artist, album, art, durationMs, count, id), quiet = true)
        DiagLog.i(tag, "told the car the song: '$title'${if (artist.isNotBlank()) " by $artist" else ""}${if (art != null) ", with cover art (${art.size} bytes)" else ""}")
    }

    fun goToCarHome() {
        cmd(CarLifeProtocol.CMD_GO_TO_DESKTOP)
        DiagLog.i(tag, "asked the car to show its own screen, FT stays connected")
    }

    fun sendSongPosition(ms: Long) {
        if (!projecting) return
        cmd(CarLifeProtocol.CMD_MEDIA_PROGRESS_BAR, ProtoWriter().int32(1, ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()).toByteArray(), quiet = true)
    }

    companion object {
        fun songPayload(title: String, artist: String, album: String, art: ByteArray?, durationMs: Long, count: Int, id: String): ByteArray =
            ProtoWriter()
                .string(1, "FT")
                .string(2, title)
                .string(3, artist)
                .string(4, album)
                .bytes(5, art ?: ByteArray(0))
                .int32(6, durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                .int32(7, count)
                .string(8, id)
                .int32(9, 0)
                .toByteArray()

        fun encoderInfo(width: Int, height: Int, fps: Int): ByteArray =
            ProtoWriter().int32(1, width).int32(2, height).int32(3, fps).toByteArray()

        fun subscribeDone(payload: ByteArray): ByteArray {
            val modules = ProtoReader(payload).messages(2).map { it.int(1, 0) }
            val w = ProtoWriter().int32(1, modules.size)
            for (id in modules) w.message(2, ProtoWriter().int32(1, id).bool(2, id == SUBSCRIBE_MUSIC || id == SUBSCRIBE_NAVIGATION))
            return w.toByteArray()
        }

        private const val SUBSCRIBE_MUSIC = 2
        private const val SUBSCRIBE_NAVIGATION = 0
        private const val DOUBLE_TAP_GAP_MS = 60L
        private const val LONG_PRESS_MS = 800L
        private const val VIDEO_POLL_MS = 50L
        private const val QUIET_FRAMES = 6
        private const val HEARTBEAT_CAP_MS = 900L
    }

    private fun linked() {
        DiagLog.i(tag, "head unit connected over ${link.name}")
        if (_state.value == State.Idle) _state.value = State.Linked(link.name)
    }

    private fun closed(reason: String) {
        DiagLog.w(tag, reason)
        reset()
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
        if (c.serviceId != CarLifeProtocol.CMD_HU_GEAR_INFO && c.serviceId != CarLifeProtocol.CMD_MODULE_CONTROL) {
            DiagLog.rx(tag, CarLifeProtocol.name(c.serviceId), c.payload)
        }
        when (c.serviceId) {
            CarLifeProtocol.CMD_HU_PROTOCOL_VERSION -> onProtocolVersion(ProtoReader(c.payload))
            CarLifeProtocol.CMD_HU_INFO -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "HU info: ${r.fields.keys.joinToString { k -> "$k=${r.string(k) ?: r.int(k)}" }}")
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
            CarLifeProtocol.CMD_VIDEO_ENCODER_INIT -> onVideoInit(ProtoReader(c.payload))
            CarLifeProtocol.CMD_VIDEO_ENCODER_START -> {
                val mode = feed.start()
                projecting = true
                if (streamStartedAt == 0L) streamStartedAt = android.os.SystemClock.elapsedRealtime()
                frames = 0
                _state.value = State.Projecting(link.name, width, height, fps, streamWidth, streamHeight)
                startVideoTimer()
                if (mode == VideoFeed.Mode.RESUMING) onVideoBack?.invoke()
                DiagLog.i(tag, when (mode) {
                    VideoFeed.Mode.STARTING -> "projection started"
                    VideoFeed.Mode.RESUMING -> "projection back, the car gets the next full picture"
                    else -> "projection started again while already running"
                })
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE -> {
                feed.pause()
                projecting = false
                _state.value = State.Negotiated(link.name, width, height, fps, streamWidth, streamHeight)
                DiagLog.i(tag, "projection paused by HU, keeping the picture warm")
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_RESET -> {
                DiagLog.i(tag, "head unit asked for a fresh picture, sending a new key frame with the codec info")
                feed.resendConfig()
                onNeedKeyframe?.invoke()
            }
            CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val asked = ProtoReader(c.payload).int(1, -1)
                val f = VideoPlans.rateChange(asked, prefs.videoFps, prefs.videoMinFps, fps)
                if (f == null) {
                    DiagLog.i(tag, "head unit asked for $asked fps, only 3 to 30 are taken")
                    return
                }
                carAskedRate = true
                DiagLog.i(tag, "head unit asked for $asked fps")
                if (asked < VideoPlans.LOWEST_PACE && f == fps) DiagLog.i(tag, "head unit asked for $asked fps, FT keeps $fps like Baidu, which never paces below ${VideoPlans.LOWEST_PACE}")
                applyRate(f)
                cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, ProtoWriter().int32(1, asked).toByteArray())
            }
            CarLifeProtocol.CMD_STATISTIC_INFO -> {
                val r = ProtoReader(c.payload)
                DiagLog.i(tag, "head unit statistics: ${r.fields.keys.joinToString { k -> "$k=${r.string(k) ?: r.int(k)}" }}")
                if (!newVehicle) cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESULT, ProtoWriter().bool(1, true).toByteArray())
                cmd(CarLifeProtocol.CMD_MD_FEATURE_CONFIG_REQUEST)
            }
            CarLifeProtocol.CMD_HU_AUTHEN_REQUEST -> {
                val random = ProtoReader(c.payload).string(1) ?: ""
                DiagLog.w(tag, "HU authentication challenge random='$random' (replying best-effort)")
                cmd(CarLifeProtocol.CMD_MD_AUTHEN_RESPONSE, ProtoWriter().string(1, random).toByteArray())
            }
            CarLifeProtocol.CMD_HU_AUTHEN_RESULT -> {
                DiagLog.i(tag, "HU authen result ${ProtoReader(c.payload).bool(1)}")
            }
            CarLifeProtocol.CMD_GO_TO_FOREGROUND -> DiagLog.i(tag, "head unit asked FT to come forward, it already draws on the car")
            CarLifeProtocol.CMD_LAUNCH_MODE_NORMAL -> {
                media.launchedAgain()
                onLaunchMode?.invoke("normal")
            }
            CarLifeProtocol.CMD_LAUNCH_MODE_PHONE -> onLaunchMode?.invoke("phone")
            CarLifeProtocol.CMD_LAUNCH_MODE_MAP -> onLaunchMode?.invoke("map")
            CarLifeProtocol.CMD_LAUNCH_MODE_MUSIC -> onLaunchMode?.invoke("music")
            CarLifeProtocol.CMD_PAUSE_MEDIA -> {
                DiagLog.i(tag, "the car asked to pause the music")
                onPauseMedia?.invoke()
            }
            CarLifeProtocol.CMD_MODULE_CONTROL -> onModuleControl(ProtoReader(c.payload), c.payload)
            CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE -> {
                val reply = subscribeDone(c.payload)
                cmd(CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_DONE, reply)
                DiagLog.i(tag, "head unit asked for phone data ${ProtoReader(c.payload).messages(2).map { it.int(1, 0) }}, FT offers navigation and song info")
            }
            CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_START, CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_STOP -> {
                if (ProtoReader(c.payload).messages(2).any { it.int(1, -1) == SUBSCRIBE_NAVIGATION }) {
                    val on = c.serviceId == CarLifeProtocol.CMD_CARLIFE_DATA_SUBSCRIBE_START
                    navigation.subscribe(on)
                    DiagLog.i(tag, "instrument cluster navigation subscription: $on")
                }
            }
            CarLifeProtocol.CMD_HU_GEAR_INFO -> {
                val g = ProtoReader(c.payload).int(1, -1)
                if (g != gear) {
                    gear = g
                    DiagLog.d(tag, "car gear $g")
                    onGear?.invoke(g)
                }
            }
            CarLifeProtocol.CMD_VEHICLE_FOREGROUND -> DiagLog.i(tag, "the car shows CarLife")
            CarLifeProtocol.CMD_VEHICLE_BACKGROUND -> DiagLog.i(tag, "the car moved CarLife to the background")
            CarLifeProtocol.CMD_CAR_VELOCITY,
            CarLifeProtocol.CMD_CAR_GPS, CarLifeProtocol.CMD_CAR_GYROSCOPE, CarLifeProtocol.CMD_CAR_ACCELERATION,
            CarLifeProtocol.CMD_CAR_OIL, CarLifeProtocol.CMD_ERROR_CODE, CarLifeProtocol.CMD_BT_HFP_INDICATION,
            CarLifeProtocol.CMD_BT_HFP_CONNECTION, CarLifeProtocol.CMD_BT_HFP_RESPONSE, CarLifeProtocol.CMD_BT_HFP_STATUS_RESPONSE,
            CarLifeProtocol.CMD_BT_START_IDENTIFY_REQ, CarLifeProtocol.CMD_VIDEO_ENCODER_JPEG -> Unit
            else -> DiagLog.w(tag, "unhandled cmd ${CarLifeProtocol.name(c.serviceId)}")
        }
    }

    private fun onProtocolVersion(r: ProtoReader) {
        val major = r.int(1)
        val minor = r.int(2)
        if (matched) {
            DiagLog.i(tag, "HU protocol $major.$minor again, already matched")
            return
        }
        newVehicle = VideoPlans.newVehicle(major, minor)
        media.forget()
        media.oldVehicle = !newVehicle
        DiagLog.i(tag, "HU protocol $major.$minor")
        cmd(CarLifeProtocol.CMD_PROTOCOL_VERSION_MATCH_STATUS, ProtoWriter().int32(1, 1).toByteArray())
        matched = true
        initSeen = false
        carAskedRate = false
        streamStartedAt = 0L
        musicStartSeen = false
        musicEndSeen = false
        if (_state.value == State.Idle) _state.value = State.Linked(link.name)
        cmd(CarLifeProtocol.CMD_FOREGROUND)
        screenState()
        cmd(CarLifeProtocol.CMD_MD_INFO, deviceInfo())
        scope.launch {
            delay(500)
            if (matched) cmd(CarLifeProtocol.CMD_MODULE_STATUS, moduleStatus())
        }
        armEncryptProbe()
    }

    private fun onVideoInit(r: ProtoReader) {
        initSeen = true
        encryptProbe?.cancel()
        val carW = r.int(1, 0)
        val carH = r.int(2, 0)
        val asked = r.int(3, 0)
        if (videoReady) {
            DiagLog.i(tag, "head unit set up video again at ${carW}x$carH, keeping ${streamWidth}x$streamHeight")
            cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE, encoderInfo(streamWidth, streamHeight, fps))
            return
        }
        val candidate = VideoPlans.plan(
            carW, carH, asked, newVehicle,
            prefs.videoSize, prefs.videoWidth, prefs.videoHeight,
            prefs.videoFps, prefs.videoMinFps, prefs.videoBitrate, prefs.videoQpFloor,
            if (fullRateStart) VideoPlans.UNASKED_FPS else VideoPlans.START_FPS
        )
        videoProfileGeneration = prefs.videoProfileGeneration
        val settingsKey = prefs.videoSettingsKey
        val carKey = "${prefs.carBtAddress}:${link.name}:$newVehicle"
        val restored = prefs.savedVideoProfile?.resume(candidate, settingsKey, carKey, asked)
        val plan = restored ?: candidate
        width = plan.contentWidth
        height = plan.contentHeight
        streamWidth = plan.streamWidth
        streamHeight = plan.streamHeight
        fps = plan.fps
        DiagLog.i(tag, "car screen ${carW}x$carH, sending ${streamWidth}x$streamHeight at $fps fps, ${plan.bitrate / 1000} kbps" + if (asked > 0) " (head unit asked $asked fps)" else "")
        feed.open()
        videoReady = true
        onVideoConfig?.invoke(plan)
        if (onVideoConfig != null) {
            videoProfile = VideoProfile(plan, settingsKey, carKey,
                prefs.videoSize != VideoPlans.SIZE_CUSTOM, prefs.videoFps == 0)
            prefs.saveVideoProfile(videoProfile!!, videoProfileGeneration)
        }
        cmd(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT_DONE, encoderInfo(streamWidth, streamHeight, asked))
        cmd(CarLifeProtocol.CMD_FOREGROUND)
        _state.value = State.Negotiated(link.name, width, height, fps, streamWidth, streamHeight)
    }

    private fun onModuleControl(r: ProtoReader, raw: ByteArray) {
        val module = r.int(1, -1)
        val status = r.int(2, -1)
        val now = System.currentTimeMillis()
        val loud = now - musicAskAt > 4000
        if (loud) {
            musicAskAt = now
            DiagLog.rx(tag, CarLifeProtocol.name(CarLifeProtocol.CMD_MODULE_CONTROL), raw)
        }
        if (module != CarLifeProtocol.MODULE_MUSIC || (status != 0 && status != 1)) return
        if (status == 1 && !musicStartSeen) {
            musicStartSeen = true
            return
        }
        if (status == 0 && !musicEndSeen) {
            musicEndSeen = true
            return
        }
        if (loud) DiagLog.i(tag, "the car wants the music ${if (status == 1) "playing" else "paused"}")
        onMusicControl?.invoke(status == 1)
    }

    private fun handleCtrl(c: CarLifeFraming.Cmd) {
        when (c.serviceId) {
            CarLifeProtocol.TOUCH_ACTION -> {
                val r = ProtoReader(c.payload)
                val raw = r.int(1, -1)
                val x = r.int(2, -1)
                val y = r.int(3, -1)
                val px = r.int(4, 0)
                val py = r.int(5, 0)
                if (raw < 0 || x < 0 || y < 0) return
                val action = raw and 0xFF
                val index = (raw shr 8) and 0xFF
                val second = px != 0 || py != 0
                when (action) {
                    CarTouch.DOWN, CarTouch.UP, CarTouch.MOVE ->
                        touch(CarTouch(action, x, y, if (second) px else -1, if (second) py else -1))
                    CarTouch.POINTER_DOWN -> touch(CarTouch(action, x, y, if (second) px else x, if (second) py else y, index.coerceAtLeast(1)))
                    CarTouch.POINTER_UP -> touch(CarTouch(action, x, y, if (second) px else -1, if (second) py else -1, index))
                    else -> oddTouch("touch action $raw")
                }
            }
            CarLifeProtocol.TOUCH_DOWN -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.DOWN, x, y)) }
            CarLifeProtocol.TOUCH_UP -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.UP, x, y)) }
            CarLifeProtocol.TOUCH_MOVE -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.MOVE, x, y)) }
            CarLifeProtocol.TOUCH_SINGLE_CLICK -> point(c.payload) { x, y ->
                touch(CarTouch(CarTouch.DOWN, x, y))
                touch(CarTouch(CarTouch.UP, x, y))
            }
            CarLifeProtocol.TOUCH_DOUBLE_CLICK -> point(c.payload) { x, y ->
                touch(CarTouch(CarTouch.DOWN, x, y))
                touch(CarTouch(CarTouch.UP, x, y))
                scope.launch {
                    delay(DOUBLE_TAP_GAP_MS)
                    touch(CarTouch(CarTouch.DOWN, x, y))
                    touch(CarTouch(CarTouch.UP, x, y))
                }
            }
            CarLifeProtocol.TOUCH_LONG_PRESS -> point(c.payload) { x, y ->
                touch(CarTouch(CarTouch.DOWN, x, y))
                scope.launch {
                    delay(LONG_PRESS_MS)
                    touch(CarTouch(CarTouch.UP, x, y))
                }
            }
            CarLifeProtocol.TOUCH_POINTER_DOWN -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.POINTER_DOWN, x, y, x, y, 1)) }
            CarLifeProtocol.TOUCH_POINTER_UP -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.POINTER_UP, x, y, -1, -1, 0)) }
            CarLifeProtocol.TOUCH_OTHERPOINTER_UP -> point(c.payload) { x, y -> touch(CarTouch(CarTouch.POINTER_UP, x, y, -1, -1, 1)) }
            CarLifeProtocol.TOUCH_UI_ACTION_BEGIN -> Unit
            CarLifeProtocol.CAR_HARD_KEY_CODE -> {
                val r2 = ProtoReader(c.payload)
                val key = r2.int(1, -1)
                DiagLog.i(tag, "steering wheel key $key (payload ${c.payload.joinToString(" ") { b -> "%02X".format(b) }})")
                if (key >= 0) onHardKey?.invoke(key)
            }
            else -> DiagLog.rx(tag, "ctrl ${CarLifeProtocol.name(c.serviceId)}", c.payload)
        }
    }

    private inline fun point(payload: ByteArray, use: (Int, Int) -> Unit) {
        val r = ProtoReader(payload)
        val x = r.int(1, -1)
        val y = r.int(2, -1)
        if (x >= 0 && y >= 0) use(x, y)
    }

    private fun touch(t: CarTouch) {
        onTouch?.invoke(t)
    }

    private fun oddTouch(what: String) {
        val now = System.currentTimeMillis()
        if (now - oddTouchAt < 10_000) return
        oddTouchAt = now
        DiagLog.i(tag, "$what from the car is not a touch FT knows")
    }

    private fun startVideoTimer() {
        if (videoTimer?.isActive == true) return
        videoTimer = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(VIDEO_POLL_MS)
                feed.idle((QUIET_FRAMES * 1000L / fps.coerceIn(1, 60)).coerceAtMost(HEARTBEAT_CAP_MS))
                if (feed.needsKey()) {
                    DiagLog.i(tag, "no full picture came by itself since the car came back, asking the encoder for one")
                    onNeedKeyframe?.invoke()
                }
                unaskedRate()
            }
        }
    }

    private fun unaskedRate() {
        if (carAskedRate || !projecting || streamStartedAt == 0L) return
        if (android.os.SystemClock.elapsedRealtime() - streamStartedAt < VideoPlans.UNASKED_AFTER_MS) return
        carAskedRate = true
        val f = VideoPlans.unaskedRate(prefs.videoFps, prefs.videoMinFps, fps) ?: return
        DiagLog.i(tag, "the car has not asked for a frame rate, FT goes to $f frames a second")
        applyRate(f)
    }

    private fun applyRate(f: Int) {
        if (f == fps) return
        fps = f
        onFrameRate?.invoke(f)
        videoProfile = videoProfile?.let { it.copy(plan = it.plan.copy(fps = f)) }
        videoProfile?.let { prefs.saveVideoProfile(it, videoProfileGeneration) }
        when (val st = _state.value) {
            is State.Projecting -> _state.value = st.copy(fps = f)
            is State.Negotiated -> _state.value = st.copy(fps = f)
            else -> Unit
        }
    }

    private fun screenState() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == true) cmd(CarLifeProtocol.CMD_SCREEN_ON)
        watchScreen()
    }

    private fun watchScreen() {
        if (screenWatch != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!matched) return
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> cmd(CarLifeProtocol.CMD_SCREEN_ON)
                    Intent.ACTION_USER_PRESENT -> {
                        DiagLog.i(tag, "phone unlocked")
                        cmd(CarLifeProtocol.CMD_SCREEN_USERPRESENT)
                    }
                    Intent.ACTION_SCREEN_OFF -> DiagLog.i(tag, "phone screen went off")
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(r, f)
            screenWatch = r
        }.onFailure { DiagLog.w(tag, "cannot follow the phone screen: ${it.message}") }
    }

    private fun unwatchScreen() {
        val r = screenWatch ?: return
        screenWatch = null
        runCatching { context.unregisterReceiver(r) }
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

    private fun btPairInfo(status: Int): ByteArray {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        // Lint requires this privileged permission in the manifest even with a
        // runtime guard. Only privileged installations can enter the guarded call.
        @android.annotation.SuppressLint("MissingPermission")
        val address = try {
            // Ordinary apps cannot obtain the local MAC; do not send Android's
            // placeholder address to the head unit as a real pairing address.
            if (context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                adapter?.address.orEmpty()
            } else {
                ""
            }
        } catch (_: SecurityException) {
            ""
        }
        val name = try {
            adapter?.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }.ifBlank { prefs.carName }
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
            CarLifeProtocol.MODULE_NAVI to if (navigation.active) 1 else 0,
            CarLifeProtocol.MODULE_MUSIC to if (media.open) 1 else 0,
            CarLifeProtocol.MODULE_VR to 0,
            CarLifeProtocol.MODULE_MIC to 0
        )
        val w = ProtoWriter().int32(1, modules.size)
        for ((id, st) in modules) w.message(2, ProtoWriter().int32(1, id).int32(2, st))
        return w.toByteArray()
    }
}
