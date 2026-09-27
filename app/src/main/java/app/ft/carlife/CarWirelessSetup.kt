package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter

class CarWirelessSetup(
    private val send: (ByteArray) -> Boolean,
    private val localIp: () -> String?,
    private val onCarWifiName: (String) -> Unit,
    private val progress: (String) -> Unit = {}
) {
    private val tag = "CarBT"
    private val buffer = ArrayList<Byte>(1024)
    private var askedForName = false
    private var vendorPolls = 0
    private var described = false

    fun reset() {
        buffer.clear()
        askedForName = false
        vendorPolls = 0
        described = false
    }

    fun hello(): Boolean {
        val ok = send(CarLifeFraming.cmd(MD_READY))
        if (ok) progress("Telling the head unit this phone is ready") else DiagLog.d(tag, "no bluetooth link to greet the head unit on")
        return ok
    }

    fun feed(chunk: ByteArray) {
        for (b in chunk) buffer.add(b)
        while (true) {
            if (vendorFrame()) continue
            if (buffer.size < CarLifeProtocol.HEAD_CMD) return
            val head = ByteArray(CarLifeProtocol.HEAD_CMD) { buffer[it] }
            val len = Bytes.u16(head, 0)
            val marker = Bytes.u16(head, 2)
            if (marker != 0 || len > MAX_BODY) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < CarLifeProtocol.HEAD_CMD + len) return
            val body = ByteArray(len) { buffer[CarLifeProtocol.HEAD_CMD + it] }
            repeat(CarLifeProtocol.HEAD_CMD + len) { buffer.removeAt(0) }
            handle(Bytes.u32(head, 4), body)
        }
    }

    private fun vendorFrame(): Boolean {
        if (buffer.size < 4) return false
        if ((buffer[0].toInt() and 0xFF) != 0xFF) return false
        val kind = buffer[1].toInt() and 0xFF
        if (kind == VENDOR_POLL) {
            val len = buffer[2].toInt() and 0xFF
            val total = 4 + len
            if (buffer.size < total) return false
            val frame = ByteArray(total) { buffer[it] }
            repeat(total) { buffer.removeAt(0) }
            var sum = len
            for (i in 0 until len) sum += frame[3 + i].toInt() and 0xFF
            val want = (-sum) and 0xFF
            val got = frame[total - 1].toInt() and 0xFF
            DiagLog.rx(tag, "head unit call${if (want != got) " (checksum $got wanted $want)" else ""}", frame)
            vendorPolls++
            if (vendorPolls == 1) progress("Head unit is calling over bluetooth, answering it")
            send(frame)
            return true
        }
        if (kind == VENDOR_REPLY) {
            if (buffer.size < 4) return false
            val total = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            if (total < 5 || total > MAX_BODY) {
                buffer.removeAt(0)
                return true
            }
            if (buffer.size < total) return false
            val frame = ByteArray(total) { buffer[it] }
            repeat(total) { buffer.removeAt(0) }
            val body = frame.copyOfRange(4, total)
            if (!described) {
                described = true
                DiagLog.rx(tag, "head unit capability", frame)
                readReply(body)
            }
            send(frame)
            return true
        }
        return false
    }

    private fun readReply(body: ByteArray) {
        if (body.size >= 15) {
            val video = ((body[9].toInt() and 0xFF) shl 8) or (body[10].toInt() and 0xFF)
            val audio = ((body[11].toInt() and 0xFF) shl 8) or (body[12].toInt() and 0xFF)
            val fps = body[13].toInt() and 0xFF
            if (video in 200..20000 && fps in 10..120) {
                progress("Head unit offers $video kbps video at $fps fps")
                DiagLog.i(tag, "head unit capability: video $video kbps, audio $audio kbps, $fps fps")
                return
            }
        }
        plainText(body)
    }

    private fun plainText(body: ByteArray) {
        val text = StringBuilder()
        var run = StringBuilder()
        for (b in body) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else {
                if (run.length >= 4) text.append(run).append(' ')
                run = StringBuilder()
            }
        }
        if (run.length >= 4) text.append(run)
        if (text.isNotEmpty()) DiagLog.i(tag, "head unit said: ${text.toString().trim()}")
    }

    private fun vendor(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = 0xFF.toByte()
        out[1] = VENDOR_POLL.toByte()
        out[2] = body.size.toByte()
        System.arraycopy(body, 0, out, 3, body.size)
        var sum = body.size
        for (b in body) sum += b.toInt() and 0xFF
        out[out.size - 1] = ((-sum) and 0xFF).toByte()
        return out
    }

    private fun handle(serviceId: Int, body: ByteArray) {
        when (serviceId) {
            HU_READY -> {
                progress("Head unit answered, asking what wireless it offers")
                send(CarLifeFraming.cmd(MD_READY))
                send(CarLifeFraming.cmd(MD_WIRELESS_INFO_REQUEST))
            }
            HU_BYE -> DiagLog.i(tag, "head unit closed the bluetooth wireless link")
            HU_WIRELESS_INFO -> {
                progress("Asking the head unit to switch on WiFi Direct")
                askForName()
            }
            HU_WIFI_DIRECT_NAME -> {
                val r = ProtoReader(body)
                val name = r.string(1) ?: r.string(2) ?: ""
                val p2p = r.string(4) ?: ""
                if (name.isBlank()) {
                    DiagLog.w(tag, "head unit sent no WiFi Direct name")
                } else {
                    progress("Head unit switched on WiFi Direct as '$name'")
                    onCarWifiName(name)
                }
            }
            HU_IP_REQUEST -> {
                val ip = localIp()
                if (ip == null) {
                    DiagLog.w(tag, "head unit asked for this phone's address but there is none yet")
                } else {
                    progress("Telling the head unit this phone is at $ip")
                    send(CarLifeFraming.cmd(MD_WIFI_IP, ProtoWriter().string(1, ip).toByteArray()))
                }
            }
            HU_STATUS -> DiagLog.i(tag, "head unit wireless status ${ProtoReader(body).int(1, -1)}")
            else -> DiagLog.rx(tag, "bluetooth ${CarLifeProtocol.name(serviceId)}", body)
        }
    }

    fun askForName() {
        if (askedForName) return
        askedForName = true
        send(CarLifeFraming.cmd(MD_WIFI_DIRECT_NAME_REQUEST))
    }

    fun tellStatus(status: Int) {
        send(CarLifeFraming.cmd(MD_STATUS, ProtoWriter().int32(1, status).toByteArray()))
    }

    companion object {
        private const val MAX_BODY = 4096
        private const val VENDOR_POLL = 0x55
        private const val VENDOR_REPLY = 0x5A
        const val MD_READY = 0x0A
        const val HU_READY = 0x0A
        const val HU_BYE = 0x0B
        const val MD_WIRELESS_INFO_REQUEST = 0x00100001
        const val HU_WIRELESS_INFO = 0x00108002
        const val MD_WIFI_DIRECT_NAME_REQUEST = 0x00100004
        const val HU_WIFI_DIRECT_NAME = 0x00108005
        const val HU_IP_REQUEST = 0x00108006
        const val MD_WIFI_IP = 0x00100007
        const val MD_STATUS = 0x00100008
        const val HU_STATUS = 0x00108009
    }
}
