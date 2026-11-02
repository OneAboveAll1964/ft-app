package app.ft.iccoa

import android.net.Network
import app.ft.core.DiagLog
import app.ft.projection.CarTouch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns only WFD sockets and bounded media queues; no CarLife service or framing. */
internal class IccoaMediaTransport(
    private val network: Network,
    private val peer: String,
    private val decryptInput: (ByteArray) -> ByteArray,
    private val onPlay: (IccoaVideoConfig) -> Unit,
    private val onPause: () -> Unit,
    private val onKeyframe: () -> Unit,
    private val onTouch: (CarTouch) -> Unit,
    private val onKey: (Int) -> Unit,
    private val onClosed: (String) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val rtpPacketCount = AtomicLong(0)
    private val rtpVideoFrames = AtomicLong(0)
    private val rtpAudioFrames = AtomicLong(0)
    private val rtpKeyFrames = AtomicLong(0)
    private val executor = Executors.newCachedThreadPool { Thread(it, "ICCOA-media") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "ICCOA-RTP-watch") }
    private val queue = IccoaStreamQueue()
    private var rtspServer: ServerSocket? = null
    private var inputServer: IccoaInputServer? = null
    @Volatile private var rtsp: IccoaRtsp? = null
    @Volatile private var videoSocket: Socket? = null
    @Volatile private var muxer: IccoaTsMuxer? = null
    @Volatile private var codecConfig = byteArrayOf()
    @Volatile private var writingAt = 0L
    @Volatile private var aac: IccoaAacEncoder? = null
    @Volatile private var videoConfig: IccoaVideoConfig? = null

    /** Must complete before AuthConfirm tells the car our ports are ready. */
    fun start() {
        try {
            rtspServer = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(7236), 2) }
            inputServer = IccoaInputServer(InetSocketAddress(4321), InetAddress.getByName(peer),
                ::input, { DiagLog.i("ICCOA", it) })
            executor.execute {
                accept(rtspServer!!) { socket ->
                    val session = IccoaRtsp(socket, ::play, { onPause() }, onKeyframe, ::fail)
                    synchronized(this) {
                        rtsp?.close()
                        rtsp = session
                    }
                    session.run()
                }
            }
            executor.execute {
                try { inputServer!!.run() }
                catch (e: Exception) { if (!closed.get()) fail("UIBC 4321: ${e.javaClass.simpleName}") }
            }
            executor.execute { output() }
            watchdog.scheduleAtFixedRate({
                val started = writingAt
                if (started > 0 && System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)) {
                    runCatching { videoSocket?.close() }
                    fail("RTP write timed out")
                }
            }, 1, 1, TimeUnit.SECONDS)
        } catch (e: Exception) { close(); throw e }
    }

    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) {
        try {
            while (!closed.get()) {
                val socket = server.accept()
                if (socket.inetAddress.hostAddress != peer) { socket.close(); continue }
                DiagLog.i("ICCOA", "RTSP 7236 connected")
                // RTSP owns one connection; a fresh session creates a fresh listener.
                try { handler(socket) } finally { runCatching { socket.close() } }
                return
            }
        } catch (e: Exception) { if (!closed.get()) fail("RTSP listener 7236: ${e.javaClass.simpleName}") }
    }

    private fun play(config: IccoaVideoConfig) {
        videoConfig = config
        if (muxer != null) { onPlay(config); onKeyframe(); return }
        val socket = Socket()
        synchronized(this) {
            if (closed.get()) { socket.close(); return }
            videoSocket = socket
        }
        try {
            network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
            socket.connect(InetSocketAddress(config.host, config.rtpPort), 4000)
            val sender = IccoaTsMuxer(BufferedOutputStream(socket.getOutputStream(), 65536))
            val encoder = IccoaAacEncoder(queue::audio, ::fail)
            synchronized(this) {
                if (closed.get()) { encoder.close(); socket.close(); return }
                aac = encoder
                muxer = sender
            }
            DiagLog.i("ICCOA", "WFD PLAY ${config.width}x${config.height}@${config.fps}; RTP/TCP ${config.rtpPort}")
            onPlay(config)
        } catch (e: Exception) { runCatching { socket.close() }; fail("RTP: ${e.javaClass.simpleName}") }
    }

    fun configure(bytes: ByteArray) {
        codecConfig = bytes.copyOf()
        DiagLog.i("ICCOA", "H264 CONFIG_RECEIVED bytes=${bytes.size}")
    }

    /** Takes ownership of a fresh encoded array from SurfaceEncoder. */
    fun frame(data: ByteArray, key: Boolean, ptsUs: Long) {
        if (closed.get() || muxer == null) return
        if (queue.video(data, key, ptsUs)) {
            DiagLog.i("ICCOA", "RTP queue overflow; dropping GOP and requesting keyframe")
            onKeyframe()
        }
    }

    fun audio(pcm: ByteArray) {
        if (closed.get()) return
        val epoch = queue.audioEpoch() ?: return
        aac?.pcm(pcm, epoch)
    }

    fun pause() { queue.pause() }
    fun resume() {
        if (closed.get()) return
        queue.resume(System.nanoTime() / 1000)
        onKeyframe()
    }

    private fun output() {
        var sentFirstFrame = false
        var sentFirstAudio = false
        var appliedConfig: ByteArray? = null
        var lastSlowWrite = 0L
        var reportAt = System.nanoTime()
        var videoFrames = 0
        var videoBytes = 0L
        var maxVideoAgeMs = 0L
        var maxWriteMs = 0L
        try {
            while (!closed.get()) {
                val frame = queue.poll(100) ?: continue
                if (!queue.current(frame)) continue
                val sender = muxer ?: continue
                writingAt = System.nanoTime()
                val beforePackets = sender.writtenRtpPackets
                if (frame.video) {
                    val config = codecConfig
                    if (appliedConfig !== config) {
                        sender.configure(config)
                        appliedConfig = config
                    }
                    sender.video(frame.data, frame.key, frame.pts)
                } else sender.audio(frame.data, frame.pts)
                val finished = System.nanoTime()
                rtpPacketCount.addAndGet(sender.writtenRtpPackets - beforePackets)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(finished - writingAt)
                writingAt = 0
                maxWriteMs = maxOf(maxWriteMs, elapsedMs)
                if (frame.video) {
                    rtpVideoFrames.incrementAndGet()
                    if (frame.key && rtpKeyFrames.incrementAndGet() == 1L)
                        DiagLog.i("ICCOA", "RTP FIRST_KEY_FRAME_WRITTEN packets=${rtpPacketCount.get()} bytes=${frame.data.size}")
                    videoFrames++
                    videoBytes += frame.data.size
                    maxVideoAgeMs = maxOf(maxVideoAgeMs, (finished / 1000 - frame.pts) / 1000)
                } else rtpAudioFrames.incrementAndGet()
                val reportMs = TimeUnit.NANOSECONDS.toMillis(finished - reportAt)
                if (reportMs >= 10_000) {
                    DiagLog.i("ICCOA", "RTP last ${reportMs}ms: video fps=${videoFrames * 1000L / reportMs} kbps=${videoBytes * 8 / reportMs} maxAge=${maxVideoAgeMs}ms maxWrite=${maxWriteMs}ms packetsTotal=${rtpPacketCount.get()} videoTotal=${rtpVideoFrames.get()} audioTotal=${rtpAudioFrames.get()} keyTotal=${rtpKeyFrames.get()}")
                    reportAt = finished
                    videoFrames = 0; videoBytes = 0; maxVideoAgeMs = 0; maxWriteMs = 0
                }
                if (elapsedMs >= 100 && finished - lastSlowWrite > TimeUnit.SECONDS.toNanos(5)) {
                    lastSlowWrite = finished
                    DiagLog.i("ICCOA", "RTP slow write ${elapsedMs}ms; bytes=${frame.data.size} video=${frame.video}")
                }
                if (frame.video && !sentFirstFrame) {
                    sentFirstFrame = true
                    DiagLog.i("ICCOA", "RTP first video frame written; bytes=${frame.data.size} key=${frame.key}")
                }
                if (!frame.video && !sentFirstAudio) {
                    sentFirstAudio = true
                    DiagLog.i("ICCOA", "RTP first AAC frame written; bytes=${frame.data.size}")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) { if (!closed.get()) fail("Media output: ${e.javaClass.simpleName}") }
    }

    private fun input(socket: Socket) {
        socket.soTimeout = 0
        var lastTouch: CarTouch? = null
        val gesture = IccoaInputGesture {
            lastTouch?.let { onTouch(it.copy(action = CarTouch.UP)) }
            DiagLog.i("ICCOA", "UIBC clean-eof; released active gesture")
        }
        val json = IccoaInputJson { event ->
            if (event.optInt("type") == 1) {
                val video = videoConfig ?: return@IccoaInputJson
                val nativeTouch = IccoaInputEvent.touch(event, video) ?: return@IccoaInputJson
                val plan = IccoaVideoPlan.from(video)
                fun sx(x: Int) = if (x < 0) -1 else (x.toLong() * plan.streamWidth / video.width).toInt()
                fun sy(y: Int) = if (y < 0) -1 else (y.toLong() * plan.streamHeight / video.height).toInt()
                val touch = nativeTouch.copy(x = sx(nativeTouch.x), y = sy(nativeTouch.y),
                    x2 = sx(nativeTouch.x2), y2 = sy(nativeTouch.y2))
                if (touch.action != CarTouch.MOVE) DiagLog.i("ICCOA",
                    "UIBC touch action=${touch.action} x=${touch.x} y=${touch.y} dst=${video.width}x${video.height}" +
                        " source=${event.optInt("width")}x${event.optInt("height")} raw=${event.optDouble("x0")},${event.optDouble("y0")}")
                gesture.accept(touch.action)
                lastTouch = touch
                onTouch(touch)
            } else if (event.optInt("keycode") > 0 && event.optInt("action") == 0) {
                onKey(event.optInt("keycode"))
            }
        }
        IccoaInputReader.read(socket.getInputStream(), decryptInput, json) { DiagLog.i("ICCOA", it) }
        gesture.finish()
    }

    private fun fail(reason: String) { if (!closed.get()) onClosed(reason) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        DiagLog.i("ICCOA", "RTP CLOSE packets=${rtpPacketCount.get()} videoFrames=${rtpVideoFrames.get()} keyFrames=${rtpKeyFrames.get()} audioFrames=${rtpAudioFrames.get()}")
        runCatching { rtspServer?.close() }; runCatching { inputServer?.close() }
        runCatching { rtsp?.close() }; runCatching { videoSocket?.close() }
        synchronized(this) { aac?.close(); aac = null }
        muxer = null; queue.pause(); codecConfig = byteArrayOf()
        watchdog.shutdownNow(); executor.shutdownNow()
    }
}

/** Balanced JSON objects can span TCP records; quoted braces do not delimit an object. */
internal class IccoaInputJson(private val emit: (JSONObject) -> Unit) {
    private val buffer = StringBuilder()
    fun finish() {
        if (buffer.isNotBlank()) throw java.io.EOFException("Incomplete UIBC JSON")
    }
    fun accept(bytes: ByteArray) {
        buffer.append(bytes.toString(Charsets.UTF_8))
        require(buffer.length <= 65536) { "UIBC object too large" }
        var depth = 0; var quoted = false; var escaped = false; var start = -1; var consumed = 0
        for (i in buffer.indices) {
            val c = buffer[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> { if (depth++ == 0) start = i }
                '}' -> if (depth > 0 && --depth == 0) {
                    emit(JSONObject(buffer.substring(start, i + 1))); consumed = i + 1
                }
            }
        }
        if (consumed > 0) buffer.delete(0, consumed)
        if (depth == 0 && buffer.length > 4096) buffer.clear()
    }
}
