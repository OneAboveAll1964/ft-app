package app.ft.aa

import android.os.Build
import app.ft.core.Bytes
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter

object AaMessages {
    fun versionRequest(): ByteArray {
        val b = ByteArray(4)
        Bytes.putU16(AaProtocol.PROTOCOL_MAJOR, b, 0)
        Bytes.putU16(AaProtocol.PROTOCOL_MINOR, b, 2)
        return b
    }

    data class Version(val major: Int, val minor: Int, val status: Int)

    fun parseVersionResponse(b: ByteArray): Version =
        Version(if (b.size >= 2) Bytes.u16(b, 0) else 0, if (b.size >= 4) Bytes.u16(b, 2) else 0, if (b.size >= 6) Bytes.u16(b, 4) else -1)

    fun authComplete(): ByteArray = ProtoWriter().enum(1, AaProtocol.STATUS_OK).toByteArray()

    fun serviceDiscoveryResponse(width: Int, height: Int, fps: Int, density: Int, advertiseAudio: Boolean, name: String, controlsLeft: Boolean = true): ByteArray {
        val (fullW, fullH) = AaProtocol.standardSize(width, height)
        val resolution = when (fullW) {
            1920 -> AaProtocol.RES_1920x1080
            1280 -> AaProtocol.RES_1280x720
            else -> AaProtocol.RES_800x480
        }
        val marginW = (fullW - width).coerceAtLeast(0)
        val marginH = (fullH - height).coerceAtLeast(0)
        val video = ProtoWriter()
            .int32(1, AaProtocol.CH_VIDEO)
            .message(
                3, ProtoWriter()
                    .enum(1, AaProtocol.CODEC_H264)
                    .message(
                        4, ProtoWriter()
                            .enum(1, resolution)
                            .enum(2, if (fps >= 60) AaProtocol.FPS_60 else AaProtocol.FPS_30)
                            .uint32(3, marginW)
                            .uint32(4, marginH)
                            .uint32(5, density)
                    )
                    .bool(5, true)
            )
        val keycodes = listOf(3, 4, 5, 6, 19, 20, 21, 22, 23, 24, 25, 84, 85, 86, 87, 88, 126, 127, 65536, 65537, 65538)
        val input = ProtoWriter()
            .int32(1, AaProtocol.CH_INPUT)
            .message(
                4, ProtoWriter()
                    .packedInts(1, keycodes)
                    .message(2, ProtoWriter().int32(1, width).int32(2, height).int32(3, 1))
            )
        val sensor = ProtoWriter()
            .int32(1, AaProtocol.CH_SENSOR)
            .message(
                2, ProtoWriter()
                    .message(1, ProtoWriter().enum(1, AaProtocol.SENSOR_DRIVING_STATUS))
                    .message(1, ProtoWriter().enum(1, AaProtocol.SENSOR_NIGHT_MODE))
            )
        val w = ProtoWriter()
            .message(1, video)
            .message(1, input)
            .message(1, sensor)
        if (advertiseAudio) {
            fun sink(ch: Int, type: Int, rate: Int, channels: Int) = ProtoWriter()
                .int32(1, ch)
                .message(
                    3, ProtoWriter()
                        .enum(1, AaProtocol.CODEC_AUDIO_PCM)
                        .enum(2, type)
                        .message(3, ProtoWriter().uint32(1, rate).uint32(2, 16).uint32(3, channels))
                        .bool(5, true)
                )
            w.message(1, sink(AaProtocol.CH_MEDIA_AUDIO, AaProtocol.AUDIO_TYPE_MEDIA, 48000, 2))
            w.message(1, sink(AaProtocol.CH_SPEECH_AUDIO, AaProtocol.AUDIO_TYPE_SPEECH, 16000, 1))
            w.message(1, sink(AaProtocol.CH_SYSTEM_AUDIO, AaProtocol.AUDIO_TYPE_SYSTEM, 16000, 1))
            w.message(
                1, ProtoWriter()
                    .int32(1, AaProtocol.CH_AV_INPUT)
                    .message(
                        5, ProtoWriter()
                            .enum(1, AaProtocol.CODEC_AUDIO_PCM)
                            .message(2, ProtoWriter().uint32(1, 16000).uint32(2, 16).uint32(3, 1))
                            .bool(3, true)
                    )
            )
        }
        w.string(2, name)
            .string(3, "Universal")
            .string(4, "2024")
            .string(5, "0001")
            .varint(6, if (controlsLeft) 0L else 1L)
            .string(7, "FT")
            .string(8, "FT")
            .string(9, "1")
            .string(10, "1.0")
            .bool(11, false)
            .bool(12, false)
            .string(14, name)
            .message(
                17, ProtoWriter()
                    .string(1, name)
                    .string(2, "Universal")
                    .string(3, "2024")
                    .string(4, "0001")
                    .string(5, "FT")
                    .string(6, "FT")
                    .string(7, "1")
                    .string(8, "1.0")
            )
        return w.toByteArray()
    }

    fun channelOpenResponse(): ByteArray = ProtoWriter().enum(1, AaProtocol.STATUS_OK).toByteArray()
    fun avSetupResponse(): ByteArray = ProtoWriter().enum(1, AaProtocol.SETUP_READY).uint32(2, 1).uint32(3, 0).toByteArray()
    fun mediaAck(session: Int): ByteArray = ProtoWriter().int32(1, session).uint32(2, 1).toByteArray()
    fun videoFocusIndication(mode: Int, unrequested: Boolean): ByteArray = ProtoWriter().enum(1, mode).bool(2, unrequested).toByteArray()
    fun bindingResponse(): ByteArray = ProtoWriter().enum(1, AaProtocol.STATUS_OK).toByteArray()
    fun sensorStartResponse(): ByteArray = ProtoWriter().enum(1, AaProtocol.STATUS_OK).toByteArray()
    fun sensorDrivingStatus(status: Int): ByteArray = ProtoWriter().message(13, ProtoWriter().int32(1, status)).toByteArray()
    fun sensorNightMode(night: Boolean): ByteArray = ProtoWriter().message(10, ProtoWriter().bool(1, night)).toByteArray()
    fun pingResponse(timestamp: Long): ByteArray = ProtoWriter().int64(1, timestamp).toByteArray()
    fun audioFocusNotification(state: Int): ByteArray = ProtoWriter().enum(1, state).bool(2, false).toByteArray()
    fun navFocusNotification(type: Int): ByteArray = ProtoWriter().enum(1, type).toByteArray()
    fun byeByeResponse(): ByteArray = ByteArray(0)
    fun avInputOpenResponse(session: Int): ByteArray = ProtoWriter().int32(1, session).int32(2, 0).toByteArray()

    fun inputTouch(timestampNs: Long, x: Int, y: Int, action: Int, pointerId: Int = 0): ByteArray =
        ProtoWriter()
            .uint64(1, timestampNs)
            .message(
                3, ProtoWriter()
                    .message(1, ProtoWriter().uint32(1, x).uint32(2, y).uint32(3, pointerId))
                    .uint32(2, 0)
                    .enum(3, action)
            )
            .toByteArray()

    fun inputKey(timestampNs: Long, keycode: Int, down: Boolean): ByteArray =
        ProtoWriter()
            .uint64(1, timestampNs)
            .message(4, ProtoWriter().message(1, ProtoWriter().uint32(1, keycode).bool(2, down).uint32(3, 0)))
            .toByteArray()

    fun deviceName(b: ByteArray): String = ProtoReader(b).let { it.string(5) ?: it.string(4) ?: Build.MODEL }
    fun channelOpenServiceId(b: ByteArray): Int = ProtoReader(b).int(2, -1)
    fun setupConfigIndex(b: ByteArray): Int = ProtoReader(b).int(1, 0)
    fun startSession(b: ByteArray): Int = ProtoReader(b).int(1, 0)
    fun videoFocusRequestMode(b: ByteArray): Int = ProtoReader(b).int(2, AaProtocol.VIDEO_FOCUS_PROJECTED)
    fun sensorRequestType(b: ByteArray): Int = ProtoReader(b).int(1, 0)
    fun pingTimestamp(b: ByteArray): Long = ProtoReader(b).long(1, 0L)
    fun audioFocusRequestType(b: ByteArray): Int = ProtoReader(b).int(1, 1)
    fun navFocusRequestType(b: ByteArray): Int = ProtoReader(b).int(1, AaProtocol.NAV_FOCUS_PROJECTED)
    fun byeByeReason(b: ByteArray): Int = ProtoReader(b).int(1, 0)
    fun avInputOpenSession(b: ByteArray): Int = ProtoReader(b).int(1, 0)
    fun avInputOpenWanted(b: ByteArray): Boolean = ProtoReader(b).bool(1, false)
}
