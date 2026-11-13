package app.ft.carlife

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class CarLifeVoiceTest {
    @Test fun navigationIsActivatedBeforeTheFirstWirelessAudioPacket() {
        val f = Fixture()
        f.voice.begin(16_000, 1)
        f.voice.data(byteArrayOf(1, 2))
        assertEquals(listOf("nav:true", "init", "data"), f.events)
    }

    @Test fun phrasesLessThanOneAndAHalfSecondsApartReuseTheChannelAndReleaseItAfterSilence() {
        val f = Fixture()
        f.voice.begin(16_000, 1)
        f.voice.end()
        f.time = 963 // Gap observed in the OPPO report on 6 November.
        f.voice.begin(16_000, 1)
        f.voice.data(byteArrayOf(3))
        f.voice.end()
        f.time = 2_462
        f.voice.tick()
        assertEquals(listOf("nav:true", "init", "data"), f.events)
        f.time++
        f.voice.tick()
        f.voice.tick()
        assertEquals(listOf("nav:true", "init", "data", "end", "nav:false"), f.events)
    }

    @Test fun aFormatChangeClosesTheOldStreamBeforeStartingAnother() {
        val f = Fixture()
        f.voice.begin(16_000, 1)
        f.voice.end()
        f.voice.begin(48_000, 2)
        assertEquals(listOf("nav:true", "init", "end", "nav:false", "nav:true", "init"), f.events)
        assertEquals(48_000, ProtoReader(f.lastInit).int(1))
        assertEquals(2, ProtoReader(f.lastInit).int(2))
    }

    @Test fun anExpiredTailCannotBeRevivedByALateStart() {
        val f = Fixture()
        f.voice.begin(16_000, 1)
        f.voice.end()
        f.time = 1_500
        f.voice.begin(16_000, 1)
        assertEquals(listOf("nav:true", "init", "end", "nav:false", "nav:true", "init"), f.events)
    }

    @Test fun resettingWhileNavigationIsBeingWrittenPreventsOldInitAndData() {
        val f = Fixture()
        f.onNavigation = { if (it) f.current = false }
        f.voice.begin(16_000, 1)
        f.voice.data(byteArrayOf(1))
        f.voice.end()
        f.time = 2_000
        f.voice.tick()
        assertEquals(listOf("nav:true"), f.events)
    }

    @Test fun oldQueuedCommandsAndDelayedEndCannotReachAReplacementStream() {
        val f = Fixture()
        f.voice.begin(16_000, 1)
        f.voice.end()
        f.events.clear()
        f.current = false
        f.time = 2_000
        f.voice.tick()
        f.voice.begin(16_000, 1)
        f.voice.data(byteArrayOf(1))
        f.voice.end()
        assertTrue(f.events.isEmpty())
    }

    @Test fun dataWithoutAStartIsDiscardedAndRepeatedEndsDoNotExtendTheTail() {
        val f = Fixture()
        f.voice.data(byteArrayOf(1))
        f.voice.end()
        assertTrue(f.events.isEmpty())
        f.voice.begin(0, 3)
        assertEquals(16_000, ProtoReader(f.lastInit).int(1))
        assertEquals(2, ProtoReader(f.lastInit).int(2))
        f.voice.end()
        f.time = 1_000
        f.voice.end()
        f.time = 1_500
        f.voice.tick()
        assertEquals(listOf("nav:true", "init", "end", "nav:false"), f.events)
    }

    @Test fun wiredVoiceKeepsImmediateEndAndItsOriginalModuleOrder() {
        val f = Fixture(wireless = false)
        f.voice.begin(16_000, 1)
        f.voice.end()
        assertEquals(listOf("init", "nav:true", "end", "nav:false"), f.events)
    }

    private class Fixture(wireless: Boolean = true) {
        var time = 0L
        var current = true
        var lastInit = ByteArray(0)
        val events = mutableListOf<String>()
        var onNavigation: (Boolean) -> Unit = {}
        val voice = CarLifeVoice({ time }, { current }, { active ->
            events += "nav:$active"
            onNavigation(active)
        }, { id, payload ->
            events += when (id) {
                CarLifeProtocol.TTS_INIT -> { lastInit = payload; "init" }
                CarLifeProtocol.TTS_DATA -> "data"
                CarLifeProtocol.TTS_END -> "end"
                else -> error("Unexpected TTS packet $id")
            }
        }, wireless)
    }
}
