package app.ft.core

import org.json.JSONObject
import java.util.Base64

object Backup {
    const val VERSION = 1
    const val SCHEME = "ft"
    const val LOOK_HOST = "look"

    private val integerKeys = setOf(
        "cmdPort", "videoPort", "mediaPort", "ttsPort", "vrPort", "touchPort", "aaPort", "aaSelfPort",
        "aaWidth", "aaHeight", "aaFps", "aaDensity", "pictureSize", "videoWidth", "videoHeight",
        "videoFps", "videoMinFps", "pictureBitrate", "videoQpFloor", "carDensity", "aaCorner",
        "carAccent", "themeMode", "iccoaMicGain", "directions", "linkMode", "carJoinTimeoutSeconds",
        "carColumns", "carNightAccent", "voiceAssistant", "wheelLongNext", "wheelLongPrev", "gearReverse",
        "gearPark", "gearReverseSound", "gearParkSound", "gearReverseLevel", "gearParkLevel", "supercastWay"
    )
    private val booleanKeys = setOf(
        "aaDensityBoost167", "aaPhoneScreenOff", "aaControlsLeft", "aaClusterNavigation", "aaMatchCar",
        "encoderSavePower", "carSizedApps", "muteWhileProjecting", "soundOverBluetooth", "hideChatAudio",
        "aaBluetooth", "aaAutoStart", "aaPrepareBeforeConnect", "aaShowReturnButton", "carClock24",
        "carShowLink", "carSongInfo", "modernPhoneUi", "autoConnect", "carBtReconnect",
        "showOtherDevicesInLogs", "vehicleCoordinateLogging", "swapTrackKeys", "carP2pAutoRole",
        "carInfoRight", "carShowClock", "carShowMusic", "carShowAa", "carShowCarHome",
        "weatherFahrenheit", "speedMph", "carDayNight", "supercastOn", "supercastScreenOff", "supercastDockHidden"
    )
    private val stringKeys = setOf(
        "aaPackage", "aaServerPackage", "carBackground", "carTiles", "language", "carName", "carProtocol",
        "carP2pName", "carBtName", "pkgMaps", "pkgMusic", "pkgVideo", "pkgPhone", "carWidgets",
        "carNightBackground", "openOnConnect", "openOnConnectSecond", "supercastApps"
    )

    val LOOK_KEYS = listOf(
        "carBackground", "carAccent", "carTiles", "carClock24", "carShowLink", "carSongInfo",
        "carInfoRight", "carColumns", "carShowClock", "carShowMusic", "carShowAa", "carShowCarHome",
        "carWidgets", "weatherFahrenheit", "speedMph", "carDayNight", "carNightBackground", "carNightAccent",
        "aaCorner"
    )

    fun everything(prefs: Prefs): String = write(portableValues(prefs.all))

    fun look(prefs: Prefs): String = write(portableValues(prefs.all, LOOK_KEYS))

    internal fun write(values: Map<String, Any?>): String {
        val body = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            when (value) {
                is Boolean -> body.put(key, value)
                is Int -> body.put(key, value)
                is Long -> body.put(key, value)
                is Float -> body.put(key, value.toDouble())
                is String -> body.put(key, value)
            }
        }
        return JSONObject().put("ft", VERSION).put("values", body).toString()
    }

    internal fun parse(text: String): Map<String, Any>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("ft", 0) !in 1..VERSION) return null
        val body = root.optJSONObject("values") ?: return null
        val out = linkedMapOf<String, Any>()
        body.keys().forEach { key -> out[key] = body.get(key) }
        return out
    }

    fun restore(prefs: Prefs, text: String, only: Collection<String>? = null): Int {
        val parsed = parse(text) ?: return 0
        val values = portableValues(parsed, only)
        prefs.restoreBackup(values)
        return values.size
    }

    internal fun portableValues(values: Map<String, *>, only: Collection<String>? = null): Map<String, Any> = buildMap {
        values.forEach { (key, value) ->
            if (only != null && key !in only) return@forEach
            val typed = when {
                key in booleanKeys -> (value as? Boolean) ?: (value as? String)?.toBooleanStrictOrNull()
                key in integerKeys && value is Number -> runCatching { value.toString().toBigDecimal().intValueExact() }.getOrNull()
                key in stringKeys -> value as? String
                key == "supercastSplit" -> (value as? Number)?.toFloat()?.takeIf(Float::isFinite)
                else -> null
            }
            if (typed != null) put(key, typed)
        }
    }

    internal fun encode(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    internal fun decode(code: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(code)) }.getOrNull()

    fun lookCode(prefs: Prefs): String = encode(look(prefs))

    fun lookLink(prefs: Prefs): String = "$SCHEME://$LOOK_HOST/${lookCode(prefs)}"

    fun readLookCode(prefs: Prefs, raw: String): Int {
        val code = raw.trim()
            .removePrefix("$SCHEME://$LOOK_HOST/")
            .removePrefix("$SCHEME://$LOOK_HOST")
            .trim('/', ' ', '\n')
        if (code.isEmpty()) return 0
        val text = decode(code) ?: return 0
        return restore(prefs, text, LOOK_KEYS)
    }
}
