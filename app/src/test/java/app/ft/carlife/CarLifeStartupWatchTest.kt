package app.ft.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeStartupWatchTest {
    @Test fun pollingDoesNotPostponeTheDeadlineAndRetriesHaveABoundedBackoff() {
        val watch = CarLifeStartupWatch()
        val point = CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.BLUETOOTH, 7, 3)
        assertNull(watch.next(point, 100))
        assertNull(watch.next(point, 8_099))
        assertEquals(1, watch.next(point, 8_100))
        assertNull(watch.next(point, 24_099))
        assertEquals(2, watch.next(point, 24_100))
        assertNull(watch.next(point, 56_099))
        assertEquals(3, watch.next(point, 56_100))
        assertNull(watch.next(point, 1_000_000))
    }

    @Test fun replacementExchangeAndProgressEachReceiveTheirOwnDeadline() {
        val watch = CarLifeStartupWatch()
        val first = CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.BLUETOOTH, 7, 3)
        watch.next(first, 0)
        val replacement = first.copy(exchange = 4)
        assertNull(watch.next(replacement, 8_000))
        assertNull(watch.next(replacement, 15_999))
        assertEquals(1, watch.next(replacement, 16_000))
        val command = CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.COMMAND, 9)
        assertNull(watch.next(command, 16_001))
        assertNull(watch.next(command, 46_000))
        assertEquals(1, watch.next(command, 46_001))
        assertNull(watch.next(command.copy(generation = 10), 46_002))
        assertNull(watch.next(null, 50_000))
        assertNull(watch.next(command, 100_000))
    }

    @Test fun aLongSchedulerPauseDoesNotFireMultipleRetriesAtOnce() {
        val watch = CarLifeStartupWatch()
        val point = CarLifeStartupWatch.Point(CarLifeStartupWatch.Stage.ADDRESS, 1)
        watch.next(point, 0)
        assertEquals(1, watch.next(point, 500_000))
        assertNull(watch.next(point, 500_000))
        assertEquals(2, watch.next(point, 540_000))
        assertNull(watch.next(point, 599_999))
        assertEquals(3, watch.next(point, 600_000))
    }
}
