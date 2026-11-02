package app.ft.iccoa

/** Only fields whose meaning is established by reference 7.62. Never logs string/blob values. */
internal object IccoaCarConfig {
    private val modes = listOf("normal cast", "data transfer", "app fullscreen", "app window",
        "card cast", "file transfer", "multi-screen", "multi-device", "mirror window",
        "launcher window", "share network")

    fun describe(body: ByteArray): String {
        // Config is diagnostic input, not permission to change Bluetooth routing or capabilities.
        require(body.size <= 16_384) { "Oversized car config" }
        val fields = IccoaControl.Fields(body)
        val result = mutableListOf<String>()
        val workModes = fields.number(18, -1)
        if (workModes >= 0) {
            val names = modes.filterIndexed { bit, _ -> workModes and (1L shl bit) != 0L }.toMutableList()
            val unknown = workModes and 2047L.inv()
            if (unknown != 0L) names += "unknown 0x${unknown.toString(16)}"
            result += "workModes=$workModes (${names.joinToString().ifEmpty { "none" }})"
        }
        for ((field, name) in listOf(19 to "poi", 20 to "rotation", 21 to "home",
            22 to "back", 23 to "hevc", 24 to "dpi")) {
            val value = fields.number(field, -1)
            if (value >= 0) result += "$name=$value"
        }
        // Shape preserves unknown field IDs and lengths for a later audit without exposing IDs/MACs.
        result += "shape=${fields.shape().take(512)}"
        return result.joinToString("; ")
    }
}
