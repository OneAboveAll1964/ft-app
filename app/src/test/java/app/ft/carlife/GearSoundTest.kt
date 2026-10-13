package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GearSoundTest {
    @Test
    fun reverseTurnsTheSoundDownAndLeavingBringsItBack() {
        val g = GearSound()
        val down = g.shift(GearAction.SOUND_LOWER, 20, playing = true)
        assertEquals(0.2f, down.level, 0.0001f)
        assertFalse(down.pause)
        val back = g.shift(GearAction.SOUND_KEEP, 20, playing = true)
        assertEquals(1f, back.level, 0.0001f)
        assertFalse(back.resume)
    }

    @Test
    fun reversePausesOnlyWhatWasPlayingAndLeavingPlaysItAgain() {
        val g = GearSound()
        val into = g.shift(GearAction.SOUND_PAUSE, 0, playing = true)
        assertTrue(into.pause)
        val out = g.shift(GearAction.SOUND_KEEP, 0, playing = false)
        assertTrue(out.resume)
        assertFalse(g.paused)
    }

    @Test
    fun nothingPlayingInReverseMeansNothingStartsWhenLeaving() {
        val g = GearSound()
        val into = g.shift(GearAction.SOUND_PAUSE, 0, playing = false)
        assertFalse(into.pause)
        val out = g.shift(GearAction.SOUND_KEEP, 0, playing = false)
        assertFalse(out.resume)
    }

    @Test
    fun goingFromReverseStraightToAnotherPausingGearKeepsItPausedUntilDrive() {
        val g = GearSound()
        assertTrue(g.shift(GearAction.SOUND_PAUSE, 0, playing = true).pause)
        val park = g.shift(GearAction.SOUND_PAUSE, 0, playing = false)
        assertFalse(park.pause)
        assertFalse(park.resume)
        assertTrue(g.shift(GearAction.SOUND_KEEP, 0, playing = false).resume)
    }

    @Test
    fun reverseThenParkWithALowerLevelSwapsTheLevelAndResumes() {
        val g = GearSound()
        g.shift(GearAction.SOUND_PAUSE, 0, playing = true)
        val park = g.shift(GearAction.SOUND_LOWER, 40, playing = false)
        assertTrue(park.resume)
        assertEquals(0.4f, park.level, 0.0001f)
    }

    @Test
    fun aNewCarConnectionForgetsAnythingLeftOver() {
        val g = GearSound()
        g.shift(GearAction.SOUND_PAUSE, 0, playing = true)
        g.forget()
        assertFalse(g.shift(GearAction.SOUND_KEEP, 0, playing = false).resume)
        assertEquals(1f, g.level, 0.0001f)
    }

    @Test
    fun parkThatPausesKeepsReverseTurnedDownUntilItPlaysAgain() {
        val g = GearSound()
        g.shift(GearAction.SOUND_LOWER, 20, playing = true)
        val park = g.shift(GearAction.SOUND_PAUSE, 0, playing = true)
        assertTrue(park.pause)
        assertEquals(0.2f, park.level, 0.0001f)
        val drive = g.shift(GearAction.SOUND_KEEP, 0, playing = false)
        assertTrue(drive.resume)
        assertEquals(1f, drive.level, 0.0001f)
    }

    @Test
    fun aReconnectInParkWithMusicPlayingPausesAgain() {
        val g = GearSound()
        assertTrue(g.shift(GearAction.SOUND_PAUSE, 0, playing = true).pause)
        g.forget()
        assertTrue(g.shift(GearAction.SOUND_PAUSE, 0, playing = true).pause)
    }

    @Test
    fun pausedParkGoesBackToFullOnceThePauseHasTakenHold() {
        val g = GearSound()
        g.shift(GearAction.SOUND_LOWER, 0, playing = true)
        assertTrue(g.shift(GearAction.SOUND_PAUSE, 0, playing = true).pause)
        assertEquals(0f, g.level, 0.0001f)
        assertTrue(g.settle())
        assertEquals(1f, g.level, 0.0001f)
        assertFalse(g.settle())
        val drive = g.shift(GearAction.SOUND_KEEP, 0, playing = false)
        assertTrue(drive.resume)
        assertEquals(1f, drive.level, 0.0001f)
    }
}
