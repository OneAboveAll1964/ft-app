package app.ft.aa

internal class AaAudioFocus {
    private var granted = AaProtocol.AUDIO_FOCUS_LOSS
    private var inCall = false
    private var restoreMedia = false

    fun request(type: Int): Int {
        val state = when (type) {
            AaProtocol.AUDIO_REQUEST_GAIN -> AaProtocol.AUDIO_FOCUS_GAIN
            AaProtocol.AUDIO_REQUEST_GAIN_TRANSIENT,
            AaProtocol.AUDIO_REQUEST_GAIN_TRANSIENT_MAY_DUCK -> AaProtocol.AUDIO_FOCUS_GAIN_TRANSIENT
            else -> AaProtocol.AUDIO_FOCUS_LOSS
        }
        if (inCall && state != AaProtocol.AUDIO_FOCUS_LOSS) {
            if (state == AaProtocol.AUDIO_FOCUS_GAIN) restoreMedia = true
            return AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT
        }
        restoreMedia = false
        if (granted != AaProtocol.AUDIO_FOCUS_GAIN || state != AaProtocol.AUDIO_FOCUS_GAIN_TRANSIENT) {
            granted = state
        }
        return state
    }

    fun updateCall(active: Boolean): Int? {
        if (inCall == active) return null
        inCall = active
        if (active) {
            if (granted == AaProtocol.AUDIO_FOCUS_LOSS) return null
            restoreMedia = granted == AaProtocol.AUDIO_FOCUS_GAIN
            granted = AaProtocol.AUDIO_FOCUS_LOSS
            return AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT
        }
        if (!restoreMedia) return null
        restoreMedia = false
        granted = AaProtocol.AUDIO_FOCUS_GAIN
        return granted
    }

    fun revokeMedia(): Int? {
        if (granted != AaProtocol.AUDIO_FOCUS_GAIN && !restoreMedia) return null
        return request(AaProtocol.AUDIO_REQUEST_RELEASE)
    }
}
