package app.ft.iccoa

import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** One UCAR listener, scoped to an authenticated session and its Wi-Fi peer. */
internal class IccoaLaneServer(
    private val port: Int,
    bindAddress: InetSocketAddress,
    private val peer: InetAddress,
    private val onConnected: (Socket) -> Unit,
    private val onDisconnected: (Socket) -> Unit,
    private val onFrame: (IccoaControl.Frame) -> Unit,
    private val log: (String) -> Unit
) : Closeable {
    private val server = ServerSocket()
    private val lock = Any()
    @Volatile private var closed = false
    private var connection: Socket? = null

    init {
        try {
            server.reuseAddress = true
            server.bind(bindAddress, 1)
        } catch (e: Exception) {
            server.close()
            throw e
        }
    }

    val localPort: Int get() = server.localPort

    /** Blocks until stopped or a fatal transport/protocol error occurs. */
    fun run() {
        while (!closed) {
            val socket = server.accept()
            if (socket.inetAddress != peer) { socket.close(); continue }
            synchronized(lock) {
                if (closed) { socket.close(); return }
                connection = socket
            }
            var stage = "connected"
            var received = 0
            try {
                socket.tcpNoDelay = true
                onConnected(socket)
                log("LANE $port connected")
                var logged = 0
                val controlCounts = mutableMapOf<Int, Int>()
                while (!closed) {
                    stage = "read-header"
                    val frame = IccoaControl.readOrNull(socket.getInputStream()) { stage = "header-read" }
                    if (frame == null) {
                        log("LANE $port clean-eof frames=$received")
                        if (port == CONTROL_PORT) throw EOFException("ICCOA control channel closed")
                        // XA60 may close an auxiliary lane immediately after AuthConfirm.
                        // Keep its listener and the other lanes alive for the next connection.
                        log("LANE $port waiting-for-reconnect")
                        break
                    }
                    received++
                    // A long-lived lane must still reveal late voice/phone/config events.
                    // Retain a bounded count per method, sampling repeats at powers of two.
                    val controlCount = if (frame.category == 1 && frame.method != 1 &&
                        (frame.method in controlCounts || controlCounts.size < 64)) {
                        ((controlCounts[frame.method] ?: 0) + 1).also { controlCounts[frame.method] = it }
                    } else 0
                    val logControl = controlCount in 1..3 ||
                        (controlCount > 0 && controlCount and (controlCount - 1) == 0)
                    val logOther = frame.category != 1 && logged < 12
                    if (logControl || logOther) {
                        log("LANE $port frame category=${frame.category} method=${frame.method} type=${frame.type} format=${frame.format} bytes=${frame.body.size}")
                        if (logOther) logged++
                    }
                    stage = "dispatch"
                    onFrame(frame)
                }
            } catch (e: Exception) {
                if (!closed) log("LANE $port failed stage=$stage frames=$received ${IccoaTransportError.describe(e)}")
                throw e
            } finally {
                runCatching { socket.close() }
                synchronized(lock) { if (connection === socket) connection = null }
                onDisconnected(socket)
            }
        }
    }

    override fun close() {
        val socket = synchronized(lock) {
            closed = true
            connection.also { connection = null }
        }
        runCatching { server.close() }
        runCatching { socket?.close() }
    }

    companion object {
        const val CONTROL_PORT = 57219
        const val AUDIO_PORT = 57229
    }
}
