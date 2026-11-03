package app.ft.iccoa

/** Parsing a frame on an auxiliary socket cannot confirm ownership of CONTROL. */
internal data class IccoaInboundFrame(val body: ByteArray, val authenticatedControl: Boolean) {
    companion object {
        fun decode(port: Int, frame: IccoaControl.Frame, cipher: IccoaControl.Cipher): IccoaInboundFrame {
            val encrypted = IccoaControl.isEncrypted(frame.body)
            val body = if (encrypted) cipher.decrypt(frame.body) else frame.body
            return IccoaInboundFrame(body, encrypted && port == IccoaLaneServer.CONTROL_PORT &&
                frame.category == 1 && frame.method != 23)
        }
    }
}
