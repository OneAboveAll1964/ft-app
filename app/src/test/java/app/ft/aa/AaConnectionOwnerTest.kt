package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaConnectionOwnerTest {
    @Test fun acceptedConnectionWinsOverAPendingDialEvenIfItClosesBeforeDialCompletes() {
        val owner = AaConnectionOwner<Any>()
        val run = owner.start()
        val pendingDial = owner.idleGeneration(run)!!
        val incoming = Any()
        assertTrue(owner.attach(pendingDial, { incoming }, {}))
        owner.release(incoming) { assertTrue(it) }
        assertFalse(owner.attach(pendingDial, { error("stale dial must be discarded") }, {}))
        assertTrue(owner.attach(owner.idleGeneration(run)!!, { Any() }, {}))
    }

    @Test fun liveConnectionCannotBeReplacedAndOldEventsCannotChangeNewConnection() {
        val owner = AaConnectionOwner<Any>()
        val run = owner.start()
        val first = Any()
        val token = owner.idleGeneration(run)!!
        owner.attach(token, { first }, {})
        assertNull(owner.idleGeneration(run))
        assertFalse(owner.attach(token, { error("a live session must remain owned") }, {}))
        owner.release(first) {}
        val second = Any()
        owner.attach(owner.idleGeneration(run)!!, { second }, {})
        owner.release(first) { fail("late close cleared the new connection") }
        owner.withCurrent(first) { fail("late phase/name event updated the new connection") }
        var delivered = false
        owner.withCurrent(second) { delivered = true }
        assertTrue(delivered)
    }

    @Test fun shutdownRejectsPendingDialAndRestartWaitsForOldMediaCleanup() {
        val owner = AaConnectionOwner<Any>()
        val firstRun = owner.start()
        val token = owner.idleGeneration(firstRun)!!
        owner.stop()
        assertFalse(owner.attach(token, { error("shutdown must invalidate the dial") }, {}))
        val secondRun = owner.start()
        assertNull(owner.idleGeneration(firstRun)) // An old accept resumed after restart.
        assertFalse(owner.withRun(firstRun) { fail("old listener published its server") })
        val session = Any()
        owner.attach(owner.idleGeneration(secondRun)!!, { session }, {})
        assertSame(session, owner.stop())
        val thirdRun = owner.start()
        assertNull(owner.idleGeneration(thirdRun)) // close() has not finished its cleanup yet.
        owner.release(session) {}
        assertNotNull(owner.idleGeneration(thirdRun))
    }
}
