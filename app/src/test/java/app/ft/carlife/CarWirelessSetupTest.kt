package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWirelessSetupTest {
    private val sent = ArrayList<ByteArray>()
    private val names = ArrayList<String>()

    private fun setup(ip: String? = "192.168.43.1") = CarWirelessSetup(
        send = { sent.add(it) },
        localIp = { ip },
        onCarWifiName = { names.add(it) }
    )

    private fun ids() = sent.map { Bytes.u32(it, 4) }

    private fun frame(serviceId: Int, payload: ByteArray = ByteArray(0)) =
        CarLifeFraming.cmd(serviceId, payload)

    @Test
    fun aReadyHeadUnitIsAskedWhatWirelessItOffers() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_READY))
        assertEquals(listOf(CarWirelessSetup.MD_READY, CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), ids())
    }

    @Test
    fun theWirelessInfoReplyMakesFtAskForWifiDirect() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_DIRECT_NAME_REQUEST), ids())
    }

    @Test
    fun theCarsWifiDirectNameIsRead() {
        val w = setup()
        val body = ProtoWriter().string(1, "DIRECT-zO-Android_f4ec").toByteArray()
        w.feed(frame(CarWirelessSetup.HU_WIFI_DIRECT_NAME, body))
        assertEquals(listOf("DIRECT-zO-Android_f4ec"), names)
    }

    @Test
    fun theCarIsToldThisPhonesAddressWhenItAsks() {
        val w = setup("10.55.95.206")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP), ids())
        val payload = sent[0].copyOfRange(8, sent[0].size)
        assertEquals("10.55.95.206", ProtoReader(payload).string(1))
    }

    @Test
    fun nothingIsSentWhenThePhoneHasNoAddressYet() {
        val w = setup(null)
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertTrue("replied with no address to give", sent.isEmpty())
    }

    @Test
    fun aFrameSplitAcrossReadsIsStillUnderstood() {
        val w = setup()
        val f = frame(CarWirelessSetup.HU_WIFI_DIRECT_NAME, ProtoWriter().string(1, "DIRECT-ab-COROLLA").toByteArray())
        w.feed(f.copyOfRange(0, 3))
        assertTrue("acted on half a frame", names.isEmpty())
        w.feed(f.copyOfRange(3, f.size))
        assertEquals(listOf("DIRECT-ab-COROLLA"), names)
    }

    @Test
    fun twoFramesInOneReadAreBothHandled() {
        val w = setup()
        val both = frame(CarWirelessSetup.HU_READY) + frame(CarWirelessSetup.HU_WIRELESS_INFO)
        w.feed(both)
        assertEquals(
            listOf(
                CarWirelessSetup.MD_READY,
                CarWirelessSetup.MD_WIRELESS_INFO_REQUEST,
                CarWirelessSetup.MD_WIFI_DIRECT_NAME_REQUEST
            ),
            ids()
        )
    }

    @Test
    fun rubbishOnTheLineDoesNotStopLaterFrames() {
        val w = setup()
        w.feed(byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10))
        w.feed(frame(CarWirelessSetup.HU_WIFI_DIRECT_NAME, ProtoWriter().string(1, "DIRECT-zz-CAR").toByteArray()))
        assertEquals(listOf("DIRECT-zz-CAR"), names)
    }

    @Test
    fun theNameIsOnlyAskedForOnce() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_DIRECT_NAME_REQUEST), ids())
    }
}
