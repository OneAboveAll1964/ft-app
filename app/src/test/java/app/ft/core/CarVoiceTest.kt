package app.ft.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class CarVoiceTest {
    private val music = Collections.synchronizedList(ArrayList<ByteArray>())
    private val said = Collections.synchronizedList(ArrayList<String>())
    private val spoken = Collections.synchronizedList(ArrayList<ByteArray>())

    private val voice = object : CarAudioBus.Voice {
        override fun begin(rate: Int, channels: Int) {
            said.add("begin $rate/$channels")
        }

        override fun data(pcm: ByteArray) {
            said.add("data ${pcm.size}")
            spoken.add(pcm)
        }

        override fun end() {
            said.add("end")
        }
    }

    @Before
    fun setUp() {
        CarAudioBus.sink = { pcm -> music.add(pcm) }
        CarAudioBus.voice = voice
        CarAudioBus.voiceChannel = true
    }

    @After
    fun tearDown() {
        CarAudioBus.sink = null
        CarAudioBus.voice = null
        CarAudioBus.voiceChannel = false
        music.clear()
        said.clear()
        spoken.clear()
    }

    private fun peakOf(pcm: ByteArray): Int {
        var top = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            top = maxOf(top, if (v < 0) -v else v)
            i += 2
        }
        return top
    }

    private fun level(value: Int, bytes: Int) = ByteArray(bytes).also { b ->
        var i = 0
        while (i + 1 < b.size) {
            b[i] = (value and 0xFF).toByte()
            b[i + 1] = ((value shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun loud(bytes: Int) = ByteArray(bytes).also { b ->
        var i = 0
        while (i + 1 < b.size) {
            b[i] = 0x00
            b[i + 1] = 0x20
            i += 2
        }
    }

    @Test
    fun directionsGoToTheCarsVoiceChannel() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        assertEquals(listOf("begin 16000/1", "data 640"), said)
        assertTrue("directions leaked into the music", music.isEmpty())
    }

    @Test
    fun musicKeepsExactlyItsOwnLengthWhileDirectionsPlay() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        repeat(50) {
            CarAudioBus.play(CarAudioBus.LANE_MEDIA, loud(3840), 48000, 2)
            CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        }
        assertEquals("the car got more music than was played", 50 * 3840, music.sumOf { it.size })
    }

    @Test
    fun theVoiceChannelOpensOncePerPrompt() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        repeat(5) { CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1) }
        CarAudioBus.end(CarAudioBus.LANE_SPEECH)
        assertEquals(1, said.count { it.startsWith("begin") })
        assertEquals(5, said.count { it.startsWith("data") })
        assertEquals("end", said.last())
    }

    @Test
    fun silenceDoesNotOpenTheVoiceChannel() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        repeat(5) { CarAudioBus.play(CarAudioBus.LANE_SPEECH, ByteArray(640), 16000, 1) }
        assertTrue("silence ducked the music for nothing", said.isEmpty())
    }

    @Test
    fun aSecondPromptOpensTheChannelAgain() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        CarAudioBus.end(CarAudioBus.LANE_SPEECH)
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        CarAudioBus.end(CarAudioBus.LANE_SPEECH)
        assertEquals(listOf("begin 16000/1", "data 640", "end", "begin 16000/1", "data 640", "end"), said)
    }

    @Test
    fun systemSoundsDoNotCutIntoDirections() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        CarAudioBus.play(CarAudioBus.LANE_SYSTEM, loud(320), 16000, 1)
        assertEquals(listOf("begin 16000/1", "data 640"), said)
    }

    @Test
    fun systemSoundsUseTheVoiceChannelWhenNothingIsSaid() {
        CarAudioBus.play(CarAudioBus.LANE_SYSTEM, loud(320), 16000, 1)
        CarAudioBus.end(CarAudioBus.LANE_SYSTEM)
        assertEquals(listOf("begin 16000/1", "data 320", "end"), said)
    }

    @Test
    fun blendingStillWorksWhenTheVoiceChannelIsOff() {
        CarAudioBus.voiceChannel = false
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        assertTrue(said.isEmpty())
        assertEquals("directions were not sent with the music", 640 * 6, music.sumOf { it.size })
    }

    @Test
    fun nothingIsSentWithoutACar() {
        CarAudioBus.sink = null
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, loud(640), 16000, 1)
        assertTrue(said.isEmpty())
    }

    @Test
    fun quietDirectionsAreRaisedWithoutClipping() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, level(8000, 640), 16000, 1)
        val top = peakOf(spoken.last())
        assertTrue("directions were not raised: $top", top in 28000..29100)
    }

    @Test
    fun loudDirectionsAreLeftAlone() {
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, level(31000, 640), 16000, 1)
        assertEquals(31000, peakOf(spoken.last()))
    }

    @Test
    fun musicDipsUnderDirectionsAndComesBack() {
        CarAudioBus.play(CarAudioBus.LANE_MEDIA, level(8000, 3840), 48000, 2)
        assertEquals("music changed with nothing being said", 8000, peakOf(music.last()))
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        CarAudioBus.play(CarAudioBus.LANE_SPEECH, level(8000, 640), 16000, 1)
        repeat(5) { CarAudioBus.play(CarAudioBus.LANE_MEDIA, level(8000, 3840), 48000, 2) }
        val dipped = peakOf(music.last())
        assertTrue("music did not dip under the directions: $dipped", dipped in 2700..2900)
        CarAudioBus.end(CarAudioBus.LANE_SPEECH)
        repeat(30) { CarAudioBus.play(CarAudioBus.LANE_MEDIA, level(8000, 3840), 48000, 2) }
        assertEquals("music did not come back up", 8000, peakOf(music.last()))
    }

    @Test
    fun musicIsNotDippedWhenBlending() {
        CarAudioBus.voiceChannel = false
        CarAudioBus.begin(CarAudioBus.LANE_SPEECH)
        repeat(5) { CarAudioBus.play(CarAudioBus.LANE_MEDIA, level(8000, 3840), 48000, 2) }
        assertEquals(8000, peakOf(music.last()))
    }
}
