package app.ft.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWatchTest {
    @Test fun failedProbeDoesNotInventEvidenceOfLife() {
        val failure = java.io.IOException("Network is unreachable")
        var reported: Exception? = null
        val reachable = CarWatch.probe({ throw failure }, { reported = it })
        org.junit.Assert.assertSame(failure, reported)
        assertFalse(reachable)
        assertTrue(CarWatch.alive(reachable, 100))
        assertFalse(CarWatch.alive(reachable, 120_000))
    }

    @Test
    fun aCarThatAnswersThePingIsAlive() {
        assertTrue(CarWatch.alive(answeredPing = true, sinceProgressMs = 60_000))
    }

    @Test
    fun aCarThatSkipsThePingButStillTakesDataIsKept() {
        assertTrue(CarWatch.alive(answeredPing = false, sinceProgressMs = 40))
        assertTrue(CarWatch.alive(answeredPing = false, sinceProgressMs = 14_999))
    }

    @Test
    fun aCarThatSkipsThePingAndTakesNothingIsGone() {
        assertFalse(CarWatch.alive(answeredPing = false, sinceProgressMs = 15_000))
        assertFalse(CarWatch.alive(answeredPing = false, sinceProgressMs = 120_000))
    }
}
