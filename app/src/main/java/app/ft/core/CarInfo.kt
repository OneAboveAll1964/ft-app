package app.ft.core

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.provider.CalendarContract
import app.ft.FTApp
import app.ft.R
import app.ft.ui.car.CarStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object CarInfo {
    private const val TAG = "Info"
    private const val WEATHER_EVERY_MS = 20 * 60 * 1000L
    private const val CALENDAR_EVERY_MS = 5 * 60 * 1000L
    private const val AHEAD_MS = 12 * 60 * 60 * 1000L

    data class Weather(val celsius: Double, val code: Int, val at: Long)
    data class Event(val title: String, val start: Long, val allDay: Boolean)

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private val _battery = MutableStateFlow(-1)
    val battery: StateFlow<Int> = _battery

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging

    private val _speed = MutableStateFlow(-1f)
    val speed: StateFlow<Float> = _speed

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event

    private val jobs = mutableListOf<Job>()
    private var batteryWatch: BroadcastReceiver? = null
    private var speedWatch: LocationListener? = null
    private var wanted = emptyList<String>()

    fun start(context: Context, scope: CoroutineScope, widgets: List<String>) {
        if (widgets == wanted) return
        stop(context)
        wanted = widgets
        if (widgets.isEmpty()) return
        val ctx = context.applicationContext
        if (CarStyle.WIDGET_BATTERY in widgets) watchBattery(ctx)
        if (CarStyle.WIDGET_SPEED in widgets) watchSpeed(ctx)
        if (CarStyle.WIDGET_WEATHER in widgets) {
            jobs += scope.launch {
                cachedWeather()?.let { _weather.value = it }
                while (isActive) {
                    refreshWeather(ctx)
                    delay(WEATHER_EVERY_MS)
                }
            }
        }
        if (CarStyle.WIDGET_CALENDAR in widgets) {
            jobs += scope.launch {
                while (isActive) {
                    _event.value = withContext(Dispatchers.IO) { nextEvent(ctx) }
                    delay(CALENDAR_EVERY_MS)
                }
            }
        }
    }

    fun stop(context: Context) {
        wanted = emptyList()
        jobs.forEach { it.cancel() }
        jobs.clear()
        val ctx = context.applicationContext
        batteryWatch?.let { runCatching { ctx.unregisterReceiver(it) } }
        batteryWatch = null
        speedWatch?.let { listener ->
            runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener) }
        }
        speedWatch = null
        _speed.value = -1f
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun needsLocation(context: Context) =
        !granted(context, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun needsCalendar(context: Context) = !granted(context, Manifest.permission.READ_CALENDAR)

    private fun watchBattery(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) _battery.value = level * 100 / scale
                val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                _charging.value = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }.getOrNull()
        batteryWatch = receiver
        sticky?.let { receiver.onReceive(context, it) }
    }

    private fun watchSpeed(context: Context) {
        if (needsLocation(context)) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = LocationListener { location ->
            _speed.value = if (location.hasSpeed()) location.speed * 3.6f else -1f
            remember(location)
        }
        val ok = runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            true
        }.getOrDefault(false)
        if (ok) speedWatch = listener else DiagLog.w(TAG, "no GPS for the speed widget")
    }

    private fun remember(location: Location) {
        val prefs = FTApp.instance.prefs
        prefs.lastLatitude = location.latitude.toFloat()
        prefs.lastLongitude = location.longitude.toFloat()
    }

    private fun cachedWeather(): Weather? {
        val prefs = FTApp.instance.prefs
        val at = prefs.weatherAt
        if (at == 0L) return null
        return Weather(prefs.weatherCelsius.toDouble(), prefs.weatherCode, at)
    }

    private suspend fun refreshWeather(context: Context) {
        val point = point(context) ?: return
        val fetched = withContext(Dispatchers.IO) { fetchWeather(point.first, point.second) } ?: return
        val prefs = FTApp.instance.prefs
        prefs.weatherCelsius = fetched.celsius.toFloat()
        prefs.weatherCode = fetched.code
        prefs.weatherAt = fetched.at
        _weather.value = fetched
    }

    private fun point(context: Context): Pair<Double, Double>? {
        val prefs = FTApp.instance.prefs
        if (!needsLocation(context)) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val last = runCatching {
                manager?.getProviders(true)?.asSequence()
                    ?.mapNotNull { manager.getLastKnownLocation(it) }
                    ?.maxByOrNull { it.time }
            }.getOrNull()
            if (last != null) {
                remember(last)
                return last.latitude to last.longitude
            }
        }
        val latitude = prefs.lastLatitude.toDouble()
        val longitude = prefs.lastLongitude.toDouble()
        if (latitude == 0.0 && longitude == 0.0) return null
        return latitude to longitude
    }

    internal fun fetchWeather(latitude: Double, longitude: Double): Weather? {
        var connection: HttpURLConnection? = null
        return runCatching {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                    latitude, longitude
                )
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            parseWeather(connection!!.inputStream.bufferedReader().use { it.readText() })
        }.onFailure { DiagLog.w(TAG, "weather not fetched: ${it.message}") }
            .also { runCatching { connection?.disconnect() } }
            .getOrNull()
    }

    internal fun parseWeather(body: String): Weather? = runCatching {
        val current = JSONObject(body).getJSONObject("current")
        Weather(current.getDouble("temperature_2m"), current.optInt("weather_code", -1), System.currentTimeMillis())
    }.getOrNull()

    private fun nextEvent(context: Context): Event? {
        if (needsCalendar(context)) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath((now + AHEAD_MS).toString())
            .build()
        val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
        return runCatching {
            context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
                var found: Event? = null
                while (found == null && cursor.moveToNext()) {
                    val title = cursor.getString(0)?.trim().orEmpty()
                    val start = cursor.getLong(1)
                    val allDay = cursor.getInt(2) == 1
                    if (title.isNotEmpty() && (allDay || start >= now)) found = Event(title, start, allDay)
                }
                found
            }
        }.onFailure { DiagLog.w(TAG, "calendar not read: ${it.message}") }.getOrNull()
    }

    fun label(code: Int): Int = when (code) {
        0, 1 -> R.string.weather_clear
        2, 3 -> R.string.weather_cloudy
        45, 48 -> R.string.weather_fog
        51, 53, 55, 56, 57 -> R.string.weather_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.string.weather_rain
        71, 73, 75, 77, 85, 86 -> R.string.weather_snow
        95, 96, 99 -> R.string.weather_thunder
        else -> R.string.weather_clear
    }
}
