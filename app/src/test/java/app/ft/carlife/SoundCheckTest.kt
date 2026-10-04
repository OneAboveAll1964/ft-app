package app.ft.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCheckTest {
    private fun check(running: Long = 10_000, sinceLoud: Long = 10_000, phone: Boolean = true, ft: Boolean = false, aa: Boolean = false, call: Boolean = false) =
        SoundCheck.blocked(running, sinceLoud, phone, ft, aa, call)

    @Test
    fun anAppPlayingWhileTheCarHearsSilenceIsBlocked() = assertTrue(check())

    @Test
    fun soundThatReachesTheCarIsFine() = assertFalse(check(sinceLoud = 1_000))

    @Test
    fun aQuietMomentRightAfterSharingStartsIsNotJudged() = assertFalse(check(running = 3_000, sinceLoud = 3_000))

    @Test
    fun nothingPlayingMeansNothingToReport() = assertFalse(check(phone = false))

    @Test
    fun ftsOwnPlayerAndroidAutoAndCallsAreNeverCounted() {
        assertFalse(check(ft = true))
        assertFalse(check(aa = true))
        assertFalse(check(call = true))
    }
}
