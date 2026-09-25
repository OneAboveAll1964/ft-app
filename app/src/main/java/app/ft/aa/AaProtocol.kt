package app.ft.aa

object AaProtocol {
    const val CH_CONTROL = 0
    const val CH_INPUT = 1
    const val CH_SENSOR = 2
    const val CH_VIDEO = 3
    const val CH_MEDIA_AUDIO = 4
    const val CH_SPEECH_AUDIO = 5
    const val CH_SYSTEM_AUDIO = 6
    const val CH_AV_INPUT = 7
    const val CH_BLUETOOTH = 8
    const val CH_MEDIA_STATUS = 9
    const val CH_NAVIGATION = 10

    const val FRAME_MIDDLE = 0
    const val FRAME_FIRST = 1
    const val FRAME_LAST = 2
    const val FRAME_BULK = 3
    const val FLAG_CONTROL = 4
    const val FLAG_ENCRYPTED = 8
    const val MAX_FRAME_PAYLOAD = 0x4000

    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 1

    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val SSL_HANDSHAKE = 3
    const val AUTH_COMPLETE = 4
    const val SERVICE_DISCOVERY_REQUEST = 5
    const val SERVICE_DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val CHANNEL_CLOSE_NOTIFICATION = 9
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYEBYE_REQUEST = 15
    const val BYEBYE_RESPONSE = 16
    const val VOICE_SESSION_NOTIFICATION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19

    const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    const val AV_MEDIA_INDICATION = 0x0001
    const val AV_SETUP_REQUEST = 0x8000
    const val AV_START_INDICATION = 0x8001
    const val AV_STOP_INDICATION = 0x8002
    const val AV_SETUP_RESPONSE = 0x8003
    const val AV_MEDIA_ACK = 0x8004
    const val AV_INPUT_OPEN_REQUEST = 0x8005
    const val AV_INPUT_OPEN_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008

    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT_INDICATION = 0x8003

    const val INPUT_EVENT_INDICATION = 0x8001
    const val BINDING_REQUEST = 0x8002
    const val BINDING_RESPONSE = 0x8003

    const val STATUS_OK = 0
    const val CODEC_H264 = 3
    const val CODEC_AUDIO_PCM = 1
    const val RES_800x480 = 1
    const val RES_1280x720 = 2
    const val RES_1920x1080 = 3
    const val FPS_60 = 1
    const val FPS_30 = 2
    const val SETUP_READY = 2
    const val VIDEO_FOCUS_PROJECTED = 1
    const val VIDEO_FOCUS_NATIVE = 2
    const val AUDIO_FOCUS_GAIN = 1
    const val AUDIO_FOCUS_LOSS = 3
    const val AUDIO_REQUEST_RELEASE = 4
    const val NAV_FOCUS_PROJECTED = 2
    const val SENSOR_LOCATION = 1
    const val SENSOR_NIGHT_MODE = 10
    const val SENSOR_DRIVING_STATUS = 13
    const val AUDIO_TYPE_SPEECH = 1
    const val AUDIO_TYPE_SYSTEM = 2
    const val AUDIO_TYPE_MEDIA = 3

    const val POINTER_DOWN = 0
    const val POINTER_UP = 1
    const val POINTER_MOVED = 2

    const val WIRELESS_PORT = 5277
    const val BT_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
    const val BT_WIFI_START_REQUEST = 1
    const val BT_WIFI_INFO_REQUEST = 2
    const val BT_WIFI_INFO_RESPONSE = 3
    const val BT_WIFI_VERSION_REQUEST = 4
    const val BT_WIFI_VERSION_RESPONSE = 5
    const val BT_WIFI_CONNECT_STATUS = 7
    const val WIFI_SECURITY_WPA2_PERSONAL = 8
    const val WIFI_AP_DYNAMIC = 1

    fun channelName(ch: Int) = when (ch) {
        CH_CONTROL -> "CONTROL"; CH_INPUT -> "INPUT"; CH_SENSOR -> "SENSOR"; CH_VIDEO -> "VIDEO"
        CH_MEDIA_AUDIO -> "MEDIA_AUDIO"; CH_SPEECH_AUDIO -> "SPEECH_AUDIO"; CH_SYSTEM_AUDIO -> "SYSTEM_AUDIO"
        CH_AV_INPUT -> "AV_INPUT"; CH_BLUETOOTH -> "BLUETOOTH"; else -> "CH$ch"
    }

    fun controlName(id: Int) = when (id) {
        VERSION_REQUEST -> "VERSION_REQUEST"; VERSION_RESPONSE -> "VERSION_RESPONSE"; SSL_HANDSHAKE -> "SSL_HANDSHAKE"
        AUTH_COMPLETE -> "AUTH_COMPLETE"; SERVICE_DISCOVERY_REQUEST -> "SERVICE_DISCOVERY_REQUEST"
        SERVICE_DISCOVERY_RESPONSE -> "SERVICE_DISCOVERY_RESPONSE"; CHANNEL_OPEN_REQUEST -> "CHANNEL_OPEN_REQUEST"
        CHANNEL_OPEN_RESPONSE -> "CHANNEL_OPEN_RESPONSE"; CHANNEL_CLOSE_NOTIFICATION -> "CHANNEL_CLOSE"
        PING_REQUEST -> "PING_REQUEST"; PING_RESPONSE -> "PING_RESPONSE"; NAV_FOCUS_REQUEST -> "NAV_FOCUS_REQUEST"
        NAV_FOCUS_NOTIFICATION -> "NAV_FOCUS_NOTIFICATION"; BYEBYE_REQUEST -> "BYEBYE_REQUEST"; BYEBYE_RESPONSE -> "BYEBYE_RESPONSE"
        VOICE_SESSION_NOTIFICATION -> "VOICE_SESSION"; AUDIO_FOCUS_REQUEST -> "AUDIO_FOCUS_REQUEST"
        AUDIO_FOCUS_NOTIFICATION -> "AUDIO_FOCUS_NOTIFICATION"; else -> String.format("0x%04X", id)
    }

    fun avName(id: Int) = when (id) {
        AV_MEDIA_WITH_TIMESTAMP -> "MEDIA_TS"; AV_MEDIA_INDICATION -> "MEDIA_CONFIG"; AV_SETUP_REQUEST -> "SETUP_REQUEST"
        AV_START_INDICATION -> "START"; AV_STOP_INDICATION -> "STOP"; AV_SETUP_RESPONSE -> "SETUP_RESPONSE"
        AV_MEDIA_ACK -> "MEDIA_ACK"; VIDEO_FOCUS_REQUEST -> "VIDEO_FOCUS_REQUEST"; VIDEO_FOCUS_INDICATION -> "VIDEO_FOCUS_INDICATION"
        else -> String.format("0x%04X", id)
    }
}
