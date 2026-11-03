package app.ft.iccoa

/**
 * A transport failure, lost Wi-Fi, local GATT write failure or timeout cannot
 * prove that a previously accepted PIN became invalid. Deleting the encrypted
 * secret on such errors breaks bounded transport recovery.
 *
 * Only explicit protocol evidence that the credential is not accepted may
 * invalidate the saved PIN. Never retry a rejected credential automatically.
 */
internal object IccoaPinCachePolicy {
    fun shouldClearAfterAuthFailure(error: Exception): Boolean {
        val failure = error as? IccoaHandshakeException ?: return false
        return failure.reason == IccoaHandshakeException.Reason.PIN_MAC ||
            (failure.reason == IccoaHandshakeException.Reason.RESPONSE_RESULT &&
                failure.result == 1L) // OEM explicitly requests a new code
    }
}
