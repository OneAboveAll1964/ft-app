package app.ft.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ft", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun set(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun set(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun set(key: String, v: String) = sp.edit().putString(key, v).apply()

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

    var forceWidth: Int get() = int("forceWidth", 0); set(v) = set("forceWidth", v)
    var forceHeight: Int get() = int("forceHeight", 0); set(v) = set("forceHeight", v)
    var forceFps: Int get() = int("forceFps", 0); set(v) = set("forceFps", v)
    var minFps: Int get() = int("minFps", 0); set(v) = set("minFps", v)
    var maxBitrate: Int get() = int("maxBitrate", 3_000_000); set(v) = set("maxBitrate", v)
    var carDensity: Int get() = int("carDensity", 160); set(v) = set("carDensity", v)
    var carSizedApps: Boolean get() = bool("carSizedApps", false); set(v) = set("carSizedApps", v)
    var muteWhileProjecting: Boolean get() = bool("muteWhileProjecting", true); set(v) = set("muteWhileProjecting", v)
    var soundOverBluetooth: Boolean get() = bool("soundOverBluetooth", false); set(v) = set("soundOverBluetooth", v)
    var hideChatAudio: Boolean get() = bool("hideChatAudio", true); set(v) = set("hideChatAudio", v)

    var autoStartWifi: Boolean get() = bool("autoStartWifi", false); set(v) = set("autoStartWifi", v)
    var autoStartAa: Boolean get() = bool("autoStartAa", false); set(v) = set("autoStartAa", v)
    var aaBluetooth: Boolean get() = bool("aaBluetooth", false); set(v) = set("aaBluetooth", v)
    var aaPackage: String get() = str("aaPackage", "com.google.android.projection.gearhead"); set(v) = set("aaPackage", v)
    var aaAutoStart: Boolean get() = bool("aaAutoStart", false); set(v) = set("aaAutoStart", v)
    var aaCorner: Int get() = int("aaCorner", 0); set(v) = set("aaCorner", v)
    var aaServerOn: Boolean get() = bool("aaServerOn", false); set(v) = set("aaServerOn", v)
    var carBackground: String get() = str("carBackground", "deep"); set(v) = set("carBackground", v)
    var carAccent: Int get() = int("carAccent", 0xFF5FE3C0.toInt()); set(v) = set("carAccent", v)
    var carTiles: String get() = str("carTiles", "music,videos,youtube,maps,browser,apps"); set(v) = set("carTiles", v)
    var carClock24: Boolean get() = bool("carClock24", true); set(v) = set("carClock24", v)
    var carShowLink: Boolean get() = bool("carShowLink", false); set(v) = set("carShowLink", v)
    var carSongInfo: Boolean get() = bool("carSongInfo", true); set(v) = set("carSongInfo", v)

    var carName: String get() = str("carName", "FT"); set(v) = set("carName", v)
    var autoConnect: Boolean get() = bool("autoConnect", false); set(v) = set("autoConnect", v)
    var carP2pName: String get() = str("carP2pName", ""); set(v) = set("carP2pName", v)
    var carBtName: String get() = str("carBtName", ""); set(v) = set("carBtName", v)
    var carBtAddress: String get() = str("carBtAddress", ""); set(v) = set("carBtAddress", v)
    var swapTrackKeys: Boolean get() = bool("swapTrackKeys", false); set(v) = set("swapTrackKeys", v)
    var blendGuidance: Boolean get() = bool("blendGuidance", false); set(v) = set("blendGuidance", v)
    var linkMode: Int get() = int("linkMode", 0); set(v) = set("linkMode", v)
    var carWpsPin: String get() = str("carWpsPin", ""); set(v) = set("carWpsPin", v)

    var mapsPackage: String get() = str("pkgMaps", "com.google.android.apps.maps"); set(v) = set("pkgMaps", v)
    var musicPackage: String get() = str("pkgMusic", "com.spotify.music"); set(v) = set("pkgMusic", v)
    var videoPackage: String get() = str("pkgVideo", "com.teamsmart.videomanager.tv"); set(v) = set("pkgVideo", v)
    var phonePackage: String get() = str("pkgPhone", "com.samsung.android.dialer"); set(v) = set("pkgPhone", v)
}
