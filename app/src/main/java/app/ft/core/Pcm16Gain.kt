package app.ft.core

/** Fixed gain for signed little-endian PCM16. Used under the microphone owner's lock. */
internal class Pcm16Gain(value: Int = 1) {
    val multiplier = clamp(value)
    var clippedSamples = 0L
        private set

    fun apply(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        if (multiplier == 1) return pcm
        return ByteArray(pcm.size).also { output ->
            for (i in pcm.indices step 2) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                val amplified = sample * multiplier
                val limited = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (limited != amplified) clippedSamples++
                output[i] = limited.toByte()
                output[i + 1] = (limited shr 8).toByte()
            }
        }
    }

    companion object {
        const val MIN = 1
        const val MAX = 16
        fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
    }
}
