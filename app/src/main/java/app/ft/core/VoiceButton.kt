package app.ft.core

enum class VoiceAssistant(val stored: Int) {
    DEFAULT(0), ALICE(1);

    companion object {
        fun fromStored(value: Int) = entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/** One press can arrive as both a hard key and a CarLife VR request. */
internal class VoiceButton(
    private val selected: () -> VoiceAssistant,
    private val aaVisible: () -> Boolean,
    private val sendAa: () -> Unit,
    private val openPhone: (VoiceAssistant) -> Unit,
    private val now: () -> Long
) {
    private var lastPress: Long? = null

    @Synchronized fun press() {
        val time = now()
        if (lastPress?.let { time - it in 0 until 500 } == true) return
        lastPress = time
        val assistant = selected()
        if (assistant == VoiceAssistant.DEFAULT && aaVisible()) sendAa()
        else openPhone(assistant)
    }
}
