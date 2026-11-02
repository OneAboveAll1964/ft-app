package app.ft.iccoa

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class IccoaVideoConfig(
    val host: String, val width: Int = 2764, val height: Int = 1426,
    val fps: Int = 30, val rtpPort: Int = 19000
)

/** Phone is the WFD source; HU opens RTSP to :7236 and later listens for RTP/TCP. */
internal class IccoaRtsp(
    private val socket: Socket,
    private val onPlay: (IccoaVideoConfig) -> Unit,
    private val onPause: () -> Unit,
    private val onKeyframe: () -> Unit,
    private val onClosed: (String) -> Unit
) : AutoCloseable {
    private val pending = ConcurrentHashMap<Int, String>()
    private var sequence = 1
    private var asked = false
    private var setup = false
    private var config = IccoaVideoConfig(socket.inetAddress.hostAddress ?: error("No peer"))
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "ICCOA-RTSP-watch") }
    @Volatile private var stopped = false
    @Volatile private var keepaliveSent = 0L
    @Volatile private var keepaliveDue = Long.MAX_VALUE
    private val notified = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun failed(reason: String) {
        if (!stopped && notified.compareAndSet(false, true)) onClosed(reason)
        close()
    }

    fun run() {
        try {
            socket.soTimeout = 30000
            request("OPTIONS * RTSP/1.0", "M1", extra = "Require: org.wfa.wfd1.0\r\n")
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            timer.scheduleAtFixedRate({
                runCatching {
                    val now = System.nanoTime() / 1_000_000
                    if (keepaliveSent > 0 && now - keepaliveSent > 10000) error("RTSP heartbeat timed out")
                    if (now >= keepaliveDue && keepaliveSent == 0L) {
                        keepaliveSent = now
                        request("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M16", extra = "Session: 1\r\n")
                    }
                }.onFailure { failed("RTSP: ${it.message ?: it.javaClass.simpleName}") }
            }, 1, 1, TimeUnit.SECONDS)
            while (!stopped) {
                val message = read(input)
                val cseq = message.headers["cseq"]?.toIntOrNull() ?: error("Missing RTSP CSeq")
                if (message.first.startsWith("RTSP/")) {
                    val kind = pending.remove(cseq) ?: continue
                    require(message.first.split(' ').getOrNull(1) == "200") { "RTSP rejected $kind" }
                    when (kind) {
                        "M3" -> {
                            config = capabilities(config, message.body)
                            request("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M4", parameters())
                        }
                        "M4" -> request("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M5", "wfd_trigger_method: SETUP\r\n")
                        "M16" -> { keepaliveSent = 0; keepaliveDue = System.nanoTime() / 1_000_000 + 20000 }
                    }
                    continue
                }
                when (message.first.substringBefore(' ')) {
                    "OPTIONS" -> {
                        response(cseq, extra = "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n")
                        if (!asked) {
                            asked = true
                            request("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M3",
                                listOf("wfd_video_formats", "wfd_audio_codecs", "wfd_client_rtp_ports", "wfd_content_protection",
                                    "wfd_display_edid", "wfd_uibc_capability", "wfd_coupled_sink", "wfd_standby_resume_capability",
                                    "wfd_connector_type", "wfd_car_display_mode", "iccoa_support_hevc").joinToString("\r\n", postfix = "\r\n"))
                        }
                    }
                    "SETUP" -> {
                        val transport = message.headers["transport"] ?: "RTP/AVP/TCP;unicast;client_port=${config.rtpPort}"
                        require(transport.contains("RTP/AVP/TCP", true)) { "Only RTP/TCP is supported" }
                        Regex("(?:client_port=|port=)(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            require(it in 1..65535); config = config.copy(rtpPort = it)
                        }
                        setup = true
                        response(cseq, extra = "Transport: $transport\r\nSession: 1;timeout=30\r\n")
                    }
                    "PLAY" -> {
                        check(setup) { "PLAY before SETUP" }
                        response(cseq, extra = "Session: 1\r\n")
                        socket.soTimeout = 0
                        keepaliveDue = System.nanoTime() / 1_000_000 + 20000
                        onPlay(config)
                    }
                    "PAUSE" -> { response(cseq, extra = "Session: 1\r\n"); onPause() }
                    "TEARDOWN" -> { response(cseq, extra = "Session: 1\r\n"); return }
                    "GET_PARAMETER" -> response(cseq, message.body.takeIf { it.isNotBlank() }?.let { wanted ->
                        parameters().lineSequence().filter { line -> line.isNotBlank() && wanted.contains(line.substringBefore(':')) }.joinToString("\r\n", postfix = "\r\n")
                    })
                    "SET_PARAMETER" -> {
                        if (message.body.contains("ovm_control_capability", ignoreCase = true)) {
                            // OEM OVM control port is not yet independently verified; never fake it.
                            app.ft.core.DiagLog.w("ICCOA", "OEM OVM requested; no verified local OVM service/port")
                        }
                        response(cseq)
                        if (message.body.contains("wfd_idr_request")) onKeyframe()
                    }
                    else -> response(cseq, status = "405 Method Not Allowed")
                }
            }
        } catch (e: Exception) {
            failed("RTSP: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            failed("RTSP disconnected")
        }
    }

    private fun parameters(): String = "$VIDEO\r\n$AUDIO\r\n" +
        "wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${config.rtpPort} 0 mode=play\r\n" +
        "wfd_presentation_URL: rtsp://${socket.localAddress.hostAddress}/wfd1.0/streamid=0 none\r\n" +
        "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;hidc_cap_list=none;port=4321;uibc_encrypted=true\r\n"

    @Synchronized private fun request(first: String, kind: String, body: String? = null, extra: String = "") {
        check(pending.size < 16) { "Too many pending RTSP requests" }
        val cseq = sequence++
        pending[cseq] = kind
        write(first, cseq, body, extra)
    }
    @Synchronized private fun response(cseq: Int, body: String? = null, extra: String = "", status: String = "200 OK") =
        write("RTSP/1.0 $status", cseq, body, extra)

    private fun write(first: String, cseq: Int, body: String?, extra: String) {
        val bytes = body?.toByteArray(Charsets.ISO_8859_1)
        val header = "$first\r\nCSeq: $cseq\r\n$extra" +
            (if (bytes != null) "Content-Type: text/parameters\r\nContent-Length: ${bytes.size}\r\n" else "") + "\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); if (bytes != null) write(bytes); flush() }
    }

    override fun close() {
        stopped = true
        timer.shutdownNow()
        runCatching { socket.close() }
    }

    internal data class Message(val first: String, val headers: Map<String, String>, val body: String)
    companion object {
        private const val VIDEO = "wfd_video_formats: 28 00 01 01 ffffffff ffffffff ffffffff 00 0000 0000 00 none none"
        private const val AUDIO = "wfd_audio_codecs: AAC 00000001 00"
        // These fields have comma-list syntax. Singleton fields must remain unambiguous.
        private val LIST_HEADERS = setOf("public", "allow", "require", "proxy-require", "supported", "unsupported")

        internal fun capabilities(base: IccoaVideoConfig, text: String): IccoaVideoConfig {
            var config = base
            Regex("display=(\\d+):(\\d+);dpi=(\\d+);fps=(\\d+)").find(text)?.groupValues?.let {
                val width = it[1].toInt(); val height = it[2].toInt(); val fps = it[4].toInt()
                require(width in 320..4096 && height in 240..2160 && fps in 1..120) { "Unsupported car display" }
                config = config.copy(width = width, height = height, fps = fps.coerceAtMost(60))
            }
            Regex("RTP/AVP/TCP;[^\\r\\n]*?\\s(\\d+)\\s+\\d+\\s+mode=play", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toInt()?.let { require(it in 1..65535); config = config.copy(rtpPort = it) }
            return config
        }

        internal fun read(input: DataInputStream): Message {
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val byte = input.readUnsignedByte()
                    if (byte == 10) break
                    require(bytes.size() < 8192) { "RTSP line too long" }
                    if (byte != 13) bytes.write(byte)
                }
                return bytes.toString("ISO-8859-1")
            }
            val first = line()
            require(first.isNotBlank()) { "Empty RTSP message" }
            val headers = linkedMapOf<String, String>()
            repeat(65) { index ->
                val line = line()
                if (line.isEmpty()) {
                    val size = headers["content-length"]?.let { requireNotNull(it.toIntOrNull()) } ?: 0
                    require(size in 0..65536) { "RTSP body too large" }
                    val body = ByteArray(size); input.readFully(body)
                    return Message(first, headers, body.toString(Charsets.ISO_8859_1))
                }
                require(index < 64 && ':' in line) { "Invalid RTSP headers" }
                val name = line.substringBefore(':').trim().lowercase(Locale.ROOT)
                require(name.isNotEmpty()) { "Empty RTSP header name" }
                val value = line.substringAfter(':').trim()
                val previous = headers[name]
                headers[name] = when {
                    previous == null -> value
                    name in LIST_HEADERS -> "$previous, $value"
                    else -> {
                        require(previous == value) { "Conflicting RTSP header: $name" }
                        previous
                    }
                }
            }
            error("RTSP headers too long")
        }
    }
}
