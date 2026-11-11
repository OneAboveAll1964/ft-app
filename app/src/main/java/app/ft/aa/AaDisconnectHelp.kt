package app.ft.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class AaDisconnectHelp {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending
    private var active = false
    private var offered = false

    @Synchronized fun start() {
        active = true
        offered = false
        _pending.value = false
    }

    @Synchronized fun stop() {
        active = false
        _pending.value = false
    }

    @Synchronized fun closed(hasStreamed: Boolean, reason: String, enabled: Boolean, remoteBye: Boolean = false) {
        if (!active || offered || !enabled || !hasStreamed || remoteBye) return
        if (!reason.startsWith("read ") && !reason.startsWith("write ")) return
        offered = true
        _pending.value = true
    }

    @Synchronized fun dismiss() {
        _pending.value = false
    }
}
