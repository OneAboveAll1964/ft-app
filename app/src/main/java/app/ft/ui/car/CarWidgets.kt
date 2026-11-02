package app.ft.ui.car

import androidx.compose.foundation.layout.Arrangement
import app.ft.ui.components.InkText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ft.R
import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import app.ft.core.VehicleTelemetry
import kotlinx.coroutines.delay
import app.ft.ui.theme.ownDirection
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarWidgets(style: CarStyle, modifier: Modifier = Modifier, preview: Boolean = false) {
    if (style.widgets.isEmpty()) return
    val vehicle by VehicleTelemetry.current.collectAsState()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    val freshVehicle = vehicle?.takeIf { it.fresh(now) }
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        style.widgets.forEach { key ->
            when (key) {
                CarStyle.WIDGET_WEATHER -> WeatherChip(style.fahrenheit, preview)
                CarStyle.WIDGET_BATTERY -> BatteryChip(preview)
                CarStyle.WIDGET_SPEED -> SpeedChip(style.mph, preview, freshVehicle)
                CarStyle.WIDGET_COORDINATES -> CoordinatesChip(freshVehicle, preview, now)
                CarStyle.WIDGET_COURSE -> CourseChip(freshVehicle, preview, now)
                CarStyle.WIDGET_CALENDAR -> EventChip(style.clock24, preview)
            }
        }
    }
}

@Composable
private fun WeatherChip(fahrenheit: Boolean, preview: Boolean) {
    val weather by CarInfo.weather.collectAsState()
    val reading = weather ?: if (preview) CarInfo.Weather(22.0, 0, 0L) else return
    val degrees = if (fahrenheit) reading.celsius * 9.0 / 5.0 + 32.0 else reading.celsius
    val unit = if (fahrenheit) "F" else "C"
    Chip(weatherIcon(reading.code), "${degrees.roundToInt()}°$unit", stringResource(CarInfo.label(reading.code)))
}

@Composable
private fun BatteryChip(preview: Boolean) {
    val live by CarInfo.battery.collectAsState()
    val charging by CarInfo.charging.collectAsState()
    val level = if (live < 0 && preview) 80 else live
    if (level < 0) return
    Chip(CarIcons.Battery, "$level%", if (charging) stringResource(R.string.widget_charging) else null)
}

@Composable
private fun SpeedChip(mph: Boolean, preview: Boolean, vehicle: VehicleLocation?) {
    val live by CarInfo.speed.collectAsState()
    // Do not label an unknown Baidu/ICCOA wire integer as km/h.
    val carSpeed = vehicle?.speedKmh?.takeIf { it.isFinite() && it in 0f..450f }
    val speed = carSpeed ?: if (live < 0f && preview) 60f else live
    if (speed < 0f) return
    val shown = if (mph) speed * 0.621371f else speed
    val source = if (carSpeed != null) (vehicle?.source ?: "CAR") else "PHONE"
    val raw = vehicle?.speedRaw
    val suffix = if (carSpeed == null && raw != null) " · ${vehicle?.source} raw=$raw" else " · $source"
    Chip(CarIcons.Speed, "${shown.roundToInt()}",
        "${stringResource(if (mph) R.string.widget_mph else R.string.widget_kmh)}$suffix")
}

@Composable
private fun CoordinatesChip(vehicle: VehicleLocation?, preview: Boolean, now: Long) {
    val fix = vehicle?.takeIf { it.positionFresh(now) }
    val latitude = fix?.displayLatitude
    val longitude = fix?.displayLongitude
    val value = when {
        latitude != null && longitude != null -> String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        fix?.rawLatitude != null && fix.rawLongitude != null ->
            "${fix.rawLatitude}, ${fix.rawLongitude}"
        else -> "—"
    }
    val detail = if (latitude != null) "${fix?.source ?: ""} · datum?"
        else if (fix?.rawLatitude != null) "${fix.source} · raw"
        else "${vehicle?.source ?: "CAR"} · NO FIX"
    Chip(CarIcons.Map, value, detail, wide = true)
}

@Composable
private fun CourseChip(vehicle: VehicleLocation?, preview: Boolean, now: Long) {
    val fix = vehicle?.takeIf { it.positionFresh(now) }
    val degrees = fix?.headingDegrees?.takeIf { it.isFinite() && it in 0f..360f }
    val raw = fix?.headingRaw
    val label = when {
        degrees != null -> "${degrees.roundToInt()}°"
        raw != null -> "$raw"
        else -> "—"
    }
    val detail = if (degrees != null) (fix?.source ?: "CAR")
        else if (raw != null) "${fix?.source ?: "CAR"} · raw"
        else "${vehicle?.source ?: "CAR"} · NO FIX"
    Chip(CarIcons.Rotate, label, detail)
}

@Composable
private fun EventChip(clock24: Boolean, preview: Boolean) {
    val event by CarInfo.event.collectAsState()
    val next = event ?: if (preview) CarInfo.Event(stringResource(R.string.widget_sample_event), sampleStart(), false) else null
    if (next == null) {
        Chip(CarIcons.Calendar, stringResource(R.string.widget_no_event), null)
        return
    }
    val am = stringResource(R.string.car_am)
    val pm = stringResource(R.string.car_pm)
    val when_ = if (next.allDay) null else time(next.start, clock24, am, pm)
    Chip(CarIcons.Calendar, next.title, when_, wide = true)
}

private fun time(millis: Long, clock24: Boolean, am: String, pm: String): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    if (clock24) return String.format(Locale.US, "%02d:%02d", hour, minute)
    val half = if (hour < 12) am else pm
    return String.format(Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, half)
}

private fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> CarIcons.Sunny
    45, 48, 2, 3 -> CarIcons.Cloud
    71, 73, 75, 77, 85, 86 -> CarIcons.Snow
    else -> CarIcons.Rain
}

@Composable
private fun Chip(icon: ImageVector, value: String, detail: String?, wide: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalCarCard.current,
        contentColor = Color.White
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = if (wide) 320.dp else 220.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            if (wide) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall.ownDirection(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                InkText(value, MaterialTheme.typography.titleSmall.ownDirection())
            }
            if (detail != null) {
                Spacer(Modifier.width(6.dp))
                InkText(detail, MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
            }
        }
    }
}

private fun sampleStart(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.MINUTE, 30)
}.timeInMillis
