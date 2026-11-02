package app.ft.iccoa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class IccoaAudioPlayerStateTest {
    @Test fun delayedLaneReceivesAacStartWithReferenceFields() {
        val player = IccoaAudioPlayerState()
        player.play()
        repeat(3) { assertNull(player.flush { false }) }
        assertTrue(player.pending)
        assertEquals(0, player.flush { bytes ->
            val body = ProtoReader(bytes)
            assertEquals(setOf(1, 2, 3, 4, 5, 6), body.fields.keys)
            assertEquals(16, body.int(1))
            assertEquals(0, body.int(2))
            assertEquals(48000, body.int(3))
            assertEquals(2, body.int(4))
            assertEquals(2, body.int(5)) // UCar encoding_format PCM_16BIT
            assertEquals("audio/mp4a-latm", body.string(6))
            assertTrue(bytes.toList().zipWithNext().any { (a, b) ->
                (a.toInt() and 0xff) == 0x28 && (b.toInt() and 0xff) == 0x02
            })
            true
        })
        assertFalse(player.pending)
    }

    @Test fun pauseSupersedesAnUnsentStart() {
        val player = IccoaAudioPlayerState()
        player.play()
        player.flush { false }
        player.pause()
        assertEquals(2, player.flush { assertEquals(2, ProtoReader(it).int(2)); true })
        player.play()
        assertEquals(3, player.flush { true })
    }

    @Test fun repeatedPlayDoesNotRestartAnActivePlayer() {
        val player = IccoaAudioPlayerState()
        val states = mutableListOf<Int>()
        fun flush() = player.flush { states += ProtoReader(it).int(2); true }
        player.play(); flush()
        player.play(); flush()
        player.pause(); flush()
        player.pause(); flush()
        player.play(); flush()
        assertEquals(listOf(0, 2, 3), states)
    }

    @Test fun reconnectDropsPendingStateAndStartsANewPlayer() {
        val player = IccoaAudioPlayerState()
        player.play(); player.flush { true }
        player.pause(); player.flush { false }
        player.reset()
        assertFalse(player.pending)
        player.pause()
        assertNull(player.flush { fail("No old-session notification should be sent"); true })
        player.play()
        assertEquals(0, player.flush { true })
    }

    @Test fun failedPauseIsNotReplayedAfterAnotherPlay() {
        val player = IccoaAudioPlayerState()
        player.play(); player.flush { true }
        player.pause(); player.flush { false }
        player.play()
        assertFalse(player.pending)
        assertNull(player.flush { fail("Receiver already has the active state"); true })
    }
}
