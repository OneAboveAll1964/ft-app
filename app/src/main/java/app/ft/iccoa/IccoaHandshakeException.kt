package app.ft.iccoa

/** Only fixed reason codes and the receiver's numeric result may enter diagnostics. */
internal class IccoaHandshakeException(val reason: Reason, val result: Long? = null) :
    IllegalArgumentException(reason.name) {
    enum class Reason {
        FRAME_LENGTH, HEADER_CRC, RESPONSE_LANE, RESPONSE_RESULT,
        IDENTITY_LENGTH, AGREEMENT_LENGTH, NONCE_LENGTH, PIN_MAC, AGREEMENT_SIGNATURE
    }

    val diagnostic: String get() = reason.name + (result?.let { " result=$it" } ?: "")
}
