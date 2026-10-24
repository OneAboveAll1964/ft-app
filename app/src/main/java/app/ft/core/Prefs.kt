package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import app.ft.carlife.JoinWatch
import app.ft.projection.VideoPlans
import app.ft.projection.VideoProfile

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
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(listener)
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
    var aaFps: Int get() = app.ft.aa.AaProtocol.supportedFps(int("aaFps", 30)); set(v) = set("aaFps", app.ft.aa.AaProtocol.supportedFps(v))
    var aaDensity: Int get() = int("aaDensity", 160); set(v) = set("aaDensity", v)
    var aaControlsLeft: Boolean get() = bool("aaControlsLeft", true); set(v) = set("aaControlsLeft", v)
    var aaClusterNavigation: Boolean get() = bool("aaClusterNavigation", true); set(v) = set("aaClusterNavigation", v)
    var aaMatchCar: Boolean get() = bool("aaMatchCar", true); set(v) = set("aaMatchCar", v)

    var videoSize: Int get() = int("pictureSize", VideoPlans.SIZE_CAR); set(v) = setVideo("pictureSize", v)
    var videoWidth: Int get() = int("videoWidth", 0); set(v) = setVideo("videoWidth", v)
    var videoHeight: Int get() = int("videoHeight", 0); set(v) = setVideo("videoHeight", v)
    var videoFps: Int get() = int("videoFps", 0); set(v) = setVideo("videoFps", v)
    var videoMinFps: Int get() = int("videoMinFps", 0); set(v) = setVideo("videoMinFps", v)
    var videoBitrate: Int get() = int("pictureBitrate", VideoPlans.DEFAULT_BITRATE); set(v) = setVideo("pictureBitrate", v)
    var videoQpFloor: Int get() = int("videoQpFloor", 22); set(v) = setVideo("videoQpFloor", v)
    var carDensity: Int get() = int("carDensity", 160); set(v) = set("carDensity", v)
    var encoderSavePower: Boolean get() = bool("encoderSavePower", false); set(v) {
        synchronized(videoProfileLock) {
            if (v != encoderSavePower) sp.edit().putBoolean("encoderSavePower", v)
                .remove("savedVideoProfile").putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
        }
    }
    private fun setVideo(key: String, value: Int) = synchronized(videoProfileLock) {
        if (sp.contains(key) && int(key, 0) == value) return@synchronized
        sp.edit().putInt(key, value).remove("savedVideoProfile")
            .putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
    }
    val videoSettingsKey: String get() = listOf(videoSize, videoWidth, videoHeight, videoFps,
        videoMinFps, videoBitrate, videoQpFloor, encoderSavePower).joinToString(":")
    val videoProfileGeneration: Int get() = int("videoProfileGeneration", 0)
    val savedVideoProfile: VideoProfile? get() = VideoProfile.decode(str("savedVideoProfile", ""))

    fun saveVideoProfile(profile: VideoProfile, generation: Int) = synchronized(videoProfileLock) {
        if (generation != videoProfileGeneration || profile.settingsKey != videoSettingsKey) return@synchronized
        set("savedVideoProfile", profile.encode())
    }

    fun clearVideoProfile() = synchronized(videoProfileLock) {
        sp.edit().remove("savedVideoProfile")
            .putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
    }
    var carSizedApps: Boolean get() = bool("carSizedApps", false); set(v) = set("carSizedApps", v)
    var muteWhileProjecting: Boolean get() = bool("muteWhileProjecting", true); set(v) = set("muteWhileProjecting", v)
    var soundOverBluetooth: Boolean get() = bool("soundOverBluetooth", false); set(v) = set("soundOverBluetooth", v)
    var hideChatAudio: Boolean get() = bool("hideChatAudio", true); set(v) = set("hideChatAudio", v)

    var aaBluetooth: Boolean get() = bool("aaBluetooth", false); set(v) = set("aaBluetooth", v)
    var aaPackage: String get() = str("aaPackage", "com.google.android.projection.gearhead"); set(v) = set("aaPackage", v)
    var aaServerPackage: String get() = str("aaServerPackage", ""); set(v) = set("aaServerPackage", v)
    var aaAutoStart: Boolean get() = bool("aaAutoStart", false); set(v) = set("aaAutoStart", v)
    var aaShowReturnButton: Boolean get() = bool("aaShowReturnButton", true); set(v) = set("aaShowReturnButton", v)
    var aaCorner: Int get() = int("aaCorner", 0); set(v) = set("aaCorner", v)
    var carBackground: String get() = str("carBackground", "deep"); set(v) = set("carBackground", v)
    var carAccent: Int get() = int("carAccent", 0xFF5FE3C0.toInt()); set(v) = set("carAccent", v)
    var carTiles: String get() = str("carTiles", "music,videos,youtube,maps,browser,apps"); set(v) = set("carTiles", v)
    var carClock24: Boolean get() = bool("carClock24", true); set(v) = set("carClock24", v)
    var carShowLink: Boolean get() = bool("carShowLink", false); set(v) = set("carShowLink", v)
    var carSongInfo: Boolean get() = bool("carSongInfo", true); set(v) = set("carSongInfo", v)

    var language: String get() = str("language", ""); set(v) = set("language", v)
    var modernPhoneUi: Boolean get() = bool("modernPhoneUi", false); set(v) = set("modernPhoneUi", v)
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
    var carJoinTimeoutSeconds: Int
        get() = JoinWatch.timeoutSeconds(int("carJoinTimeoutSeconds", JoinWatch.DEFAULT_TIMEOUT_SECONDS))
        set(v) = set("carJoinTimeoutSeconds", JoinWatch.timeoutSeconds(v))

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
    var gearReverse: Int get() = int("gearReverse", 1); set(v) = set("gearReverse", v)
    var gearPark: Int get() = int("gearPark", 0); set(v) = set("gearPark", v)
    var gearReverseSound: Int get() = int("gearReverseSound", 0); set(v) = set("gearReverseSound", v)
    var gearParkSound: Int get() = int("gearParkSound", 0); set(v) = set("gearParkSound", v)
    var gearReverseLevel: Int get() = int("gearReverseLevel", 20); set(v) = set("gearReverseLevel", v)
    var gearParkLevel: Int get() = int("gearParkLevel", 20); set(v) = set("gearParkLevel", v)

    var openOnConnect: String get() = str("openOnConnect", ""); set(v) = set("openOnConnect", v)
    var openOnConnectSecond: String get() = str("openOnConnectSecond", ""); set(v) = set("openOnConnectSecond", v)

    var weatherCelsius: Float get() = flt("weatherCelsius", 0f); set(v) = set("weatherCelsius", v)
    var weatherCode: Int get() = int("weatherCode", -1); set(v) = set("weatherCode", v)
    var weatherAt: Long get() = lng("weatherAt", 0L); set(v) = set("weatherAt", v)

    var supercastToken: String get() = str("supercastToken", ""); set(v) = set("supercastToken", v)
    var supercastOn: Boolean get() = bool("supercastOn", false); set(v) = set("supercastOn", v)
    var supercastScreenOff: Boolean get() = bool("supercastScreenOff", true); set(v) = set("supercastScreenOff", v)
    var supercastApps: String get() = str("supercastApps", ""); set(v) = set("supercastApps", v)
    var supercastWay: Int get() = int("supercastWay", 0); set(v) = set("supercastWay", v)
    var supercastDockHidden: Boolean get() = bool("supercastDockHidden", false); set(v) = set("supercastDockHidden", v)
    var supercastSplit: Float get() = flt("supercastSplit", 0.5f); set(v) = set("supercastSplit", v)
}

// Settings and the service each own a Prefs instance, but must invalidate/save atomically.
private val videoProfileLock = Any()
