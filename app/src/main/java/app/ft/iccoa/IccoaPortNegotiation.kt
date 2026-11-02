package app.ft.iccoa

import app.ft.core.ProtoWriter

/**
 * RAV4 OEM Carlink.apk ucar.proto ControlIndex methods 2/3.
 * Requests carry ServerType enum: SENSOR=1, AUDIO=2, UIBC=3.
 * Only the SENSOR port is mapped: FT binds this authenticated lane explicitly.
 */
internal object IccoaPortNegotiation {
    const val REQUEST = 2
    const val RESPONSE = 3
    const val SENSOR = 1
    const val SENSOR_PORT = 57239

    fun responseFor(request: ByteArray): ByteArray? {
        // OEM ucar.proto: GetPortRequest has exactly one enum field (type=1).
        // Protobuf's canonical encoding for SENSOR=1 is 08 01.
        // ProtoReader is intentionally permissive on truncated trailing fields:
        // do not acknowledge an incomplete or duplicate port request.
        if (!request.contentEquals(byteArrayOf(0x08, 0x01))) return null
        return ProtoWriter().enum(1, 1).int32(2, SENSOR_PORT).toByteArray()
    }
}
