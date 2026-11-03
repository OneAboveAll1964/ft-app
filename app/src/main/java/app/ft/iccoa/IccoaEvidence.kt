package app.ft.iccoa

/** Observations only: an advertised UUID or name does not establish protocol compatibility. */
object IccoaEvidence {
    // Receiver APK analysis, unconfirmed on Toyota:
    // https://github.com/PiggyBackBayonet/MiAuto/blob/main/docs/APK_ANALYSIS_ZHL-v1.5.3.md
    const val REPORTED_SERVICE = "2abcc850-9935-4f8a-ba84-123456789100"
    const val ADVERTISED_SERVICE = "0000fcfb-0000-1000-8000-00805f9b34fb"

    // A receiver-stable observation in real XA60 BLE serviceData, with the
    // 00000001 frame changing while the 00000002 frame stays constant over
    // multiple randomized BLE addresses. This is only a discovery hint; it
    // is NEVER an authenticated identity or substitute for pinned OEM ECDSA.
    const val RECEIVER_TOKEN_SERVICE_DATA = "00000002-0000-1000-8000-00805f9b34fb"

    fun receiverToken(data: ByteArray?): String {
        if (data == null || data.size != 16 || data.all { it == 0.toByte() }) return ""
        val digits = "0123456789abcdef"
        return buildString(data.size * 2) {
            for (b in data) {
                val value = b.toInt() and 255
                append(digits[value ushr 4])
                append(digits[value and 15])
            }
        }
    }


    fun hint(name: String?, services: Collection<String>): Boolean =
        services.any { it.equals(REPORTED_SERVICE, ignoreCase = true) || it.equals(ADVERTISED_SERVICE, ignoreCase = true) } ||
            name?.let { it.contains("iccoa", ignoreCase = true) || it.contains("carlink", ignoreCase = true) } == true
}

/** Keep a bounded set of distinct advertisement payloads, including changes from the same device. */
internal class AdvertisementSamples(private val perDevice: Int = 8, private val maxDevices: Int = 128) {
    private val samples = LinkedHashMap<String, MutableSet<String>>()

    fun record(address: String, payload: String): Boolean {
        val seen = samples[address] ?: run {
            if (samples.size >= maxDevices) return false
            linkedSetOf<String>().also { samples[address] = it }
        }
        if (seen.size >= perDevice) return false
        return seen.add(payload)
    }
}
