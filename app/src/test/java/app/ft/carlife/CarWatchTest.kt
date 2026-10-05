package app.ft.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWatchTest {
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
