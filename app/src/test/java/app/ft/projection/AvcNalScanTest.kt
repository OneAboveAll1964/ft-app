package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcNalScanTest {
    @Test fun annexBConfigAndIdr() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00,
            0, 0, 1, 0x68, 0x11,
            0, 0, 0, 1, 0x65, 0x55
        )
        assertEquals(setOf(7, 8, 5), AvcNalScan.types(bytes))
    }

    @Test fun lengthPrefixedUnits() {
        val bytes = byteArrayOf(0, 0, 0, 2, 0x67, 0x42, 0, 0, 0, 2, 0x68, 0x22)
        assertEquals(setOf(7, 8), AvcNalScan.types(bytes))
    }

    @Test fun standaloneAvcConfigurationNal() {
        assertEquals(setOf(7), AvcNalScan.types(byteArrayOf(0x67, 0x42)))
        assertEquals(setOf(5), AvcNalScan.types(byteArrayOf(0, 0, 1, 0x65, 0x33)))
    }

    @Test fun opaqueOrEmptyBytesDoNotClaimSpsPpsIdr() {
        assertTrue(AvcNalScan.types(byteArrayOf()).isEmpty())
        assertTrue(AvcNalScan.types(byteArrayOf(0, 0, 0, 0)).isEmpty())
        assertEquals(setOf(1), AvcNalScan.types(byteArrayOf(1, 2, 3)))
    }
}
