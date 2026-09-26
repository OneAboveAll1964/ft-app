package app.ft.core

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CarAudioBusTest {
    private val realTime = 192_000.0

    @After
    fun tearDown() {
        CarAudioBus.sink = null
        Thread.sleep(60)
    }

    private class Source(val lane: Int, val chunk: Int, val startMs: Long, val stopMs: Long)

    private fun run(seconds: Long, sources: List<Source>): Pair<Double, Long> {
        val sent = AtomicLong(0)
        CarAudioBus.sink = { pcm -> sent.addAndGet(pcm.size.toLong()) }
        val began = System.nanoTime()
        val feeders = sources.map { src ->
            Thread {
                val silence = ByteArray(src.chunk)
                val periodNs = src.chunk.toLong() * 1_000_000_000L / realTime.toLong()
                var due = began + src.startMs * 1_000_000L
                while (true) {
                    val elapsed = (System.nanoTime() - began) / 1_000_000L
                    if (elapsed >= src.stopMs) break
                    val wait = due - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                    if ((System.nanoTime() - began) / 1_000_000L >= src.startMs) {
                        CarAudioBus.write(src.lane, silence)
                    }
                    due += periodNs
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(seconds * 1000)
        feeders.forEach { it.interrupt() }
        val elapsedS = (System.nanoTime() - began) / 1_000_000_000.0
        CarAudioBus.sink = null
        val rate = sent.get() / elapsedS
        println("RATE ${sources.size} source(s): ${rate.toInt()} B/s = ${"%.1f".format(rate / realTime * 100)}% of real time")
        return rate to sent.get()
    }

    @Test
    fun onePhoneSourceStaysAtRealTime() {
        val (rate, _) = run(4, listOf(Source(CarAudioBus.LANE_PHONE, 16384, 0, 4000)))
        assertTrue("one source produced $rate B/s, over real time", rate <= realTime * 1.05)
        assertTrue("one source produced only $rate B/s", rate >= realTime * 0.80)
    }

    @Test
    fun guidanceOnTopOfMediaDoesNotOutrunTheCar() {
        val (rate, _) = run(
            5,
            listOf(
                Source(CarAudioBus.LANE_PHONE, 16384, 0, 5000),
                Source(CarAudioBus.LANE_SPEECH, 3840, 1000, 4000)
            )
        )
        assertTrue("two sources produced $rate B/s, over real time", rate <= realTime * 1.05)
        assertTrue("two sources produced only $rate B/s", rate >= realTime * 0.80)
    }

    @Test
    fun threeSourcesTogetherStillMatchTheCar() {
        val (rate, _) = run(
            5,
            listOf(
                Source(CarAudioBus.LANE_PHONE, 16384, 0, 5000),
                Source(CarAudioBus.LANE_MEDIA, 8192, 500, 4500),
                Source(CarAudioBus.LANE_SPEECH, 3840, 1000, 4000)
            )
        )
        assertTrue("three sources produced $rate B/s, over real time", rate <= realTime * 1.05)
        assertTrue("three sources produced only $rate B/s", rate >= realTime * 0.80)
    }

    @Test
    fun aSourceThatFloodsIsHeldToRealTime() {
        val (rate, _) = run(
            4,
            listOf(
                Source(CarAudioBus.LANE_PHONE, 16384, 0, 4000),
                Source(CarAudioBus.LANE_SPEECH, 15360, 500, 3500)
            )
        )
        assertTrue("a flooding source pushed $rate B/s to the car", rate <= realTime * 1.05)
    }

    @Test
    fun silenceProducesNothing() {
        val (rate, total) = run(2, emptyList())
        assertTrue("sent $total bytes with no source at all", total == 0L)
        assertTrue(rate == 0.0)
    }
}
