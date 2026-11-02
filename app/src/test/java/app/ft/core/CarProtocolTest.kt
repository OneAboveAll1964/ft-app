package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CarProtocolTest {
    @Test fun oldBackupsAndUnknownValuesKeepCarLife() {
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId(null))
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId(""))
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId("future-protocol"))
        assertEquals(CarProtocol.ICCOA, CarProtocol.fromId("iccoa"))
    }
}
