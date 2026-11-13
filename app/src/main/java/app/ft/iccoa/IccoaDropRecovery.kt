package app.ft.iccoa

/**
 * A bounded episode begins when FT auto-selects a previously remembered
 * car, or after an already established ICCOA link unexpectedly drops.
 *
 * The initial auto-connect or first reconnect is attempt 1/3. A subsequent
 * failure may consume attempt 2 or 3 only for a confirmed transport failure.
 * PIN rejection, authentication errors, a new PIN prompt, and user disconnect
 * stop the episode. Never retry a secret or change the ICCOA handshake.
 */
internal class IccoaDropRecovery {
    companion object { const val MAX_ATTEMPTS = 3 }

    private var recovering = false
    private var attempts = 0

    val active: Boolean get() = recovering

    /**
     * An already remembered car selected by normal discovery is a bounded
     * initial automatic attempt, even if no live projection existed yet.
     * This does not grant permission to retry a PIN, missing credential,
     * denied Wi-Fi consent or cryptographic failure.
     */
    fun onInitialRememberedCarAutoSelect(): Int {
        if (!recovering) {
            recovering = true
            attempts = 1
        }
        return attempts
    }

    /** Returns the numbered attempt to start, or null when automation must stop. */
    fun onFailure(
        wasConnected: Boolean,
        autoConnectEnabled: Boolean,
        retryableTransportFailure: Boolean
    ): Int? {
        if (!autoConnectEnabled || !retryableTransportFailure) {
            clear()
            return null
        }
        if (wasConnected) {
            // A completed link ends the previous recovery episode. Its unexpected
            // loss authorizes a NEW bounded set of three reconnect attempts.
            recovering = true
            attempts = 0
        } else if (!recovering) {
            // Failed initial pairing/auth must not start automatic retries.
            return null
        }
        if (attempts >= MAX_ATTEMPTS) {
            clear()
            return null
        }
        attempts++
        return attempts
    }

    /** Completed projection, a PIN prompt, or manual action ends a retry episode. */
    fun onConnected() = clear()
    fun onPinRequired() = clear()
    fun onExplicitOwnerRequest() = clear()

    private fun clear() {
        recovering = false
        attempts = 0
    }
}
