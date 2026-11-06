package app.ft.carlife

import org.junit.Assert.*
import org.junit.Test

class WirelessPeerWatchTest {
    @Test fun repeatedAnnouncementsDoNotPostponeReconnectingAnAbsentCar() {
        val watch = WirelessPeerWatch()
        assertNull(watch.next(7, "Car", true, 0))
        val asks = listOf(2_000L, 7_000L, 17_000L, 27_000L)
        for (time in asks) {
            assertNull(watch.next(7, "Car", true, time - 1))
            assertEquals(WirelessPeerWatch.Action.ASK_NAME, watch.next(7, "Car", true, time))
        }
        assertNull(watch.next(7, "Car", true, 36_999))
        assertEquals(WirelessPeerWatch.Action.RECONNECT_BLUETOOTH, watch.next(7, "Car", true, 37_000))
        assertNull("one disconnect per absent conversation", watch.next(7, "Car", true, 100_000))
    }

    @Test fun discoveryOrConnectionProgressCancelsTheOldAbsentPeriod() {
        val watch = WirelessPeerWatch()
        watch.next(7, "Car", true, 0)
        assertEquals(WirelessPeerWatch.Action.ASK_NAME, watch.next(7, "Car", true, 2_000))
        assertNull(watch.next(7, "Car", false, 7_000))
        assertNull(watch.next(7, "Car", false, 100_000))
        assertNull(watch.next(7, "Car", true, 101_000))
        assertEquals(WirelessPeerWatch.Action.ASK_NAME, watch.next(7, "Car", true, 103_000))
    }

    @Test fun anotherSocketOrTargetGetsItsOwnBudget() {
        for (replacement in listOf(8L to "Car", 7L to "Other")) {
            val watch = WirelessPeerWatch()
            watch.next(7, "Car", true, 0)
            watch.next(7, "Car", true, 2_000)
            assertNull(watch.next(replacement.first, replacement.second, true, 40_000))
            assertEquals(WirelessPeerWatch.Action.ASK_NAME,
                watch.next(replacement.first, replacement.second, true, 42_000))
        }
    }
}
