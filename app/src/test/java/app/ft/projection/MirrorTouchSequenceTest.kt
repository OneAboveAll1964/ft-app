package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class MirrorTouchSequenceTest {
    @Test fun smallCloseIconTapIsAtomic() {
        val seq = MirrorTouchSequence()
        assertTrue(seq.accept(CarTouch.DOWN, 1070f, 134f, 1000).isEmpty())
        assertTrue(seq.accept(CarTouch.MOVE, 1072f, 135f, 1025).isEmpty())
        val result = seq.accept(CarTouch.UP, 1071f, 135f, 1080)
        assertEquals(1, result.size)
        assertEquals(MirrorTouchSequence.TAP, result.single().action)
        assertEquals(80L, result.single().durationMs)
    }

    @Test fun realDragRetainsAllOriginalEvents() {
        val seq = MirrorTouchSequence()
        assertTrue(seq.accept(CarTouch.DOWN, 100f, 200f, 0).isEmpty())
        assertEquals(listOf(CarTouch.DOWN, CarTouch.MOVE),
            seq.accept(CarTouch.MOVE, 130f, 205f, 40).map { it.action })
        assertEquals(listOf(CarTouch.MOVE),
            seq.accept(CarTouch.MOVE, 150f, 220f, 70).map { it.action })
        assertEquals(listOf(CarTouch.UP),
            seq.accept(CarTouch.UP, 150f, 220f, 110).map { it.action })
    }

    @Test fun releaseWithoutDownAndResetRemainSafe() {
        val seq = MirrorTouchSequence()
        assertTrue(seq.accept(CarTouch.UP, 1f, 1f, 100).isEmpty())
        seq.accept(CarTouch.DOWN, 1f, 1f, 110)
        seq.reset()
        assertTrue(seq.accept(CarTouch.UP, 1f, 1f, 150).isEmpty())
    }
}
