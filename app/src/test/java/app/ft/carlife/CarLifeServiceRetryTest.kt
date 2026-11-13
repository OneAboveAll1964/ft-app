package app.ft.carlife

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class CarLifeServiceRetryTest {
    @Test fun aCompletedReplyCannotApproveRetryAfterItsBluetoothGenerationChanged() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val savedState = CarLifeService.state.value
        try {
            val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
            val finder = mock(CarFinder::class.java)
            val bt = mock(CarBluetooth::class.java)
            val wireless = mock(CarWirelessSetup::class.java)
            val reply = CompletableDeferred<Boolean>()
            val prepared = CompletableDeferred<Unit>()
            set(service, "tag", "CarLifeRetryTest")
            set(service, "mode", 1)
            set(service, "finder", finder)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazyOf(wireless))
            set(service, "wirelessBluetoothGeneration", 7L)
            `when`(bt.open).thenReturn(true)
            `when`(bt.connectionGeneration).thenReturn(7L)
            `when`(wireless.exchange).thenReturn(3L)
            doAnswer { prepared.complete(Unit); reply }.`when`(wireless).restartAwaitingName(false)
            var approved = true
            val job = launch(Dispatchers.Main) { approved = service.prepareWirelessRetry(finder, false) }
            prepared.await()
            `when`(bt.connectionGeneration).thenReturn(8L)
            reply.complete(true)
            job.join()
            assertFalse(approved)
        } finally {
            @Suppress("UNCHECKED_CAST")
            val flow = CarLifeService::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(null) as kotlinx.coroutines.flow.MutableStateFlow<CarState>
            flow.value = savedState
            Dispatchers.resetMain()
        }
    }

    @Test fun queuedNameFromAnOldSocketCannotJoinBeforeTheNewSocketResetsTheExchange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
            val finder = mock(CarFinder::class.java)
            val bt = mock(CarBluetooth::class.java)
            val wireless = mock(CarWirelessSetup::class.java)
            set(service, "mode", 1)
            set(service, "scope", backgroundScope)
            set(service, "finder", finder)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazyOf(wireless))
            set(service, "wirelessBluetoothGeneration", 7L)
            `when`(wireless.exchange).thenReturn(3L)
            `when`(bt.connectionGeneration).thenReturn(7L)
            service.queueWirelessName("_OldCar")
            // New socket accepted, but its onCar callback is still waiting for wireless's monitor.
            `when`(bt.connectionGeneration).thenReturn(8L)
            runCurrent()
            verifyNoInteractions(finder)
            verify(wireless, never()).reset(anyBoolean())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun aCancelledWorkerCannotRestartAnExchangeAfterWaitingForItsMonitor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
            val finder = mock(CarFinder::class.java)
            val bt = mock(CarBluetooth::class.java)
            val wireless = mock(CarWirelessSetup::class.java)
            val entered = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            set(service, "mode", 1)
            set(service, "finder", finder)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazy {
                worker.set(Thread.currentThread())
                entered.countDown()
                wireless
            })
            `when`(bt.open).thenReturn(true)
            val job = synchronized(wireless) {
                val pending = launch(Dispatchers.Main) { service.prepareWirelessRetry(finder, false) }
                runCurrent()
                assertTrue("Main must return while IO waits for the monitor", entered.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.get().state)
                pending.cancel()
                // Simulate replacement by another connection before the old worker wakes.
                set(service, "finder", mock(CarFinder::class.java))
                pending
            }
            job.join()
            verifyNoInteractions(wireless)
            verify(bt, never()).start()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellingAfterResetCannotReopenAClosedBluetoothListener() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reset = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
            val finder = mock(CarFinder::class.java)
            val bt = mock(CarBluetooth::class.java)
            val wireless = mock(CarWirelessSetup::class.java)
            set(service, "tag", "CarLifeRetryTest")
            set(service, "mode", 1)
            set(service, "finder", finder)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazyOf(wireless))
            doAnswer {
                reset.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                null
            }.`when`(wireless).reset(false)
            val job = launch(Dispatchers.Main) { service.prepareWirelessRetry(finder, false) }
            runCurrent()
            assertTrue(reset.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
            verify(wireless).reset(false)
            verify(bt, never()).start()
        } finally {
            release.countDown()
            Dispatchers.resetMain()
        }
    }

    @Test fun retryWritesOnIoAndPreservesTheLiveGroupPolicy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
            val finder = mock(CarFinder::class.java)
            val bt = mock(CarBluetooth::class.java)
            val wireless = mock(CarWirelessSetup::class.java)
            val mainThread = Thread.currentThread()
            val writer = AtomicReference<Thread>()
            set(service, "tag", "CarLifeRetryTest")
            set(service, "mode", 1)
            set(service, "finder", finder)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazyOf(wireless))
            `when`(bt.open).thenReturn(true)
            doAnswer { writer.set(Thread.currentThread()); CompletableDeferred<Boolean>() }.`when`(wireless).restartAwaitingName(true)
            val state = CarLifeService.state.value
            try {
                launch(Dispatchers.Main) { service.prepareWirelessRetry(finder, true) }.join()
            } finally {
                @Suppress("UNCHECKED_CAST")
                val flow = CarLifeService::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(null) as kotlinx.coroutines.flow.MutableStateFlow<CarState>
                flow.value = state
            }
            assertNotNull(writer.get())
            assertNotSame(mainThread, writer.get())
            verify(wireless).restartAwaitingName(true)
            verify(bt, never()).start()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun anUnansweredInitialBluetoothExchangeIsRetriedOnIo() = startupCheck { h ->
        val sent = CompletableDeferred<Thread>()
        val mainThread = Thread.currentThread()
        doAnswer { sent.complete(Thread.currentThread()); true }.`when`(h.wireless).begin()
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(7_999)
        verify(h.wireless, never()).begin()
        h.service.checkWirelessStartup(8_000)
        assertNotSame(mainThread, sent.await())
        verify(h.wireless).begin()
        verify(h.finder, never()).retry()
        verify(h.link, never()).retryCommand(anyLong(), anyWaiting())
    }

    @Test fun aQueuedBluetoothRetryCannotTouchAReplacementSocket() = startupCheck { h ->
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(8_000)
        `when`(h.bt.connectionGeneration).thenReturn(8L)
        runCurrent()
        verify(h.wireless, never()).begin()
    }

    @Test fun aReplyArrivingBeforeTheQueuedRetryPreventsAnotherBluetoothRequest() = startupCheck { h ->
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(8_000)
        `when`(h.wireless.carWifiName).thenReturn("CarLife")
        runCurrent()
        verify(h.wireless, never()).begin()
    }

    @Test fun aGroupWithoutTcpIsRetriedWithoutRemovingTheGroupOrClosingListeners() = startupCheck { h ->
        `when`(h.finder.joined).thenReturn(true)
        h.state.value = h.state.value.copy(wifiDirect = true)
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(30_000)
        runCurrent()
        verify(h.finder).retry()
        verify(h.link, never()).stop()
        verify(h.link, never()).retryCommand(anyLong(), anyWaiting())
        verify(h.wireless, never()).reset(anyBoolean())
    }

    @Test fun aGroupWithoutAnAddressIsQueriedWithoutRestartingTheProtocol() = startupCheck { h ->
        `when`(h.finder.joined).thenReturn(true)
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(20_000)
        runCurrent()
        verify(h.finder).checkExisting()
        verify(h.finder, never()).retry()
        verify(h.wireless, never()).begin()
    }

    @Test fun aStalledNegotiationRetiresOnlyItsCommandAndReusesTheGroup() = startupCheck { h ->
        `when`(h.link.connected).thenReturn(true)
        `when`(h.link.retryCommand(eq(11L), anyWaiting())).thenReturn(true)
        h.sessionState.value = CarLifeSession.State.Linked("WiFi")
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(60_000)
        runCurrent()
        verify(h.link).retryCommand(eq(11L), anyWaiting())
        verify(h.finder).retry()
        verify(h.link, never()).stop()
    }

    @Test fun aQueuedNegotiationTimeoutCannotCloseANewCommand() = startupCheck { h ->
        `when`(h.link.connected).thenReturn(true)
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(60_000)
        `when`(h.link.commandGeneration).thenReturn(12L)
        runCurrent()
        verify(h.link, never()).retryCommand(anyLong(), anyWaiting())
        verify(h.finder, never()).retry()
    }

    @Test fun completedNegotiationProjectionAndPermissionWaitCancelQueuedTimeouts() = startupCheck { h ->
        `when`(h.link.connected).thenReturn(true)
        val protected = listOf(CarLifeSession.State.Negotiated("WiFi", 1280, 720, 30),
            CarLifeSession.State.Projecting("WiFi", 1280, 720, 30))
        for (state in protected) {
            h.watch.reset()
            h.sessionState.value = CarLifeSession.State.Linked("WiFi")
            h.service.checkWirelessStartup(0)
            h.service.checkWirelessStartup(60_000)
            h.sessionState.value = state
            runCurrent()
            assertNull(h.service.wirelessStartupPoint())
        }
        h.watch.reset()
        h.sessionState.value = CarLifeSession.State.Linked("WiFi")
        h.service.checkWirelessStartup(0)
        h.service.checkWirelessStartup(60_000)
        h.state.value = h.state.value.copy(waitingForShare = true)
        runCurrent()
        assertNull(h.service.wirelessStartupPoint())
        verify(h.link, never()).retryCommand(anyLong(), anyWaiting())
        verify(h.finder, never()).retry()
    }

    @Test fun anOngoingFinderPreparationDoesNotStartAnotherRecovery() = startupCheck { h ->
        `when`(h.finder.joined).thenReturn(true)
        h.state.value = h.state.value.copy(wifiDirect = true)
        h.service.checkWirelessStartup(0)
        `when`(h.finder.preparingRetry).thenReturn(true)
        h.service.checkWirelessStartup(30_000)
        runCurrent()
        verify(h.finder, never()).retry()
        `when`(h.finder.preparingRetry).thenReturn(false)
        h.service.checkWirelessStartup(31_000)
        runCurrent()
        verify(h.finder).retry()
    }

    @Test fun anAbsentPeerGetsFourIoRequestsThenOnlyItsBluetoothConversationIsRetired() = startupCheck { h ->
        h.state.value = h.state.value.copy(carWifi = "CarLife")
        val writer = AtomicReference<Thread>()
        val mainThread = Thread.currentThread()
        doAnswer { writer.set(Thread.currentThread()); null }.`when`(h.wireless).askName()
        for (at in listOf(0L, 2_000L, 7_000L, 17_000L, 27_000L, 37_000L, 100_000L)) {
            h.service.askCarAgain(at)
        }
        assertNotSame(mainThread, writer.get())
        verify(h.wireless, times(4)).askName()
        verify(h.bt).disconnect(7L)
        verify(h.bt, never()).stop()
        verify(h.finder, never()).stop()
        verify(h.link, never()).stop()
        assertFalse(h.state.value.carWifiRecoveryNeeded)
    }

    @Test fun missingAddressRequestsQueryTheCurrentGroupWithoutRestartingTheTransport() = startupCheck { h ->
        `when`(h.wireless.waitingForAddress).thenReturn(true)
        `when`(h.wireless.carWifiName).thenReturn("CarLife")
        // This can simply be DHCP lag on an otherwise valid group.
        `when`(h.finder.joined).thenReturn(true)
        h.service.queueWirelessIpRequest()
        runCurrent()
        verify(h.finder).checkExisting()
        verify(h.finder, never()).retry()
        verify(h.bt, never()).disconnect(anyLong())
        verify(h.wireless, never()).reset(anyBoolean())
        assertFalse(h.state.value.carWifiRecoveryNeeded)
    }

    @Test fun aQueuedIpRequestCannotAffectAReplacementOrAlreadyRecoveredConnection() = startupCheck { h ->
        val protect = listOf<() -> Unit>(
            { `when`(h.bt.connectionGeneration).thenReturn(8L) },
            { `when`(h.wireless.exchange).thenReturn(4L) },
            { `when`(h.wireless.waitingForAddress).thenReturn(false) },
            { `when`(h.link.connected).thenReturn(true) },
            { set(h.service, "finder", mock(CarFinder::class.java)) }
        )
        for (guard in protect) {
            set(h.service, "finder", h.finder)
            `when`(h.bt.connectionGeneration).thenReturn(7L)
            `when`(h.wireless.exchange).thenReturn(3L)
            `when`(h.wireless.waitingForAddress).thenReturn(true)
            `when`(h.link.connected).thenReturn(false)
            h.service.queueWirelessIpRequest()
            guard()
            runCurrent()
        }
        verify(h.finder, never()).checkExisting()
        verify(h.bt, never()).disconnect(anyLong())
        assertFalse(h.state.value.carWifiRecoveryNeeded)
    }

    @Test fun anAbsentCarRequestingAnIpGetsAdviceOnlyAfterBoundedRecovery() = startupCheck { h ->
        h.state.value = h.state.value.copy(carWifi = "CarLife")
        `when`(h.wireless.waitingForAddress).thenReturn(true)
        for (at in listOf(0L, 2_000L, 7_000L, 17_000L, 27_000L)) h.service.askCarAgain(at)
        assertFalse(h.state.value.carWifiRecoveryNeeded)
        h.service.askCarAgain(37_000L)
        h.service.askCarAgain(100_000L)
        assertTrue(h.state.value.carWifiRecoveryNeeded)
        verify(h.bt).disconnect(7L)
        verify(h.finder, never()).retry()
        verify(h.link, never()).stop()
    }

    @Test fun recoveryCannotRetireBluetoothAfterAnyConnectionProgressOrReplacement() = startupCheck { h ->
        h.state.value = h.state.value.copy(carWifi = "CarLife")
        `when`(h.wireless.waitingForAddress).thenReturn(true)
        val protect = listOf<() -> Unit>(
            { `when`(h.finder.inSight("CarLife")).thenReturn(true) },
            { `when`(h.finder.joined).thenReturn(true) },
            { `when`(h.finder.preparingRetry).thenReturn(true) },
            { `when`(h.link.connected).thenReturn(true) },
            { `when`(h.session.projecting).thenReturn(true) },
            { h.state.value = h.state.value.copy(wifiDirect = true) },
            { `when`(h.bt.connectionGeneration).thenReturn(8L) }
        )
        for (guard in protect) {
            `when`(h.finder.inSight("CarLife")).thenReturn(false)
            `when`(h.finder.joined).thenReturn(false)
            `when`(h.finder.preparingRetry).thenReturn(false)
            `when`(h.link.connected).thenReturn(false)
            `when`(h.session.projecting).thenReturn(false)
            `when`(h.bt.connectionGeneration).thenReturn(7L)
            h.state.value = h.state.value.copy(wifiDirect = false)
            h.peerWatch.reset()
            for (at in listOf(0L, 2_000L, 7_000L, 17_000L, 27_000L)) h.service.askCarAgain(at)
            guard()
            h.service.askCarAgain(37_000L)
        }
        verify(h.bt, never()).disconnect(anyLong())
        assertFalse(h.state.value.carWifiRecoveryNeeded)
    }

    private fun anyWaiting(): () -> Boolean = any<() -> Boolean>() ?: { true }

    private fun startupCheck(block: suspend TestScope.(StartupHarness) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = CarLifeService.state.value
        try { block(StartupHarness(this)) } finally {
            stateFlow().value = saved
            Dispatchers.resetMain()
        }
    }

    private inner class StartupHarness(scope: TestScope) {
        val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
        val finder = mock(CarFinder::class.java)
        val link = mock(WifiChannelLink::class.java)
        val bt = mock(CarBluetooth::class.java)
        val wireless = mock(CarWirelessSetup::class.java)
        val session = mock(CarLifeSession::class.java)
        val sessionState = MutableStateFlow<CarLifeSession.State>(CarLifeSession.State.Idle)
        val state = stateFlow()
        val watch = CarLifeStartupWatch()
        val peerWatch = WirelessPeerWatch()
        init {
            state.value = state.value.copy(wifiDirect = false, waitingForShare = false, carWifiRecoveryNeeded = false)
            set(service, "tag", "CarLifeRetryTest")
            set(service, "mode", 1)
            set(service, "scope", scope.backgroundScope)
            set(service, "finder", finder)
            set(service, "wifiLink", link)
            set(service, "session", session)
            set(service, "startupWatch", watch)
            set(service, "peerWatch", peerWatch)
            set(service, "bt\$delegate", lazyOf(bt))
            set(service, "wireless\$delegate", lazyOf(wireless))
            set(service, "wirelessBluetoothGeneration", 7L)
            `when`(bt.open).thenReturn(true)
            `when`(bt.connectionGeneration).thenReturn(7L)
            `when`(wireless.exchange).thenReturn(3L)
            `when`(finder.connectionGeneration).thenReturn(9L)
            `when`(link.commandGeneration).thenReturn(11L)
            `when`(session.state).thenReturn(sessionState)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow() = CarLifeService::class.java.getDeclaredField("_state").apply { isAccessible = true }
        .get(null) as MutableStateFlow<CarState>

    private fun set(service: CarLifeService, name: String, value: Any?) {
        CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
