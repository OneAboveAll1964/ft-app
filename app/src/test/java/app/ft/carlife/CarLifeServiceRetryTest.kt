package app.ft.carlife

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class CarLifeServiceRetryTest {
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
            doAnswer { writer.set(Thread.currentThread()); true }.`when`(wireless).restart(true)
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
            verify(wireless).restart(true)
            verify(bt, never()).start()
        } finally { Dispatchers.resetMain() }
    }

    private fun set(service: CarLifeService, name: String, value: Any?) {
        CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
