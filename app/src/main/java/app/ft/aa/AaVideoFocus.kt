package app.ft.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AaVideoFocus(val mode: Int) {
    PROJECTED(AaProtocol.VIDEO_FOCUS_PROJECTED),
    NATIVE(AaProtocol.VIDEO_FOCUS_NATIVE);

    companion object {
        fun fromMode(mode: Int): AaVideoFocus? = entries.firstOrNull { it.mode == mode }
    }
}

/** User intent outlives a transport session; StateFlow needs no owner/session lock ordering. */
class AaVideoFocusOwner {
    private val mutable = MutableStateFlow(AaVideoFocus.PROJECTED)
    val state: StateFlow<AaVideoFocus> = mutable

    fun select(focus: AaVideoFocus) { mutable.value = focus }
}

/** All calls run inside AaSessionLifecycle.dispatch, including local UI requests. */
internal class AaVideoFocusControl(
    private val available: () -> Boolean,
    private val indicate: (AaVideoFocus, Boolean) -> Boolean,
    private val owner: AaVideoFocusOwner = AaVideoFocusOwner()
) {
    val state: StateFlow<AaVideoFocus> = owner.state

    fun request(focus: AaVideoFocus, unrequested: Boolean): Boolean {
        if (!available() || !indicate(focus, unrequested)) return false
        owner.select(focus)
        return true
    }

    fun phoneRequest(mode: Int): Boolean {
        val focus = AaVideoFocus.fromMode(mode) ?: return false
        return request(focus, false)
    }

    // A late AV_START or codec recovery must not steal focus back from FT.
    fun acknowledge(): Boolean = request(state.value, false)

    // This is a redraw request, not a command to leave FT and reopen Android Auto.
    fun freshPicture(): Boolean = state.value == AaVideoFocus.PROJECTED &&
        request(AaVideoFocus.PROJECTED, true)
}
