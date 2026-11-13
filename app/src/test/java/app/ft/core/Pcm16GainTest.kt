package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class Pcm16GainTest {
    private fun pcm(vararg values: Int) = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun defaultKeepsTheOriginalBytesIncludingSilenceAndBothLimits() {
        val gain = Pcm16Gain()
        val input = pcm(0, -32768, 32767, -1, 256)
        assertSame(input, gain.apply(input))
        assertEquals(0L, gain.clippedSamples)
    }

    @Test fun signedLittleEndianGainSaturatesWithoutWrapOrChangingInput() {
        val input = pcm(-32768, -16385, -16384, -256, 0, 256, 16383, 16384, 32767)
        val original = input.copyOf()
        val gain = Pcm16Gain(2)
        assertArrayEquals(pcm(-32768, -32768, -32768, -512, 0, 512, 32766, 32767, 32767), gain.apply(input))
        assertArrayEquals(original, input)
        assertEquals(4L, gain.clippedSamples)
        gain.apply(pcm(20000))
        assertEquals(5L, gain.clippedSamples)
    }

    @Test fun clampsConfigurationAndHandlesMaximumGain() {
        assertEquals(1, Pcm16Gain(Int.MIN_VALUE).multiplier)
        val gain = Pcm16Gain(Int.MAX_VALUE)
        assertEquals(16, gain.multiplier)
        assertArrayEquals(pcm(-32768, 32767, -32768, 32752), gain.apply(pcm(-32768, 32767, -2048, 2047)))
        assertEquals(2L, gain.clippedSamples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteSamples() { Pcm16Gain(2).apply(byteArrayOf(1)) }
}
