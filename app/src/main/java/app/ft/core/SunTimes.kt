package app.ft.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

object SunTimes {
    data class Day(val sunrise: Int, val sunset: Int, val polar: Boolean = false)

    const val FALLBACK_SUNRISE = 7 * 60
    const val FALLBACK_SUNSET = 19 * 60

    private const val D2R = Math.PI / 180.0
    private const val R2D = 180.0 / Math.PI

    fun of(latitude: Double, longitude: Double, millis: Long, zone: TimeZone): Day? {
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) return null
        val julian = millis / 86_400_000.0 + 2440587.5
        val since = floor(julian - 2451545.0 + 0.0008 + 0.5)
        val mean = since - longitude / 360.0
        val anomaly = (357.5291 + 0.98560028 * mean) % 360.0
        val anomalyRad = anomaly * D2R
        val centre = 1.9148 * sin(anomalyRad) + 0.0200 * sin(2 * anomalyRad) + 0.0003 * sin(3 * anomalyRad)
        val ecliptic = (anomaly + centre + 180.0 + 102.9372) % 360.0
        val eclipticRad = ecliptic * D2R
        val transit = 2451545.0 + mean + 0.0053 * sin(anomalyRad) - 0.0069 * sin(2 * eclipticRad)
        val sinDeclination = sin(eclipticRad) * sin(23.44 * D2R)
        val declination = asin(sinDeclination)
        val latRad = latitude * D2R
        val hourCos = (sin(-0.833 * D2R) - sin(latRad) * sinDeclination) / (cos(latRad) * cos(declination))
        if (hourCos > 1.0) return Day(0, 0, polar = true)
        if (hourCos < -1.0) return Day(0, 24 * 60, polar = true)
        val hour = acos(hourCos) * R2D
        return Day(minutesOf(transit - hour / 360.0, zone), minutesOf(transit + hour / 360.0, zone))
    }

    fun night(minuteOfDay: Int, day: Day?): Boolean {
        if (day == null) return minuteOfDay < FALLBACK_SUNRISE || minuteOfDay >= FALLBACK_SUNSET
        if (day.polar) return day.sunrise == day.sunset
        if (day.sunrise == day.sunset) return false
        return minuteOfDay < day.sunrise || minuteOfDay >= day.sunset
    }

    private fun minutesOf(julian: Double, zone: TimeZone): Int {
        val millis = ((julian - 2440587.5) * 86_400_000.0).toLong()
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }
}
