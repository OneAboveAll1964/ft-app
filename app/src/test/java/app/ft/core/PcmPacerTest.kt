package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPacerTest {
    private val rate = 192000
    private val ms = 1_000_000L

    private fun pacer(leadMs: Int = 150, maxMs: Int = 2000) = PcmPacer(rate, leadMs, maxMs, 3840, "test", threaded = false) { }

    private fun bytes(list: List<ByteArray>) = list.sumOf { it.size }

    @Test
    fun aBurstIsLetOutAtRealTimeAfterTheLead() {
        val p = pacer()
        p.add(ByteArray(rate))
        val first = bytes(p.due(1 * ms))
        assertTrue("the first release should be the lead only, was $first", first in 26880..28800)
        var half = 0
        for (t in 11..501 step 10) half += bytes(p.due(t * ms))
        assertTrue("half a second later about half a second more should go, was $half", half in 92160..99840)
        assertEquals(rate - first - half, p.backlog.toInt())
    }

    @Test
    fun steadyRealTimeInputNeverRunsAheadOfTheClock() {
        val p = pacer()
        var fed = 0L
        var sent = 0L
        var now = 1 * ms
        repeat(1500) { i ->
            if (i % 2 == 0) {
                p.add(ByteArray(7680))
                fed += 7680
            }
            sent += bytes(p.due(now))
            val allowed = (now - 1 * ms) * rate / 1_000_000_000L + 28800
            assertTrue("sent $sent more than real time allows ($allowed)", sent <= allowed)
            now += 20 * ms
        }
        assertTrue("almost everything fed should have gone out, fed $fed sent $sent", fed - sent <= 28800)
    }

    @Test
    fun holdingStopsTheFlowAndLettingGoResumesIt() {
        val p = pacer()
        p.add(ByteArray(rate))
        p.due(1 * ms)
        p.hold(true)
        assertEquals(0, bytes(p.due(400 * ms)))
        val left = p.backlog
        p.hold(false)
        val resumed = bytes(p.due(401 * ms))
        assertTrue("after letting go the lead should flow again, was $resumed", resumed in 1..28800)
        assertEquals(left - resumed, p.backlog)
    }

    @Test
    fun clearingThrowsAwayWhatWasWaiting() {
        val p = pacer()
        p.add(ByteArray(rate))
        p.due(1 * ms)
        p.clear()
        assertEquals(0L, p.backlog)
        assertEquals(0, bytes(p.due(1000 * ms)))
    }

    @Test
    fun theQueueIsCappedAndTheOldestIsDropped() {
        val p = pacer(maxMs = 500)
        p.add(ByteArray(rate))
        assertTrue(p.backlog <= rate / 2)
        assertTrue(p.takeDropped() >= rate / 2 - 3840)
    }

    @Test
    fun largeBlocksAreCutIntoSmallPieces() {
        val p = pacer()
        p.add(ByteArray(10000))
        val out = p.due(1 * ms)
        assertTrue(out.all { it.size <= 3840 })
        assertEquals(10000, bytes(out))
    }

    @Test
    fun aTinyLeadStillLetsWholePiecesThrough() {
        val p = PcmPacer(rate, 1, 2000, 3840, "test", threaded = false) { }
        p.add(ByteArray(3840))
        assertEquals(3840, bytes(p.due(1 * ms)))
    }
}
