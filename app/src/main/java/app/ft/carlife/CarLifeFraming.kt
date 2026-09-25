package app.ft.carlife

import app.ft.core.Bytes

object CarLifeFraming {
    data class Cmd(val serviceId: Int, val payload: ByteArray)
    data class Stream(val serviceId: Int, val timestamp: Int, val payload: ByteArray)

    fun headLen(channel: Int): Int =
        if (channel == CarLifeProtocol.CH_CMD || channel == CarLifeProtocol.CH_CTRL) CarLifeProtocol.HEAD_CMD else CarLifeProtocol.HEAD_STREAM

    fun bodyLen(channel: Int, head: ByteArray): Int =
        if (headLen(channel) == CarLifeProtocol.HEAD_CMD) Bytes.u16(head, 0) else Bytes.u32(head, 0)

    fun maxBody(channel: Int): Int =
        if (headLen(channel) == CarLifeProtocol.HEAD_CMD) CarLifeProtocol.MAX_CMD_BODY else CarLifeProtocol.MAX_STREAM_BODY

    fun cmd(serviceId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 0xffff)
        val out = ByteArray(CarLifeProtocol.HEAD_CMD + payload.size)
        Bytes.putU16(payload.size, out, 0)
        Bytes.putU32(serviceId, out, 4)
        System.arraycopy(payload, 0, out, CarLifeProtocol.HEAD_CMD, payload.size)
        return out
    }

    fun stream(serviceId: Int, payload: ByteArray, timestamp: Int = System.currentTimeMillis().toInt()): ByteArray {
        val out = ByteArray(CarLifeProtocol.HEAD_STREAM + payload.size)
        Bytes.putU32(payload.size, out, 0)
        Bytes.putU32(timestamp, out, 4)
        Bytes.putU32(serviceId, out, 8)
        System.arraycopy(payload, 0, out, CarLifeProtocol.HEAD_STREAM, payload.size)
        return out
    }

    fun parseCmd(head: ByteArray, body: ByteArray): Cmd = Cmd(Bytes.u32(head, 4), body)

    fun parseStream(head: ByteArray, body: ByteArray): Stream = Stream(Bytes.u32(head, 8), Bytes.u32(head, 4), body)

    fun serviceId(channel: Int, head: ByteArray): Int =
        if (headLen(channel) == CarLifeProtocol.HEAD_CMD) Bytes.u32(head, 4) else Bytes.u32(head, 8)

}
