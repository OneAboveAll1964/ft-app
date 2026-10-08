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
    private var ready = 0

    private fun setup() = CarWirelessSetup(
        send = { sent.add(it) },
        onCarWifiName = { names.add(it) },
        onCarReady = { ready++ }
    )

    private fun ids() = sent.map { Bytes.u32(it, 4) }

    private fun frame(serviceId: Int, payload: ByteArray = ByteArray(0)) =
        CarLifeFraming.cmd(serviceId, payload)

    private fun target(name: String) =
        frame(CarWirelessSetup.HU_TARGET_INFO, ProtoWriter().string(1, name).toByteArray())

    @Test
    fun theFirstThingTheCarHearsIsTheWirelessInfoRequest() {
        val w = setup()
        w.begin()
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), ids())
        assertEquals(8, sent[0].size)
    }

    @Test
    fun theWirelessInfoReplyMakesFtAskForTheTarget() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 2).int32(2, 5745).toByteArray()))
        assertEquals(listOf(CarWirelessSetup.MD_TARGET_INFO_REQUEST), ids())
    }

    @Test
    fun aWirelessInfoAfterTheNameTellsFtTheCarIsReady() {
        val w = setup()
        w.feed(target("Android_f4ec"))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 3).int32(2, 1).toByteArray()))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 1).toByteArray()))
        assertEquals(1, ready)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theCarsWifiDirectNameIsRead() {
        val w = setup()
        w.feed(target("DIRECT-zO-Android_f4ec"))
        assertEquals(listOf("DIRECT-zO-Android_f4ec"), names)
        assertEquals("DIRECT-zO-Android_f4ec", w.carWifiName)
    }

    @Test
    fun aRepeatedTargetIsIgnored() {
        val w = setup()
        w.feed(target("DIRECT-ab-COROLLA"))
        w.feed(target("DIRECT-ab-COROLLA"))
        assertEquals(listOf("DIRECT-ab-COROLLA"), names)
    }

    @Test
    fun noMoreTargetRequestsOnceTheNameIsKnown() {
        val w = setup()
        w.feed(target("DIRECT-ab-COROLLA"))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO))
        assertTrue("asked again after the name was known", sent.isEmpty())
    }

    @Test
    fun askingAgainTakesTheCarsNextAnswerEvenWhenTheNameIsTheSame() {
        val w = setup()
        w.feed(target("_ClfWfd_vehicle-r9ti4"))
        w.askName()
        assertEquals(listOf(CarWirelessSetup.MD_TARGET_INFO_REQUEST), ids())
        w.feed(target("_ClfWfd_vehicle-r9ti4"))
        assertEquals(listOf("_ClfWfd_vehicle-r9ti4", "_ClfWfd_vehicle-r9ti4"), names)
    }

    @Test
    fun theAddressWaitsForWifiDirect() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertTrue("sent an address before WiFi Direct was up", sent.isEmpty())
        w.wifiDirectReady("192.168.49.23")
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.23", ProtoReader(sent[0].copyOfRange(8, sent[0].size)).string(1))
    }

    @Test
    fun theAddressGoesStraightAwayWhenWifiDirectIsAlreadyUp() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        assertTrue("sent an address nobody asked for", sent.isEmpty())
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP), ids())
    }

    @Test
    fun theAddressIsOnlySentOnce() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady("192.168.49.23")
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP), ids())
    }

    @Test
    fun aNewCallStartsOver() {
        val w = setup()
        w.feed(target("DIRECT-ab-COROLLA"))
        w.reset()
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO))
        assertEquals(listOf(CarWirelessSetup.MD_TARGET_INFO_REQUEST), ids())
    }

    @Test
    fun aFrameSplitAcrossReadsIsStillUnderstood() {
        val w = setup()
        val f = target("DIRECT-ab-COROLLA")
        w.feed(f.copyOfRange(0, 3))
        assertTrue("acted on half a frame", names.isEmpty())
        w.feed(f.copyOfRange(3, f.size))
        assertEquals(listOf("DIRECT-ab-COROLLA"), names)
    }

    @Test
    fun twoFramesInOneReadAreBothHandled() {
        val w = setup()
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO) + target("DIRECT-ab-COROLLA"))
        assertEquals(listOf(CarWirelessSetup.MD_TARGET_INFO_REQUEST), ids())
        assertEquals(listOf("DIRECT-ab-COROLLA"), names)
    }

    @Test
    fun rubbishOnTheLineDoesNotStopLaterFrames() {
        val w = setup()
        w.feed(byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10))
        w.feed(target("DIRECT-zz-CAR"))
        assertEquals(listOf("DIRECT-zz-CAR"), names)
    }
}
