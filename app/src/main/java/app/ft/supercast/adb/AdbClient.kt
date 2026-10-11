package app.ft.supercast.adb

import app.ft.core.DiagLog
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbClient(private val keys: AdbKeys, private val host: String = "127.0.0.1", private val port: Int = 5555) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var nextId = 1

    val connected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(timeoutMs: Int = 8000): Result {
        close()
        return runCatching {
            val made = Socket()
            made.tcpNoDelay = true
            made.connect(InetSocketAddress(host, port), timeoutMs)
            made.soTimeout = timeoutMs
            socket = made
            input = DataInputStream(made.getInputStream().buffered())
            output = made.getOutputStream()
            send(AdbWire.connect())
            handshake(timeoutMs)
        }.getOrElse {
            close()
            DiagLog.w(TAG, "could not reach the phone's debug bridge: ${it.message}")
            Result.UNREACHABLE
        }
    }

    private fun handshake(timeoutMs: Int): Result {
        var sentSignature = false
        val deadline = System.currentTimeMillis() + timeoutMs.toLong() * 3
        while (System.currentTimeMillis() < deadline) {
            val message = read() ?: return Result.UNREACHABLE
            when (message.command) {
                AdbWire.CNXN -> {
                    DiagLog.i(TAG, "the phone's debug bridge accepted FT")
                    return Result.READY
                }
                AdbWire.AUTH -> when {
                    message.arg0 != AdbWire.AUTH_TOKEN -> Unit
                    !sentSignature -> {
                        sentSignature = true
                        send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_SIGNATURE, 0, keys.sign(message.payload)))
                    }
                    else -> {
                        send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_PUBLIC_KEY, 0, keys.line(NAME)))
                        DiagLog.i(TAG, "waiting for you to allow FT on the phone's debug prompt")
                        return waitForAccept(deadline)
                    }
                }
                else -> Unit
            }
        }
        return Result.TIMED_OUT
    }

    private fun waitForAccept(deadline: Long): Result {
        while (System.currentTimeMillis() < deadline) {
            val message = runCatching { read() }.getOrNull() ?: continue
            if (message.command == AdbWire.CNXN) {
                DiagLog.i(TAG, "FT was allowed on the phone's debug bridge")
                return Result.READY
            }
        }
        return Result.NOT_ALLOWED
    }

    fun shell(command: String, waitMs: Int = 8000): String? {
        val id = open("shell:$command") ?: return null
        val text = StringBuilder()
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val message = runCatching { read() }.getOrNull() ?: break
            when (message.command) {
                AdbWire.WRTE -> {
                    text.append(String(message.payload))
                    send(AdbWire.okay(id.first, id.second))
                }
                AdbWire.CLSE -> return text.toString().trim()
                else -> Unit
            }
        }
        return text.toString().trim()
    }

    fun push(local: File, remote: String, mode: Int = 493): Boolean {
        val id = open("sync:") ?: return false
        return runCatching {
            val header = "$remote,$mode"
            writeSync("SEND", header.length, id)
            sendData(header.toByteArray(), id)
            local.inputStream().buffered().use { stream ->
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    writeSync("DATA", read, id)
                    sendData(chunk.copyOf(read), id)
                }
            }
            writeSync("DONE", (System.currentTimeMillis() / 1000).toInt(), id)
            drain(id)
            DiagLog.i(TAG, "sent the helper to the phone's own storage")
            true
        }.onFailure { DiagLog.w(TAG, "could not send the helper: ${it.message}") }.getOrDefault(false)
    }

    private fun writeSync(tag: String, value: Int, id: Pair<Int, Int>) {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tag.toByteArray())
        buffer.putInt(value)
        sendData(buffer.array(), id)
    }

    private fun sendData(data: ByteArray, id: Pair<Int, Int>) {
        send(AdbWire.write(id.first, id.second, data))
        waitFor(AdbWire.OKAY)
    }

    private fun drain(id: Pair<Int, Int>) {
        runCatching {
            val message = read()
            if (message != null && message.command == AdbWire.WRTE) send(AdbWire.okay(id.first, id.second))
        }
    }

    private fun open(service: String): Pair<Int, Int>? {
        val local = nextId++
        send(AdbWire.open(local, service))
        val answer = waitFor(AdbWire.OKAY) ?: return null
        return local to answer.arg0
    }

    private fun waitFor(command: Int, tries: Int = 8): AdbMessage? {
        repeat(tries) {
            val message = runCatching { read() }.getOrNull() ?: return null
            if (message.command == command) return message
            if (message.command == AdbWire.CLSE) return null
        }
        return null
    }

    private fun send(message: AdbMessage) {
        val stream = output ?: throw IOException("not connected")
        stream.write(AdbWire.frame(message))
        stream.flush()
    }

    private fun read(): AdbMessage? {
        val stream = input ?: return null
        val header = ByteArray(AdbWire.HEADER)
        stream.readFully(header)
        val fields = AdbWire.readHeader(header)
        if (!AdbWire.headerValid(fields)) throw IOException("bad adb header")
        val payload = ByteArray(fields[3])
        if (payload.isNotEmpty()) stream.readFully(payload)
        return AdbMessage(fields[0], fields[1], fields[2], payload)
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    enum class Result { READY, NOT_ALLOWED, UNREACHABLE, TIMED_OUT }

    companion object {
        private const val TAG = "SuperCast"
        private const val NAME = "FT"
        private const val CHUNK = 15360
    }
}
