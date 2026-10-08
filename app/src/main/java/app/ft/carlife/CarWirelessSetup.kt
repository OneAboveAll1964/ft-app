package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter

class CarWirelessSetup(
    private val send: (ByteArray) -> Boolean,
    private val onCarWifiName: (String) -> Unit,
    private val progress: (String) -> Unit = {},
    private val onCarReady: () -> Unit = {}
) {
    private val tag = "CarBT"
    private val buffer = ArrayList<Byte>(1024)
    @Volatile var carWifiName: String? = null
        private set
    @Volatile private var ipWanted = false
    @Volatile private var ipSent = false
    @Volatile private var phoneIp: String? = null

    fun reset() {
        synchronized(buffer) { buffer.clear() }
        carWifiName = null
        ipWanted = false
        ipSent = false
    }

    fun begin(): Boolean {
        val ok = send(CarLifeFraming.cmd(MD_WIRELESS_INFO_REQUEST))
        if (ok) progress("Asking the car to turn on WiFi Direct") else DiagLog.d(tag, "no bluetooth link to ask the car on")
        return ok
    }

    fun askName(): Boolean {
        carWifiName = null
        val ok = send(CarLifeFraming.cmd(MD_TARGET_INFO_REQUEST))
        if (ok) progress("Asking the car for its WiFi Direct name again")
        return ok
    }

    fun wifiDirectReady(ip: String?) {
        phoneIp = ip
        if (ip != null && ipWanted) sendIp()
    }

    fun feed(chunk: ByteArray) {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        synchronized(buffer) {
            for (b in chunk) buffer.add(b)
            while (buffer.size >= CarLifeProtocol.HEAD_CMD) {
                val head = ByteArray(CarLifeProtocol.HEAD_CMD) { buffer[it] }
                val len = Bytes.u16(head, 0)
                if (len > MAX_BODY || Bytes.u16(head, 2) != 0) {
                    buffer.removeAt(0)
                    continue
                }
                if (buffer.size < CarLifeProtocol.HEAD_CMD + len) break
                val body = ByteArray(len) { buffer[CarLifeProtocol.HEAD_CMD + it] }
                repeat(CarLifeProtocol.HEAD_CMD + len) { buffer.removeAt(0) }
                frames += Bytes.u32(head, 4) to body
            }
        }
        frames.forEach { (id, body) -> handle(id, body) }
    }

    private fun handle(serviceId: Int, body: ByteArray) {
        when (serviceId) {
            HU_WIRELESS_INFO -> {
                val r = ProtoReader(body)
                val type = r.int(1, -1)
                DiagLog.i(tag, "car wireless info: type $type, band ${r.int(2, 0)}")
                if (carWifiName != null) {
                    if (type in 2..4) onCarReady()
                    return
                }
                progress("Asking the car for its WiFi Direct name")
                send(CarLifeFraming.cmd(MD_TARGET_INFO_REQUEST))
            }
            HU_TARGET_INFO -> {
                if (carWifiName != null) {
                    DiagLog.d(tag, "car repeated its WiFi Direct name")
                    return
                }
                val r = ProtoReader(body)
                val name = r.string(1).orEmpty().trim()
                DiagLog.i(tag, "car WiFi Direct: name '$name', info '${r.string(2).orEmpty()}', network '${r.string(4).orEmpty()}'")
                if (name.isBlank()) {
                    DiagLog.w(tag, "the car sent no WiFi Direct name")
                    return
                }
                carWifiName = name
                progress("The car is on WiFi Direct as '$name'")
                onCarWifiName(name)
            }
            HU_IP_REQUEST -> {
                ipWanted = true
                if (phoneIp != null) sendIp() else progress("The car is waiting for WiFi Direct to connect")
            }
            HU_STATUS -> DiagLog.i(tag, "car wireless status ${ProtoReader(body).int(1, -1)}")
            else -> DiagLog.rx(tag, "bluetooth ${CarLifeProtocol.name(serviceId)}", body)
        }
    }

    @Synchronized
    private fun sendIp() {
        val ip = phoneIp ?: return
        if (ipSent) return
        ipSent = true
        if (send(CarLifeFraming.cmd(MD_WIFI_IP, ProtoWriter().string(1, ip).toByteArray()))) {
            progress("Told the car to connect to $ip")
        } else {
            ipSent = false
        }
    }

    companion object {
        private const val MAX_BODY = 4096
        const val MD_WIRELESS_INFO_REQUEST = 0x00100001
        const val HU_WIRELESS_INFO = 0x00108002
        const val MD_TARGET_INFO_REQUEST = 0x00100004
        const val HU_TARGET_INFO = 0x00108005
        const val HU_IP_REQUEST = 0x00108006
        const val MD_WIFI_IP = 0x00100007
        const val HU_STATUS = 0x00108009
    }
}
