package app.ft.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class CarAudioBusTest {
    private val got = Collections.synchronizedList(ArrayList<ByteArray>())

    @After
    fun tearDown() {
        CarAudioBus.sink = null
        got.clear()
    }

    private fun listen() {
        CarAudioBus.sink = { pcm -> got.add(pcm) }
    }

    @Test
    fun everythingWrittenReachesTheCar() {
        listen()
        val a = ByteArray(1024) { 1 }
        val b = ByteArray(2048) { 2 }
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, a)
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, b)
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
    }

    @Test
    fun aFloodIsPassedOnRatherThanThrownAway() {
        listen()
        val block = ByteArray(3840)
        repeat(500) { CarAudioBus.write(CarAudioBus.LANE_MEDIA, block) }
        assertEquals("audio was dropped on the way to the car", 500, got.size)
    }

    @Test
    fun guidanceOnItsOwnGoesStraightToTheCar() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_SPEECH, ByteArray(256) { 9 })
        assertEquals(1, got.size)
        assertEquals(256, got[0].size)
    }

    @Test
    fun guidanceOverMusicIsMixedInRatherThanQueuedBehindIt() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, ByteArray(512) { 0 })
        CarAudioBus.write(CarAudioBus.LANE_SPEECH, ByteArray(512) { 0 })
        assertEquals("guidance was sent as its own block", 1, got.size)
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, ByteArray(512) { 0 })
        assertEquals(2, got.size)
        assertEquals("the car was sent more audio than music alone", 512, got[1].size)
    }

    @Test
    fun mixingTwoSourcesAddsThemTogether() {
        listen()
        val quiet = ByteArray(8)
        for (i in 0 until 4) {
            quiet[i * 2] = 0x10
            quiet[i * 2 + 1] = 0x00
        }
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, quiet)
        CarAudioBus.write(CarAudioBus.LANE_SPEECH, quiet)
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, quiet)
        val mixed = got[1]
        val sample = ((mixed[1].toInt() shl 8) or (mixed[0].toInt() and 0xFF))
        assertEquals("two equal sources should sum", 0x20, sample)
    }

    @Test
    fun nothingIsSentWithoutACar() {
        CarAudioBus.sink = null
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, ByteArray(1024))
        assertTrue(got.isEmpty())
    }

    @Test
    fun emptyAudioIsIgnored() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, ByteArray(0))
        assertTrue(got.isEmpty())
    }

    @Test
    fun guidanceIsStretchedToTheCarsOwnFormat() {
        val mono16k = ByteArray(320)
        val out = CarAudioBus.toCarFormat(mono16k, 16000, 1)
        assertEquals("16kHz mono should become 48kHz stereo", mono16k.size * 6, out.size)
    }

    @Test
    fun mediaIsPassedThroughUntouched() {
        val stereo48k = ByteArray(960) { (it % 251).toByte() }
        val out = CarAudioBus.toCarFormat(stereo48k, 48000, 2)
        assertArrayEquals(stereo48k, out)
    }

    @Test
    fun aStretchedFrameKeepsItsDuration() {
        val oneSecondMono16k = ByteArray(16000 * 2)
        val out = CarAudioBus.toCarFormat(oneSecondMono16k, 16000, 1)
        assertEquals("one second in should be one second out", 48000 * 4, out.size)
    }
    @Test
    fun phoneSoundIsHeldBackWhileFtsOwnPlayerPlays() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_PLAYER, ByteArray(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        CarAudioBus.write(CarAudioBus.LANE_PLAYER, ByteArray(3840))
        assertEquals("the car must only get the player, not the player plus the phone", 2, got.size)
    }

    @Test
    fun phoneSoundIsHeldBackWhileAndroidAutoPlays() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_MEDIA, ByteArray(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        assertEquals(1, got.size)
    }

    @Test
    fun phoneSoundFlowsWhenNothingElsePlays() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_PHONE, loud(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, loud(3840))
        assertEquals(2, got.size)
    }

    @Test
    fun phoneSilenceIsKeptOffTheCarUntilSomethingPlays() {
        listen()
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        assertEquals(0, got.size)
        CarAudioBus.write(CarAudioBus.LANE_PHONE, loud(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        assertEquals("a quiet moment inside a song still reaches the car", 2, got.size)
    }

    @Test
    fun cdRateSoundIsStretchedToTheCarRate() {
        val oneSecond = ByteArray(44100 * 4)
        assertEquals(48000 * 4, CarAudioBus.toCarFormat(oneSecond, 44100, 2).size)
        val oneSecondMono = ByteArray(22050 * 2)
        assertEquals(48000 * 4, CarAudioBus.toCarFormat(oneSecondMono, 22050, 1).size)
        assertEquals(48000 * 4, CarAudioBus.toCarFormat(ByteArray(32000 * 4), 32000, 2).size)
    }

    @Test
    fun stretchingFollowsTheWaveInsteadOfRepeatingIt() {
        val frames = 441
        val ramp = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val v = f * 50
            for (c in 0..1) {
                ramp[f * 4 + c * 2] = (v and 0xFF).toByte()
                ramp[f * 4 + c * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        val out = CarAudioBus.toCarFormat(ramp, 44100, 2)
        var last = -1
        for (o in 0 until out.size / 4) {
            val v = ((out[o * 4 + 1].toInt() shl 8) or (out[o * 4].toInt() and 0xFF)).toShort().toInt()
            assertTrue("the stretched ramp went backwards at $o", v >= last)
            last = v
        }
        assertTrue(last >= (frames - 2) * 50)
    }

    private fun loud(bytes: Int) = ByteArray(bytes).also { b ->
        var i = 0
        while (i + 1 < b.size) {
            b[i] = 0x10
            b[i + 1] = 0x27
            i += 2
        }
    }
}
