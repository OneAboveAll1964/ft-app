package app.ft.core

import org.json.JSONObject
import java.util.Base64

object Backup {
    const val VERSION = 1
    const val SCHEME = "ft"
    const val LOOK_HOST = "look"

    private val SKIP = setOf("carBtAddress", "weatherAt", "weatherCelsius", "weatherCode", "lastLatitude", "lastLongitude")

    val LOOK_KEYS = listOf(
        "carBackground", "carAccent", "carTiles", "carClock24", "carShowLink", "carSongInfo",
        "carInfoRight", "carColumns", "carShowClock", "carShowMusic", "carShowAa", "carShowCarHome",
        "carWidgets", "weatherFahrenheit", "speedMph", "carDayNight", "carNightBackground", "carNightAccent",
        "aaCorner"
    )

    fun everything(prefs: Prefs): String = write(prefs.all.filterKeys { it !in SKIP })

    fun look(prefs: Prefs): String = write(prefs.all.filterKeys { it in LOOK_KEYS })

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
        body.keys().forEach { key -> body.get(key)?.let { out[key] = it } }
        return out
    }

    fun restore(prefs: Prefs, text: String, only: Collection<String>? = null): Int {
        val parsed = parse(text) ?: return 0
        val before = prefs.all
        var written = 0
        parsed.forEach { (key, value) ->
            if (key in SKIP) return@forEach
            if (only != null && key !in only) return@forEach
            val current = before[key]
            val typed: Any? = when (current) {
                is Boolean -> (value as? Boolean) ?: (value as? String)?.toBooleanStrictOrNull()
                is Int -> (value as? Number)?.toInt()
                is Long -> (value as? Number)?.toLong()
                is Float -> (value as? Number)?.toFloat()
                is String -> value as? String
                null -> when (value) {
                    is Boolean, is String -> value
                    is Int -> value
                    is Long -> value
                    is Double -> value.toFloat()
                    else -> null
                }
                else -> null
            }
            if (typed != null) {
                prefs.put(key, typed)
                written++
            }
        }
        return written
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
