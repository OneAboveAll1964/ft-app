package app.ft.carlife

import app.ft.core.Bytes
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarWirelessSetupTest {
    private val sent = ArrayList<ByteArray>()
    private val names = ArrayList<String>()
    private fun setup() = CarWirelessSetup(
        send = { sent.add(it) },
        onCarWifiName = { names.add(it) }
    )

    private fun ids() = sent.map { Bytes.u32(it, 4) }

    private fun frame(serviceId: Int, payload: ByteArray = ByteArray(0)) =
        CarLifeFraming.cmd(serviceId, payload)

    private fun target(name: String) =
        frame(CarWirelessSetup.HU_TARGET_INFO, ProtoWriter().string(1, name).toByteArray())

    @Test fun aRetryWaiterIsInstalledBeforeAnImmediateReply() = runBlocking {
        lateinit var w: CarWirelessSetup
        w = CarWirelessSetup(send = { w.feed(target("CarLife")); true }, onCarWifiName = {})
        assertTrue(w.restartAwaitingName(false).await())
    }

    @Test fun capabilitiesAndBlankNamesCannotCompletePreparation() = runTest {
        val w = setup()
        val reply = w.restartAwaitingName(false)
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 3).toByteArray()))
        w.feed(target("  "))
        assertNull(withTimeoutOrNull(8_000) { reply.await() })
        // A caller's timeout must not cancel the protocol's shared waiter.
        w.feed(target("CarLife"))
        assertTrue(reply.await())
    }

    @Test fun aNewExchangeCannotCompleteThePreviousWaiter() = runBlocking {
        val w = setup()
        val old = w.restartAwaitingName(false)
        val current = w.restartAwaitingName(false)
        w.feed(target("CarLife"))
        assertFalse(old.await())
        assertTrue(current.await())
    }

    @Test fun disconnectEndsTheWaitWithoutDiscardingAnAcceptedGroup() = runBlocking {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        val pending = w.restartAwaitingName(true)
        w.bluetoothDisconnected()
        assertFalse(pending.await())
        w.feed(target("CarLife"))
        w.bluetoothDisconnected()
        assertEquals("CarLife", w.carWifiName)
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(CarWirelessSetup.MD_WIFI_IP, ids().last())
    }

    @Test fun aReentrantResetCannotCompleteTheNextExchangesWaiter() = runBlocking {
        lateinit var w: CarWirelessSetup
        w = CarWirelessSetup(send = { true }, onCarWifiName = { w.reset() })
        val old = w.restartAwaitingName(false)
        w.feed(target("CarLife"))
        assertFalse(old.await())
    }

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
    fun aWirelessCapabilityReplyAfterTheNameDoesNotRepeatPreparation() {
        val w = setup()
        w.feed(target("Android_f4ec"))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 3).int32(2, 1).toByteArray()))
        w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 1).toByteArray()))
        assertEquals(listOf("Android_f4ec"), names)
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

    @Test fun aFreshIpRequestCanRecoverTheCarsTcpClientButPollingCannotRestartIt() {
        var clock = 0L
        val w = CarWirelessSetup(send = { sent.add(it) }, onCarWifiName = {}, now = { clock })
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        clock = 1_999
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(1, sent.size)
        clock = 2_000
        w.wifiDirectReady("192.168.49.23")
        assertEquals("polling must not restart the car's TCP client", 1, sent.size)
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP, CarWirelessSetup.MD_WIFI_IP), ids())
    }

    @Test fun anEstablishedCommandChannelSuppressesIpRepliesEvenAfterBluetoothRestarts() {
        var connected = false
        val w = CarWirelessSetup(send = { sent.add(it) }, onCarWifiName = {},
            commandConnected = { connected }, now = { 10_000L })
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        connected = true
        w.restart()
        sent.clear()
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady("192.168.49.24")
        assertTrue("a live CMD must survive delayed Bluetooth requests", sent.isEmpty())
        connected = false
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.24", ProtoReader(sent.single().copyOfRange(8, sent.single().size)).string(1))
    }

    @Test fun aFailedIpWriteDoesNotConsumeTheReplyOrThrottleItsRetry() {
        var writable = false
        val w = CarWirelessSetup(send = { sent.add(it); writable }, onCarWifiName = {}, now = { 0L })
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        writable = true
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP, CarWirelessSetup.MD_WIFI_IP), ids())
    }

    @Test
    fun aRestoredGroupAnnouncesTheSameAddressAgainWithoutANewBluetoothCall() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady(null)
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals("the lost group's address must not be sent", 1, sent.size)

        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady("192.168.49.23")
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP, CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.23", ProtoReader(sent[1].copyOfRange(8, sent[1].size)).string(1))
    }

    @Test
    fun aChangedAddressIsAnnouncedWithoutWaitingForAnotherIpRequest() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady("192.168.49.24")
        assertEquals(listOf(CarWirelessSetup.MD_WIFI_IP, CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.24", ProtoReader(sent[1].copyOfRange(8, sent[1].size)).string(1))
    }

    @Test
    fun losingTheGroupDoesNotTurnCapabilitiesIntoAnotherNameAnnouncement() {
        val w = setup()
        val readyFrame = frame(CarWirelessSetup.HU_WIRELESS_INFO, ProtoWriter().int32(1, 3).toByteArray())
        w.feed(target("DIRECT-ab-COROLLA"))
        w.feed(readyFrame)
        w.wifiDirectReady("192.168.49.23")
        w.wifiDirectReady(null)
        w.feed(readyFrame)
        w.feed(readyFrame)
        assertEquals(listOf("DIRECT-ab-COROLLA"), names)
        assertEquals("DIRECT-ab-COROLLA", w.carWifiName)
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
    fun restartingWithALiveGroupKeepsItsAddressUntilTheCarRequestsIt() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        sent.clear()
        w.restart()
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), ids())
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST, CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.23", ProtoReader(sent.last().copyOfRange(8, sent.last().size)).string(1))
    }

    @Test
    fun restartingAfterCleanupWaitsForTheNewGroupsAddress() {
        val w = setup()
        w.wifiDirectReady("192.168.49.23")
        w.restart(keepIp = false)
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), ids())
        w.wifiDirectReady("192.168.49.24")
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST, CarWirelessSetup.MD_WIFI_IP), ids())
        assertEquals("192.168.49.24", ProtoReader(sent.last().copyOfRange(8, sent.last().size)).string(1))
    }

    @Test
    fun aCallbackRestartDiscardsTheRestOfTheOldRead() {
        lateinit var w: CarWirelessSetup
        w = CarWirelessSetup(
            send = { sent.add(it) },
            onCarWifiName = { w.restart(keepIp = false) }
        )
        w.feed(target("CarLife") + frame(CarWirelessSetup.HU_IP_REQUEST))
        w.wifiDirectReady("192.168.49.24")
        assertEquals("an IP request from the old exchange must not leak into the new one",
            listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), ids())
        w.feed(frame(CarWirelessSetup.HU_IP_REQUEST))
        assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST, CarWirelessSetup.MD_WIFI_IP), ids())
    }

    @Test
    fun aConcurrentResetCannotSplitTheProcessingOfOneRead() {
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetting = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val w = CarWirelessSetup(
            send = {
                sending.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            },
            onCarWifiName = {}
        )
        val reader = Thread {
            try { w.feed(frame(CarWirelessSetup.HU_WIRELESS_INFO) + target("old exchange")) }
            catch (t: Throwable) { failure.set(t) }
        }
        val reset = Thread {
            resetting.countDown()
            w.reset()
        }
        try {
            reader.start()
            assertTrue(sending.await(5, TimeUnit.SECONDS))
            reset.start()
            assertTrue(resetting.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reset.state != Thread.State.BLOCKED && reset.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals("reset must wait for the old read to finish", Thread.State.BLOCKED, reset.state)
        } finally {
            release.countDown()
            reader.join(5_000)
            reset.join(5_000)
        }
        assertTrue(!reader.isAlive && !reset.isAlive)
        assertNull(failure.get())
        assertNull("the old read must not restore a name after reset", w.carWifiName)
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
