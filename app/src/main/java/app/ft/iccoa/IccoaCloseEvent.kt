package app.ft.iccoa

/**
 * Explicit ICCOA termination action. Never overload a Boolean shared by
 * Quick Auth fallback and automatic transport recovery.
 *
 * STOP: no automatic retry (invalid/unknown auth, PIN prompt or consent refusal).
 * RETRY_TRANSPORT: unexpected BLE/Wi-Fi/CONTROL/remote disconnect; the service
 * still applies its own bounded-recovery and owner-disconnect checks.
 * REQUIRE_NORMAL_PIN: the authenticated OEM Quick reply explicitly requires
 * a new Normal Auth PIN. This is never a transport-recovery request.
 */
internal enum class IccoaCloseAction {
    STOP,
    RETRY_TRANSPORT,
    REQUIRE_NORMAL_PIN
}

/** One typed event; message is diagnostic text, never a machine state code. */
internal data class IccoaCloseEvent(
    val message: String,
    val action: IccoaCloseAction = IccoaCloseAction.STOP
)

/** ICCOA ControlIndex disconnect notification is handled inside IccoaSession. */
internal object IccoaCloseClassifier {
    fun remoteControlAction(category: Int, method: Int): IccoaCloseAction? =
        if (category == 1 && method == 23) IccoaCloseAction.RETRY_TRANSPORT else null
}
