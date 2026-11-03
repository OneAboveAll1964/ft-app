package app.ft.iccoa

/**
 * Persist the OEM car public key only when both cryptographic authentication
 * confirmation has been sent AND an encrypted frame from the physical CONTROL
 * lane has passed session-key authentication. A transmitted AuthConfirm alone is not evidence of car acceptance.
 */
internal class IccoaTrustedCommitGate {
    private var confirmSent = false
    private var controlReady = false
    private var committed = false

    @Synchronized fun onConfirmSent(): Boolean {
        confirmSent = true
        return takeIfReady()
    }

    @Synchronized fun onAuthenticatedControl(): Boolean {
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
