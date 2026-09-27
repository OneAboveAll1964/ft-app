package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter

class CarWirelessSetup(
    private val send: (ByteArray) -> Unit,
    private val localIp: () -> String?,
    private val onCarWifiName: (String) -> Unit,
    private val progress: (String) -> Unit = {}
) {
    private val tag = "CarBT"
    private val buffer = ArrayList<Byte>(1024)
    private var askedForName = false

    fun reset() {
        buffer.clear()
        askedForName = false
    }

    fun hello() {
        progress("Telling the head unit this phone is ready")
        send(CarLifeFraming.cmd(MD_READY))
    }

    fun feed(chunk: ByteArray) {
        for (b in chunk) buffer.add(b)
        while (true) {
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
