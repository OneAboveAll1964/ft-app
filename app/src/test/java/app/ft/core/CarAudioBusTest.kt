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
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        CarAudioBus.write(CarAudioBus.LANE_PHONE, ByteArray(3840))
        assertEquals(2, got.size)
    }
}
