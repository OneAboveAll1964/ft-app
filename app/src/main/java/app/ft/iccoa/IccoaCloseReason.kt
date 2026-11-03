package app.ft.iccoa

/** The owner decides whether to rediscover, request a fresh PIN, or stop. */
enum class IccoaCloseReason { FAILED, TRANSPORT_FAILURE, NORMAL_REQUIRED }

internal object IccoaFailurePolicy {
    fun channel(error: Throwable): IccoaCloseReason =
        if (error is java.io.IOException) IccoaCloseReason.TRANSPORT_FAILURE else IccoaCloseReason.FAILED

    fun authentication(stage: String, quick: Boolean, error: Throwable): IccoaCloseReason {
        if (quick && error is IccoaHandshakeException &&
            error.reason == IccoaHandshakeException.Reason.RESPONSE_RESULT &&
            error.result in listOf(1L, 2L)) return IccoaCloseReason.NORMAL_REQUIRED
        // Once a request might have reached the car, require owner action instead of replaying auth.
        return if (stage == "tcp-bind" || stage == "tcp-connect") channel(error)
        else IccoaCloseReason.FAILED
    }
}
