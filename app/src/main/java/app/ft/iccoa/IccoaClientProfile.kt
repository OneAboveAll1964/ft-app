package app.ft.iccoa

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

/** Compatibility identity for UCAR receivers, independent of the car or actual phone vendor. */
internal data class IccoaClientProfile(val id: String, val mac: String) {
    fun clientInfo(pin: String, model: String, channel: Int): ByteArray = JSONObject()
        .put("id", id).put("model", model).put("name", "F-Tap")
        .put("pinCodeOrAuthentication", pin).put("band", 1).put("mac", mac)
        .put("type", 1002).put("manufactures", "Xiaomi").put("channel", channel)
        .toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun load(context: Context): IccoaClientProfile {
            // Separate from settings backups: an installation must keep its own identity.
            val prefs = context.getSharedPreferences("iccoa-client", Context.MODE_PRIVATE)
            val savedId = prefs.getString("id", null)
            val savedMac = prefs.getString("mac", null)
            if (savedId != null && savedMac != null && valid(savedId, savedMac)) {
                return IccoaClientProfile(savedId, savedMac)
            }
            val profile = generate()
            check(prefs.edit().putString("id", profile.id).putString("mac", profile.mac).commit()) {
                "Could not save ICCOA client identity"
            }
            return profile
        }

        internal fun generate(): IccoaClientProfile {
            val random = SecureRandom()
            val bytes = ByteArray(6).also(random::nextBytes)
            // A synthetic, locally administered unicast address, as in the reference.
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 2).toByte()
            val mac = bytes.joinToString(":") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            val id = "%06x".format(Locale.ROOT, random.nextInt(0x1000000))
            return IccoaClientProfile(id, mac)
        }

        internal fun valid(id: String, mac: String): Boolean =
            id.matches(Regex("[0-9a-f]{6}")) && mac.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
                (mac.take(2).toInt(16) and 3) == 2

        @Suppress("DEPRECATION")
        fun channel(context: Context): Int = runCatching {
            wifiChannel(context.getSystemService(WifiManager::class.java).connectionInfo.frequency)
        }.getOrDefault(1)

        internal fun wifiChannel(frequency: Int): Int = when {
            frequency in 2412..2472 && (frequency - 2412) % 5 == 0 -> (frequency - 2412) / 5 + 1
            frequency == 2484 -> 14
            frequency in 5180..5825 && (frequency - 5180) % 5 == 0 -> (frequency - 5180) / 5 + 36
            else -> 1
        }
    }
}
