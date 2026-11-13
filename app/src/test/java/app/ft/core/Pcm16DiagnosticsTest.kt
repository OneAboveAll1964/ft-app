package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class Pcm16DiagnosticsTest {
    private fun pcm(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { i, value ->
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    @Test fun separatesConstantOffsetFromAlternatingSignal() {
        val logs = mutableListOf<String>()
        val diag = Pcm16Diagnostics(logs::add) { 0L }
        diag.reset()
        diag.record(pcm(1000, 1000))
        diag.finish()
        assertTrue(logs.single().contains("peak=1000 rms=1000 acRms=0 mean=1000 zeros=0 clipped=0"))
        diag.reset()
        diag.record(pcm(-1000, 1000))
        diag.finish()
        assertTrue(logs.last().contains("peak=1000 rms=1000 acRms=1000 mean=0 zeros=0 clipped=0"))
    }

    @Test fun readsSignedLittleEndianExtremesWithoutOverflowOrChangingInput() {
        val logs = mutableListOf<String>()
        val diag = Pcm16Diagnostics(logs::add) { 0L }
        val input = pcm(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(), 0, 256)
        val original = input.copyOf()
        diag.reset()
        diag.record(input)
        diag.finish()
        assertEquals(32768, diag.peak)
        assertTrue(logs.single().contains("mean=63 zeros=1 clipped=2"))
        assertArrayEquals(original, input)
    }

    @Test fun windowsFollowAudioSamplesRatherThanPacketBoundariesAndFlushTheTail() {
        val logs = mutableListOf<String>()
        val diag = Pcm16Diagnostics(logs::add) { 0L }
        diag.reset()
        diag.record(ByteArray(31_998))
        assertTrue(logs.isEmpty())
        diag.record(pcm(100, 200))
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("audioMs=1000 samples=16000 peak=100"))
        diag.finish()
        assertTrue(logs[1].contains("samples=1 peak=200 rms=200 acRms=0 mean=200"))
    }

    @Test fun reportsArrivalDelayGapsTailAndResetsBetweenRequests() {
        var now = 0L
        val diag = Pcm16Diagnostics({}) { now }
        diag.reset()
        now = 300_000_000
        diag.record(ByteArray(1280))
        now = 350_000_000
        diag.record(ByteArray(1280))
        now = 420_000_000
        assertEquals("audioMs=80 elapsedMs=420 firstPacketMs=300 maxGapMs=50 tailMs=70", diag.finish())
        diag.reset()
        now += 10_000_000
        diag.record(byteArrayOf(1))
        diag.record(byteArrayOf())
        assertEquals("audioMs=0 elapsedMs=10 firstPacketMs=-1 maxGapMs=0 tailMs=-1", diag.finish())
        assertEquals(0, diag.peak)
    }
}
