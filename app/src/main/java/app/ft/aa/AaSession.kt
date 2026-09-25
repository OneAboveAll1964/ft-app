package app.ft.aa

import android.content.Context
import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AaSession(
    context: Context,
    private val input: InputStream,
    private val output: OutputStream,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val decoder: AaVideoDecoder,
    private val onClosed: (String) -> Unit
) {
    enum class Phase { CONNECTED, VERSIONED, HANDSHAKING, AUTHENTICATED, DISCOVERED, STREAMING }

    private val tag = "AA"
    private val crypto = AaCrypto(context)
    private val assembler = AaFraming.Assembler { crypto.decrypt(it) }
    private val outLock = Any()
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var videoSession = 0
    private var mediaFrames = 0L
    private val _phase = MutableStateFlow(Phase.CONNECTED)
    val phase: StateFlow<Phase> = _phase
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName
    private val openChannels = HashSet<Int>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            try {
                sendPlain(AaProtocol.CH_CONTROL, AaProtocol.VERSION_REQUEST, AaMessages.versionRequest(), false)
                DiagLog.i(tag, "version request sent")
                while (isActive && running.get()) {
                    val frame = AaFraming.readFrame(input)
                    val msg = assembler.feed(frame) ?: continue
                    dispatch(msg)
                }
            } catch (t: Throwable) {
                if (running.get()) DiagLog.w(tag, "session ended: ${t.message}")
            } finally {
                close("session closed")
            }
        }
    }

    fun close(reason: String) {
        if (!running.getAndSet(false)) return
        runCatching { input.close() }
        runCatching { output.close() }
        decoder.stop()
        job?.cancel()
        onClosed(reason)
    }

    private fun sendPlain(channel: Int, id: Int, body: ByteArray, control: Boolean) = send(channel, id, body, false, control)
    private fun sendEnc(channel: Int, id: Int, body: ByteArray, control: Boolean = false) = send(channel, id, body, true, control)

    private fun send(channel: Int, id: Int, body: ByteArray, encrypted: Boolean, control: Boolean) {
        val payload = ByteArray(2 + body.size)
        Bytes.putU16(id, payload, 0)
        System.arraycopy(body, 0, payload, 2, body.size)
        val frames = AaFraming.encode(channel, payload, encrypted, control) { if (encrypted) crypto.encrypt(it) else it }
        synchronized(outLock) {
            try {
                frames.forEach { output.write(it) }
                output.flush()
            } catch (t: Throwable) {
                DiagLog.e(tag, "write failed", t)
                close("write failed")
            }
        }
    }

    private fun dispatch(m: AaFraming.Message) {
        val id = m.id
        val body = m.body
        if (m.channel == AaProtocol.CH_CONTROL || m.control) {
            handleControl(m.channel, id, body)
            return
        }
        when (m.channel) {
            AaProtocol.CH_VIDEO -> handleVideo(id, body)
            AaProtocol.CH_INPUT -> handleInput(id, body)
            AaProtocol.CH_SENSOR -> handleSensor(id, body)
            AaProtocol.CH_MEDIA_AUDIO, AaProtocol.CH_SPEECH_AUDIO, AaProtocol.CH_SYSTEM_AUDIO -> handleAudioSink(m.channel, id, body)
            AaProtocol.CH_AV_INPUT -> handleAvInput(id, body)
            else -> DiagLog.d(tag, "${AaProtocol.channelName(m.channel)} msg 0x${Integer.toHexString(id)} len=${body.size}")
        }
    }

    private fun handleControl(channel: Int, id: Int, body: ByteArray) {
        if (id != AaProtocol.PING_REQUEST && id != AaProtocol.SSL_HANDSHAKE) DiagLog.rx(tag, "${AaProtocol.channelName(channel)} ${AaProtocol.controlName(id)}", body)
        when (id) {
            AaProtocol.VERSION_RESPONSE -> {
                val v = AaMessages.parseVersionResponse(body)
                DiagLog.i(tag, "phone protocol ${v.major}.${v.minor} status=${v.status}")
                _phase.value = Phase.VERSIONED
                val hello = crypto.handshakeStep(null)
                if (hello.isNotEmpty()) sendPlain(AaProtocol.CH_CONTROL, AaProtocol.SSL_HANDSHAKE, hello, false)
                _phase.value = Phase.HANDSHAKING
            }
            AaProtocol.SSL_HANDSHAKE -> {
                val out = crypto.handshakeStep(body)
                if (out.isNotEmpty()) sendPlain(AaProtocol.CH_CONTROL, AaProtocol.SSL_HANDSHAKE, out, false)
                if (crypto.handshakeDone) {
                    sendPlain(AaProtocol.CH_CONTROL, AaProtocol.AUTH_COMPLETE, AaMessages.authComplete(), false)
                    _phase.value = Phase.AUTHENTICATED
                    DiagLog.i(tag, "TLS handshake complete, auth complete sent")
                }
            }
            AaProtocol.SERVICE_DISCOVERY_REQUEST -> {
                _deviceName.value = AaMessages.deviceName(body)
                DiagLog.i(tag, "service discovery from '${_deviceName.value}'")
                sendEnc(
                    AaProtocol.CH_CONTROL, AaProtocol.SERVICE_DISCOVERY_RESPONSE,
                    AaMessages.serviceDiscoveryResponse(prefs.aaWidth, prefs.aaHeight, prefs.aaFps, prefs.aaDensity, false, prefs.carName)
                )
                _phase.value = Phase.DISCOVERED
            }
            AaProtocol.CHANNEL_OPEN_REQUEST -> {
                val sid = AaMessages.channelOpenServiceId(body)
                openChannels += channel
                DiagLog.i(tag, "channel open ${AaProtocol.channelName(channel)} (service $sid)")
                sendEnc(channel, AaProtocol.CHANNEL_OPEN_RESPONSE, AaMessages.channelOpenResponse(), true)
            }
            AaProtocol.PING_REQUEST -> sendEnc(AaProtocol.CH_CONTROL, AaProtocol.PING_RESPONSE, AaMessages.pingResponse(AaMessages.pingTimestamp(body)))
            AaProtocol.PING_RESPONSE -> Unit
            AaProtocol.AUDIO_FOCUS_REQUEST -> {
                val req = AaMessages.audioFocusRequestType(body)
                val state = if (req == AaProtocol.AUDIO_REQUEST_RELEASE) AaProtocol.AUDIO_FOCUS_LOSS else AaProtocol.AUDIO_FOCUS_GAIN
                sendEnc(AaProtocol.CH_CONTROL, AaProtocol.AUDIO_FOCUS_NOTIFICATION, AaMessages.audioFocusNotification(state))
            }
            AaProtocol.NAV_FOCUS_REQUEST -> sendEnc(AaProtocol.CH_CONTROL, AaProtocol.NAV_FOCUS_NOTIFICATION, AaMessages.navFocusNotification(AaProtocol.NAV_FOCUS_PROJECTED))
            AaProtocol.BYEBYE_REQUEST -> {
                DiagLog.i(tag, "phone bye-bye reason=${AaMessages.byeByeReason(body)}")
                sendEnc(AaProtocol.CH_CONTROL, AaProtocol.BYEBYE_RESPONSE, AaMessages.byeByeResponse())
                close("bye-bye")
            }
            AaProtocol.CHANNEL_CLOSE_NOTIFICATION -> DiagLog.i(tag, "channel close ${AaProtocol.channelName(channel)}")
            AaProtocol.VOICE_SESSION_NOTIFICATION -> Unit
            else -> DiagLog.w(tag, "unhandled control ${AaProtocol.controlName(id)} on ${AaProtocol.channelName(channel)}")
        }
    }

    private fun handleVideo(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> {
                DiagLog.i(tag, "video setup config=${AaMessages.setupConfigIndex(body)}")
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(AaProtocol.VIDEO_FOCUS_PROJECTED, false))
            }
            AaProtocol.AV_START_INDICATION -> {
                videoSession = AaMessages.startSession(body)
                mediaFrames = 0
                _phase.value = Phase.STREAMING
                DiagLog.i(tag, "video start session=$videoSession")
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(AaProtocol.VIDEO_FOCUS_PROJECTED, false))
            }
            AaProtocol.AV_STOP_INDICATION -> {
                DiagLog.i(tag, "video stop")
                decoder.stop()
                _phase.value = Phase.DISCOVERED
            }
            AaProtocol.AV_MEDIA_INDICATION -> {
                DiagLog.i(tag, "video codec config ${body.size} bytes")
                decoder.onConfig(body)
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(videoSession))
            }
            AaProtocol.AV_MEDIA_WITH_TIMESTAMP -> {
                if (body.size > 8) {
                    val ts = Bytes.u64(body, 0)
                    mediaFrames++
                    if (mediaFrames <= 3 || mediaFrames % 300 == 0L) DiagLog.d(tag, "video frame #$mediaFrames ${body.size - 8} bytes")
                    decoder.onFrame(body.copyOfRange(8, body.size), ts)
                }
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(videoSession))
            }
            AaProtocol.VIDEO_FOCUS_REQUEST -> {
                val mode = AaMessages.videoFocusRequestMode(body)
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(mode, false))
            }
            else -> DiagLog.w(tag, "unhandled video ${AaProtocol.avName(id)}")
        }
    }

    private fun handleInput(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.BINDING_REQUEST -> {
                DiagLog.i(tag, "input binding request")
                sendEnc(AaProtocol.CH_INPUT, AaProtocol.BINDING_RESPONSE, AaMessages.bindingResponse())
            }
            else -> DiagLog.d(tag, "input msg 0x${Integer.toHexString(id)}")
        }
    }

    private fun handleSensor(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.SENSOR_START_REQUEST -> {
                val type = AaMessages.sensorRequestType(body)
                sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_START_RESPONSE, AaMessages.sensorStartResponse())
                when (type) {
                    AaProtocol.SENSOR_DRIVING_STATUS -> sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, AaMessages.sensorDrivingStatus(0))
                    AaProtocol.SENSOR_NIGHT_MODE -> sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, AaMessages.sensorNightMode(false))
                    else -> Unit
                }
                DiagLog.i(tag, "sensor start type=$type")
            }
            else -> DiagLog.d(tag, "sensor msg 0x${Integer.toHexString(id)}")
        }
    }

    private fun handleAudioSink(channel: Int, id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> sendEnc(channel, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
            AaProtocol.AV_START_INDICATION, AaProtocol.AV_STOP_INDICATION -> Unit
            AaProtocol.AV_MEDIA_WITH_TIMESTAMP, AaProtocol.AV_MEDIA_INDICATION -> sendEnc(channel, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(0))
            else -> Unit
        }
    }

    private fun handleAvInput(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> sendEnc(AaProtocol.CH_AV_INPUT, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
            AaProtocol.AV_INPUT_OPEN_REQUEST -> sendEnc(AaProtocol.CH_AV_INPUT, AaProtocol.AV_INPUT_OPEN_RESPONSE, AaMessages.avInputOpenResponse(AaMessages.avInputOpenSession(body)))
            else -> Unit
        }
    }

    fun sendTouch(x: Int, y: Int, action: Int) {
        if (_phase.value < Phase.DISCOVERED || AaProtocol.CH_INPUT !in openChannels) return
        val aaAction = when (action) {
            0 -> AaProtocol.POINTER_DOWN
            1 -> AaProtocol.POINTER_UP
            else -> AaProtocol.POINTER_MOVED
        }
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputTouch(System.nanoTime(), x, y, aaAction))
    }

    fun sendKey(keycode: Int) {
        if (_phase.value < Phase.DISCOVERED || AaProtocol.CH_INPUT !in openChannels) return
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputKey(System.nanoTime(), keycode, true))
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputKey(System.nanoTime(), keycode, false))
    }
}
