package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunTimesTest {
    private val erbil = 36.19 to 44.01
    private val baghdadZone: TimeZone = TimeZone.getTimeZone("Asia/Baghdad")

    private fun noonUtc(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun midsummerInErbilHasAnEarlySunriseAndLateSunset() {
        val day = SunTimes.of(erbil.first, erbil.second, noonUtc(2026, 6, 21), baghdadZone)!!
        assertTrue("sunrise ${day.sunrise}", day.sunrise in (4 * 60)..(6 * 60))
        assertTrue("sunset ${day.sunset}", day.sunset in (18 * 60)..(20 * 60 + 30))
        assertTrue(day.sunset > day.sunrise)
    }

    @Test
    fun midwinterDaysAreShorterThanMidsummerDays() {
        val summer = SunTimes.of(erbil.first, erbil.second, noonUtc(2026, 6, 21), baghdadZone)!!
        val winter = SunTimes.of(erbil.first, erbil.second, noonUtc(2026, 12, 21), baghdadZone)!!
        assertTrue(winter.sunset - winter.sunrise < summer.sunset - summer.sunrise)
    }

    @Test
    fun nightIsBeforeSunriseAndAfterSunset() {
        val day = SunTimes.Day(sunrise = 6 * 60, sunset = 18 * 60)
        assertTrue(SunTimes.night(5 * 60, day))
        assertFalse(SunTimes.night(12 * 60, day))
        assertTrue(SunTimes.night(19 * 60, day))
        assertFalse(SunTimes.night(6 * 60, day))
        assertTrue(SunTimes.night(18 * 60, day))
    }

    @Test
    fun withoutAPlaceItFallsBackToFixedHours() {
        assertTrue(SunTimes.night(2 * 60, null))
        assertFalse(SunTimes.night(12 * 60, null))
        assertTrue(SunTimes.night(23 * 60, null))
    }

    @Test
    fun polarWinterIsAlwaysNightAndPolarSummerIsAlwaysDay() {
        val zone = TimeZone.getTimeZone("UTC")
        val polarNight = SunTimes.of(78.0, 15.0, noonUtc(2026, 12, 21), zone)!!
        val polarDay = SunTimes.of(78.0, 15.0, noonUtc(2026, 6, 21), zone)!!
        assertTrue(polarNight.polar)
        assertTrue(polarDay.polar)
        assertTrue(SunTimes.night(12 * 60, polarNight))
        assertFalse(SunTimes.night(2 * 60, polarDay))
    }

    @Test
    fun impossibleCoordinatesGiveNothing() {
        assertNull(SunTimes.of(120.0, 0.0, noonUtc(2026, 6, 21), baghdadZone))
        assertNull(SunTimes.of(0.0, 400.0, noonUtc(2026, 6, 21), baghdadZone))
    }

    @Test
    fun theEquatorSplitsTheDayRoughlyInHalf() {
        val day = SunTimes.of(0.0, 0.0, noonUtc(2026, 3, 21), TimeZone.getTimeZone("UTC"))!!
        assertEquals(12 * 60, day.sunset - day.sunrise, 20)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
