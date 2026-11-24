package app.ft.core

import org.json.JSONObject
import java.util.Base64

object Backup {
    const val VERSION = 2
    const val SCHEME = "ft"
    const val LOOK_HOST = "look"
    const val TYPE_ALL_CARS = "all_cars"

    private val integerKeys = setOf(
        "cmdPort", "videoPort", "mediaPort", "ttsPort", "vrPort", "touchPort", "aaPort", "aaSelfPort",
        "aaWidth", "aaHeight", "aaFps", "aaDensity", "pictureSize", "videoWidth", "videoHeight",
        "videoFps", "videoMinFps", "pictureBitrate", "videoQpFloor", "carDensity", "aaCorner",
        "carAccent", "themeMode", "iccoaMicGain", "directions", "linkMode", "carJoinTimeoutSeconds",
        "carColumns", "carNightAccent", "voiceAssistant", "wheelLongNext", "wheelLongPrev", "gearReverse",
        "gearPark", "gearReverseSound", "gearParkSound", "gearReverseLevel", "gearParkLevel", "supercastWay",
        "autoBackupInterval"
    )
    private val booleanKeys = setOf(
        "aaDensityBoost167", "aaPhoneScreenOff", "aaControlsLeft", "aaClusterNavigation", Prefs.AA_DRIVING_RESTRICTIONS, "aaMatchCar",
        "encoderSavePower", "carSizedApps", "muteWhileProjecting", "soundOverBluetooth", "hideChatAudio",
        "aaBluetooth", "aaAutoStart", "aaOnly", "aaPrepareBeforeConnect", "aaShowReturnButton", "carClock24",
        "carShowLink", "carSongInfo", "modernPhoneUi", "autoConnect", "carBtReconnect",
        "showOtherDevicesInLogs", "vehicleCoordinateLogging", "swapTrackKeys", "carP2pAutoRole", "carP2pPhoneGroup",
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

    fun everythingAllCars(profiles: CarProfiles, activePrefs: Prefs): String {
        val root = JSONObject()
        root.put("ft", VERSION)
        root.put("type", TYPE_ALL_CARS)
        root.put("timestamp", System.currentTimeMillis())
        root.put("activeId", profiles.state.value.activeId)

        val globalValues = portableValues(activePrefs.global.all.filterKeys { it !in Prefs.PROFILE_KEYS })
        val globalObj = JSONObject()
        globalValues.toSortedMap().forEach { (k, v) -> putJson(globalObj, k, v) }
        root.put("global", globalObj)

        val profilesArray = org.json.JSONArray()
        profiles.state.value.profiles.forEach { profile ->
            val pPrefs = profiles.prefsFor(profile.id) ?: return@forEach
            val pValues = portableValues(pPrefs.all.filterKeys { it in Prefs.PROFILE_KEYS })
            val pObj = JSONObject()
            pObj.put("id", profile.id)
            pObj.put("name", profile.name)
            pObj.put("model", profile.model.name)
            pObj.put("protocol", profile.protocol.name)
            pObj.put("linkMode", profile.linkMode)
            pObj.put("autoConnect", profile.autoConnect)

            val valuesObj = JSONObject()
            pValues.toSortedMap().forEach { (k, v) -> putJson(valuesObj, k, v) }
            pObj.put("values", valuesObj)

            profilesArray.put(pObj)
        }
        root.put("profiles", profilesArray)
        return root.toString()
    }

    fun isAllCars(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return root.optString("type") == TYPE_ALL_CARS || root.has("profiles")
    }

    private fun putJson(body: JSONObject, key: String, value: Any?) {
        when (value) {
            is Boolean -> body.put(key, value)
            is Int -> body.put(key, value)
            is Long -> body.put(key, value)
            is Float -> body.put(key, value.toDouble())
            is String -> body.put(key, value)
        }
    }

    fun look(prefs: Prefs): String = write(portableValues(prefs.all, LOOK_KEYS))

    internal fun write(values: Map<String, Any?>): String {
        val body = JSONObject()
        values.toSortedMap().forEach { (key, value) -> putJson(body, key, value) }
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

    fun restore(prefs: Prefs, text: String, only: Collection<String>? = null, profiles: CarProfiles? = null): Int {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        if (root.optInt("ft", 0) !in 1..VERSION) return 0

        if (root.optString("type") == TYPE_ALL_CARS || root.has("profiles")) {
            return restoreAllCars(prefs, root, profiles, only)
        }

        val parsed = parse(text) ?: return 0
        val values = portableValues(parsed, only)
        prefs.restoreBackup(values)
        return values.size
    }

    private fun restoreAllCars(prefs: Prefs, root: JSONObject, profiles: CarProfiles?, only: Collection<String>? = null): Int {
        var totalRestored = 0
        val globalObj = root.optJSONObject("global")
        if (globalObj != null) {
            val globalValues = linkedMapOf<String, Any>()
            globalObj.keys().forEach { k -> globalValues[k] = globalObj.get(k) }
            val portable = portableValues(globalValues, only)
            prefs.restoreBackup(portable)
            totalRestored += portable.size
        }

        val profilesArray = root.optJSONArray("profiles")
        if (profilesArray != null) {
            for (i in 0 until profilesArray.length()) {
                val pObj = profilesArray.optJSONObject(i) ?: continue
                val id = pObj.optString("id")
                val name = pObj.optString("name", "Car")
                val modelStr = pObj.optString("model", CarModel.OTHER.name)
                val model = runCatching { CarModel.valueOf(modelStr) }.getOrDefault(CarModel.OTHER)
                val protocolStr = pObj.optString("protocol", CarProtocol.CARLIFE.name)
                val protocol = runCatching { CarProtocol.valueOf(protocolStr) }.getOrDefault(CarProtocol.CARLIFE)
                val linkMode = pObj.optInt("linkMode", 0)

                val targetPrefs = if (profiles != null) {
                    val targetId = when {
                        profiles.prefsFor(id) != null -> {
                            profiles.update(id, name, model)
                            id
                        }
                        profiles.state.value.profiles.size == 1 && profiles.state.value.profiles[0].id == CarProfiles.LEGACY_ID -> {
                            profiles.update(CarProfiles.LEGACY_ID, name, model)
                            CarProfiles.LEGACY_ID
                        }
                        else -> {
                            profiles.createWithId(id, name, model, protocol, linkMode) ?: id
                        }
                    }
                    profiles.prefsFor(targetId) ?: prefs
                } else {
                    prefs
                }

                val valuesObj = pObj.optJSONObject("values")
                if (valuesObj != null) {
                    val pValues = linkedMapOf<String, Any>()
                    valuesObj.keys().forEach { k -> pValues[k] = valuesObj.get(k) }
                    val portable = portableValues(pValues, only)
                    targetPrefs.restoreBackup(portable)
                    totalRestored += portable.size
                }
            }
        }
        val activeId = root.optString("activeId", "")
        if (activeId.isNotEmpty() && profiles != null && profiles.prefsFor(activeId) != null) {
            profiles.select(activeId)
        }
        return totalRestored
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
