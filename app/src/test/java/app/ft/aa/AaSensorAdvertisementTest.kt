package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class AaSensorAdvertisementTest {
    private fun sensors(advertise: Boolean): List<Int> {
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 160, false, "XA60",
            advertiseCarLocation = advertise)
        val sensorChannel = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }
        return sensorChannel.message(2)!!.messages(1).map { it.int(1) }
    }

    @Test fun carGpsIsAdvertisedFirstWithoutDroppingOtherSensors() {
        assertEquals(listOf(AaProtocol.SENSOR_LOCATION, AaProtocol.SENSOR_DRIVING_STATUS,
            AaProtocol.SENSOR_NIGHT_MODE), sensors(true))
    }

    @Test fun noCarLocationIsAdvertisedInOtherProtocols() {
        assertEquals(listOf(AaProtocol.SENSOR_DRIVING_STATUS,
            AaProtocol.SENSOR_NIGHT_MODE), sensors(false))
    }
}
