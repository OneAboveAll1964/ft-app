package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import app.ft.projection.VideoPlans

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ft", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun set(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun set(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun set(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun flt(key: String, def: Float) = sp.getFloat(key, def)
    private fun set(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    private fun lng(key: String, def: Long) = sp.getLong(key, def)
    private fun set(key: String, v: Long) = sp.edit().putLong(key, v).apply()

    val all: Map<String, *> get() = sp.all
    fun put(key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            null -> e.remove(key)
        }
        e.apply()
    }

    var cmdPort: Int get() = int("cmdPort", 7240); set(v) = set("cmdPort", v)
    var videoPort: Int get() = int("videoPort", 8240); set(v) = set("videoPort", v)
    var mediaPort: Int get() = int("mediaPort", 9240); set(v) = set("mediaPort", v)
    var ttsPort: Int get() = int("ttsPort", 9241); set(v) = set("ttsPort", v)
    var vrPort: Int get() = int("vrPort", 9242); set(v) = set("vrPort", v)
    var touchPort: Int get() = int("touchPort", 9340); set(v) = set("touchPort", v)

    var aaPort: Int get() = int("aaPort", 5288); set(v) = set("aaPort", v)
    var aaSelfPort: Int get() = int("aaSelfPort", 5277); set(v) = set("aaSelfPort", v)
    var aaWidth: Int get() = int("aaWidth", 1280); set(v) = set("aaWidth", v)
    var aaHeight: Int get() = int("aaHeight", 720); set(v) = set("aaHeight", v)
    var aaFps: Int get() = int("aaFps", 30); set(v) = set("aaFps", v)
    var aaDensity: Int get() = int("aaDensity", 160); set(v) = set("aaDensity", v)
    var aaControlsLeft: Boolean get() = bool("aaControlsLeft", true); set(v) = set("aaControlsLeft", v)
    var aaMatchCar: Boolean get() = bool("aaMatchCar", true); set(v) = set("aaMatchCar", v)

    var videoSize: Int get() = int("pictureSize", VideoPlans.SIZE_CAR); set(v) = set("pictureSize", v)
    var videoWidth: Int get() = int("videoWidth", 0); set(v) = set("videoWidth", v)
    var videoHeight: Int get() = int("videoHeight", 0); set(v) = set("videoHeight", v)
    var videoFps: Int get() = int("videoFps", 0); set(v) = set("videoFps", v)
    var videoMinFps: Int get() = int("videoMinFps", 0); set(v) = set("videoMinFps", v)
    var videoBitrate: Int get() = int("pictureBitrate", VideoPlans.DEFAULT_BITRATE); set(v) = set("pictureBitrate", v)
    var videoQpFloor: Int get() = int("videoQpFloor", 22); set(v) = set("videoQpFloor", v)
    var carDensity: Int get() = int("carDensity", 160); set(v) = set("carDensity", v)
    var carSizedApps: Boolean get() = bool("carSizedApps", false); set(v) = set("carSizedApps", v)
    var muteWhileProjecting: Boolean get() = bool("muteWhileProjecting", true); set(v) = set("muteWhileProjecting", v)
    var soundOverBluetooth: Boolean get() = bool("soundOverBluetooth", false); set(v) = set("soundOverBluetooth", v)
    var hideChatAudio: Boolean get() = bool("hideChatAudio", true); set(v) = set("hideChatAudio", v)

    var aaBluetooth: Boolean get() = bool("aaBluetooth", false); set(v) = set("aaBluetooth", v)
    var aaPackage: String get() = str("aaPackage", "com.google.android.projection.gearhead"); set(v) = set("aaPackage", v)
    var aaAutoStart: Boolean get() = bool("aaAutoStart", false); set(v) = set("aaAutoStart", v)
    var aaCorner: Int get() = int("aaCorner", 0); set(v) = set("aaCorner", v)
    var carBackground: String get() = str("carBackground", "deep"); set(v) = set("carBackground", v)
    var carAccent: Int get() = int("carAccent", 0xFF5FE3C0.toInt()); set(v) = set("carAccent", v)
    var carTiles: String get() = str("carTiles", "music,videos,youtube,maps,browser,apps"); set(v) = set("carTiles", v)
    var carClock24: Boolean get() = bool("carClock24", true); set(v) = set("carClock24", v)
    var carShowLink: Boolean get() = bool("carShowLink", false); set(v) = set("carShowLink", v)
    var carSongInfo: Boolean get() = bool("carSongInfo", true); set(v) = set("carSongInfo", v)

    var language: String get() = str("language", ""); set(v) = set("language", v)
    var themeMode: Int get() = int("themeMode", 0); set(v) = set("themeMode", v)

    var carName: String get() = str("carName", "FT"); set(v) = set("carName", v)
    var autoConnect: Boolean get() = bool("autoConnect", false); set(v) = set("autoConnect", v)
    var carP2pName: String get() = str("carP2pName", ""); set(v) = set("carP2pName", v)
    var carBtName: String get() = str("carBtName", ""); set(v) = set("carBtName", v)
    var carBtAddress: String get() = str("carBtAddress", ""); set(v) = set("carBtAddress", v)
    var swapTrackKeys: Boolean get() = bool("swapTrackKeys", false); set(v) = set("swapTrackKeys", v)
    var guidanceMode: Int get() = int("directions", CarAudioBus.GUIDANCE_UNTOUCHED); set(v) = set("directions", v)
    var linkMode: Int get() = int("linkMode", 0); set(v) = set("linkMode", v)
    var carWpsPin: String get() = str("carWpsPin", ""); set(v) = set("carWpsPin", v)

    var mapsPackage: String get() = str("pkgMaps", "com.google.android.apps.maps"); set(v) = set("pkgMaps", v)
    var musicPackage: String get() = str("pkgMusic", "com.spotify.music"); set(v) = set("pkgMusic", v)
    var videoPackage: String get() = str("pkgVideo", "com.teamsmart.videomanager.tv"); set(v) = set("pkgVideo", v)
    var phonePackage: String get() = str("pkgPhone", "com.samsung.android.dialer"); set(v) = set("pkgPhone", v)

    var carInfoRight: Boolean get() = bool("carInfoRight", false); set(v) = set("carInfoRight", v)
    var carColumns: Int get() = int("carColumns", 0); set(v) = set("carColumns", v)
    var carShowClock: Boolean get() = bool("carShowClock", true); set(v) = set("carShowClock", v)
    var carShowMusic: Boolean get() = bool("carShowMusic", true); set(v) = set("carShowMusic", v)
    var carShowAa: Boolean get() = bool("carShowAa", true); set(v) = set("carShowAa", v)
    var carShowCarHome: Boolean get() = bool("carShowCarHome", true); set(v) = set("carShowCarHome", v)

    var carWidgets: String get() = str("carWidgets", ""); set(v) = set("carWidgets", v)
    var weatherFahrenheit: Boolean get() = bool("weatherFahrenheit", false); set(v) = set("weatherFahrenheit", v)
    var speedMph: Boolean get() = bool("speedMph", false); set(v) = set("speedMph", v)
    var lastLatitude: Float get() = flt("lastLatitude", 0f); set(v) = set("lastLatitude", v)
    var lastLongitude: Float get() = flt("lastLongitude", 0f); set(v) = set("lastLongitude", v)

    var carDayNight: Boolean get() = bool("carDayNight", false); set(v) = set("carDayNight", v)
    var carNightBackground: String get() = str("carNightBackground", "black"); set(v) = set("carNightBackground", v)
    var carNightAccent: Int get() = int("carNightAccent", 0); set(v) = set("carNightAccent", v)

    var wheelLongNext: Int get() = int("wheelLongNext", 0); set(v) = set("wheelLongNext", v)
    var wheelLongPrev: Int get() = int("wheelLongPrev", 0); set(v) = set("wheelLongPrev", v)

    var openOnConnect: String get() = str("openOnConnect", ""); set(v) = set("openOnConnect", v)
    var openOnConnectSecond: String get() = str("openOnConnectSecond", ""); set(v) = set("openOnConnectSecond", v)

    var weatherCelsius: Float get() = flt("weatherCelsius", 0f); set(v) = set("weatherCelsius", v)
    var weatherCode: Int get() = int("weatherCode", -1); set(v) = set("weatherCode", v)
    var weatherAt: Long get() = lng("weatherAt", 0L); set(v) = set("weatherAt", v)

    var supercastToken: String get() = str("supercastToken", ""); set(v) = set("supercastToken", v)
    var supercastOn: Boolean get() = bool("supercastOn", false); set(v) = set("supercastOn", v)
    var supercastScreenOff: Boolean get() = bool("supercastScreenOff", true); set(v) = set("supercastScreenOff", v)
    var supercastTiles: Boolean get() = bool("supercastTiles", true); set(v) = set("supercastTiles", v)
    var supercastWay: Int get() = int("supercastWay", 0); set(v) = set("supercastWay", v)
}
