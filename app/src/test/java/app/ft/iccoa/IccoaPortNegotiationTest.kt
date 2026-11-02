package app.ft.iccoa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class IccoaPortNegotiationTest {
    @Test fun knownSensorRequestReturnsBoundPort() {
        val request = ProtoWriter().enum(1, 1).toByteArray()
        val response = IccoaPortNegotiation.responseFor(request)!!
        val parsed = ProtoReader(response)
        assertEquals(1, parsed.int(1))
        assertEquals(57239, parsed.int(2))
    }
    @Test fun incompleteOrAmbiguousRequestsNeverReceivePorts() {
        val good = ProtoWriter().enum(1, 1).toByteArray()
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x10)))
        assertNull(IccoaPortNegotiation.responseFor(good + good))
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x0A, 0x00)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08, 0x81.toByte())))
    }
    @Test fun unknownTypesAreNotAssignedUnverifiedPorts() {
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 2).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 3).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(ByteArray(0)))
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 1).bool(2, true).toByteArray()))
    }
}
