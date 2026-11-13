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

    private fun advertisedDpi(width: Int, height: Int, requestedDpi: Int): Int {
        val response = AaMessages.serviceDiscoveryResponse(
            width, height, 30, requestedDpi, false, "XA60")
        val video = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_VIDEO }
        return video.message(3)!!.message(4)!!.int(5)
    }

    @Test fun largerIconsAreNegotiatedThroughVideoDpiNotCropping() {
        assertEquals(240, advertisedDpi(1920, 1008, 240))
        assertEquals(240, advertisedDpi(1280, 720, 240))
        assertEquals(170, advertisedDpi(800, 480, 240))
    }

    @Test fun noCarLocationIsAdvertisedInOtherProtocols() {
        assertEquals(listOf(AaProtocol.SENSOR_DRIVING_STATUS,
            AaProtocol.SENSOR_NIGHT_MODE), sensors(false))
    }
}
