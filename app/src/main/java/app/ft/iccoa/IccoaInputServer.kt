package app.ft.iccoa

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/** UIBC is optional: a complete stream may end without ending RTSP or video. */
internal class IccoaInputServer(
    bindAddress: InetSocketAddress,
    private val peer: InetAddress,
    private val read: (Socket) -> Unit,
    private val log: (String) -> Unit
) : Closeable {
    private val server = ServerSocket()
    private val lock = Any()
    @Volatile private var closed = false
    private var connection: Socket? = null

    init {
        try {
            server.reuseAddress = true
            server.bind(bindAddress, 2)
        } catch (e: Exception) { server.close(); throw e }
    }

    val localPort: Int get() = server.localPort

    fun run() {
        while (!closed) {
            val socket = server.accept()
            if (socket.inetAddress != peer) { socket.close(); continue }
            synchronized(lock) {
                if (closed) { socket.close(); return }
                connection = socket
            }
            try {
                socket.tcpNoDelay = true
                log("UIBC 4321 connected")
                read(socket) // Returns only on EOF at a complete message boundary.
                if (!closed) log("UIBC 4321 clean-eof; waiting-for-reconnect")
            } finally {
                runCatching { socket.close() }
                synchronized(lock) { if (connection === socket) connection = null }
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
}

/** Strict packet boundaries distinguish an unused/finished UIBC lane from a truncated packet. */
internal object IccoaInputReader {
    fun read(input: InputStream, decrypt: (ByteArray) -> ByteArray, json: IccoaInputJson,
             log: (String) -> Unit = {}) {
        val stream = DataInputStream(input)
        fun prefix(): ByteArray? {
            val first = stream.read()
            if (first < 0) return null
            return ByteArray(4).also { it[0] = first.toByte(); stream.readFully(it, 1, 3) }
        }
        var head = prefix() ?: return
        if (ByteBuffer.wrap(head).int == 12) {
            log("UIBC mode=encrypted")
            while (true) {
                val ivLength = ByteBuffer.wrap(head).int
                require(ivLength == 12) { "Invalid UIBC IV" }
                val iv = ByteArray(ivLength); stream.readFully(iv)
                val size = stream.readInt(); require(size in 17..65536) { "Invalid UIBC size" }
                val cipher = ByteArray(size); stream.readFully(cipher)
                val packet = ByteBuffer.allocate(8 + ivLength + size)
                    .putInt(ivLength).put(iv).putInt(size).put(cipher).array()
                json.accept(decrypt(packet))
                head = prefix() ?: break
            }
        } else {
            log("UIBC mode=plaintext")
            json.accept(head)
            val bytes = ByteArray(4096)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                json.accept(bytes.copyOf(n))
            }
        }
        json.finish()
    }
}

/** A complete UIBC lane can close independently; release held fingers before reconnecting. */
internal class IccoaInputGesture(private val release: () -> Unit = {}) {
    private var active = false
    fun accept(action: Int) {
        when (action and 255) {
            0 -> active = true
            1 -> active = false
            // CANCEL is not consistently supported by the projection backends yet.
        }
    }
    fun finish() {
        if (active) {
            active = false
            release()
        }
    }
}
