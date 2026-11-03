package app.ft.iccoa

/**
 * Persist a remembered PIN only when both cryptographic authentication
 * confirmation has been sent AND the car has opened its authenticated CONTROL
 * lane. A transmitted AuthConfirm alone is not evidence of car acceptance.
 */
internal class IccoaTrustedCommitGate {
    private var confirmSent = false
    private var controlReady = false
    private var committed = false

    @Synchronized fun onConfirmSent(): Boolean {
        confirmSent = true
        return takeIfReady()
    }

    @Synchronized fun onControlReady(): Boolean {
        controlReady = true
        return takeIfReady()
    }

    @Synchronized fun cancel() {
        committed = true
    }

    private fun takeIfReady(): Boolean {
        if (committed || !confirmSent || !controlReady) return false
        committed = true
        return true
    }
}
