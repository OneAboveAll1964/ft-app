package app.ft.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Scalar diagnostics only: never retains or changes the PCM. Call under the owner's lock. */
internal class Pcm16Diagnostics(
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var started = 0L
    private var firstPacket: Long? = null
    private var lastPacket: Long? = null
    private var maxGap = 0L
    private var samples = 0L
    private var windowSamples = 0
    private var windowSum = 0L
    private var windowSquares = 0L
    private var windowZeros = 0
    private var windowClipped = 0
    private var windowPeak = 0
    var peak = 0
        private set

    fun reset() {
        started = nanoTime()
        firstPacket = null
        lastPacket = null
        maxGap = 0
        samples = 0
        peak = 0
        clearWindow()
    }

    fun record(pcm: ByteArray) {
        if (pcm.isEmpty() || pcm.size % 2 != 0) return
        val now = nanoTime()
        if (firstPacket == null) firstPacket = now
        lastPacket?.let { maxGap = maxOf(maxGap, now - it) }
        lastPacket = now
        for (i in pcm.indices step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val magnitude = abs(sample)
            peak = maxOf(peak, magnitude)
            windowPeak = maxOf(windowPeak, magnitude)
            windowSum += sample
            windowSquares += sample.toLong() * sample
            if (sample == 0) windowZeros++
            if (sample == Short.MIN_VALUE.toInt() || sample == Short.MAX_VALUE.toInt()) windowClipped++
            samples++
            windowSamples++
            if (windowSamples == RATE) reportWindow()
        }
    }

    fun finish(): String {
        if (windowSamples > 0) reportWindow()
        val now = nanoTime()
        val firstMs = firstPacket?.let { (it - started) / 1_000_000 } ?: -1
        val tailMs = lastPacket?.let { (now - it) / 1_000_000 } ?: -1
        return "audioMs=${samples * 1000 / RATE} elapsedMs=${(now - started) / 1_000_000} " +
            "firstPacketMs=$firstMs maxGapMs=${maxGap / 1_000_000} tailMs=$tailMs"
    }

    private fun reportWindow() {
        val mean = windowSum.toDouble() / windowSamples
        val power = windowSquares.toDouble() / windowSamples
        val rms = sqrt(power).toInt()
        val acRms = sqrt(maxOf(0.0, power - mean * mean)).toInt()
        log("PCM window: audioMs=${samples * 1000 / RATE} samples=$windowSamples " +
            "peak=$windowPeak rms=$rms acRms=$acRms mean=${mean.toInt()} " +
            "zeros=$windowZeros clipped=$windowClipped")
        clearWindow()
    }

    private fun clearWindow() {
        windowSamples = 0
        windowSum = 0
        windowSquares = 0
        windowZeros = 0
        windowClipped = 0
        windowPeak = 0
    }

    private companion object { const val RATE = 16000 }
}
