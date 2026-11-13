package app.ft.iccoa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class IccoaMicrophoneTest {
    @Test fun gainIsAppliedAfterRawDiagnosticsAndChangesOnNextVoiceRequest() {
        var selected = 2
        val received = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val mic = IccoaMicrophone({ _, _ -> true }, { received += it }, logs::add,
            gain = { selected })
        val input = byteArrayOf(0, 64, 0, -64) // +16384, -16384
        mic.setWanted(true)
        mic.receive(9, input)
        selected = 1
        mic.setWanted(true) // A duplicate request must not reset gain or counters.
        mic.receive(9, input)
        mic.setWanted(false)
        assertArrayEquals(byteArrayOf(-1, 127, 0, -128), received[0])
        assertArrayEquals(received[0], received[1])
        assertArrayEquals(byteArrayOf(0, 64, 0, -64), input)
        assertTrue(logs.any { it.contains("packets=2 bytes=8 peak=16384") && it.contains("gain=2x gainClipped=2") })
        mic.setWanted(true)
        mic.receive(9, input)
        mic.setWanted(false)
        assertSame(input, received[2])
        assertTrue(logs.any { it.contains("packets=1 bytes=4 peak=16384") && it.contains("gain=1x gainClipped=0") })
    }

    @Test fun microphoneRequestUsesReferencePcmFormatAndStopsOnClose() {
        val states = mutableListOf<Pair<Int, Int>>()
        val mic = IccoaMicrophone({ method, bytes ->
            val fields = ProtoReader(bytes)
            states += method to fields.int(if (method == 9) 4 else 1)
            if (method == 6) {
                assertEquals(16000, fields.int(2))
                assertEquals(1, fields.int(3))
                assertEquals(2, fields.int(4))
            } else {
                assertEquals(9, method)
                assertArrayEquals(byteArrayOf(32, fields.int(4).toByte()), bytes)
            }
            true
        }, {})
        mic.setWanted(true)
        mic.setWanted(true)
        mic.close()
        mic.close()
        mic.setWanted(true)
        assertEquals(listOf(9 to 1, 6 to 1, 6 to 0, 9 to 0), states)
    }

    @Test fun onlyMicrophonePcmWhileListeningReachesAndroidAuto() {
        val received = mutableListOf<ByteArray>()
        val mic = IccoaMicrophone({ _, _ -> true }, { received += it })
        val pcm = byteArrayOf(1, 0, 0, -128)
        mic.receive(9, pcm)
        mic.setWanted(true)
        mic.receive(8, pcm)
        mic.receive(9, byteArrayOf())
        mic.receive(9, byteArrayOf(1))
        mic.receive(9, pcm)
        mic.setWanted(false)
        mic.receive(9, pcm)
        mic.close()
        mic.receive(9, pcm)
        assertEquals(1, received.size)
        assertArrayEquals(pcm, received.single())
    }

    @Test fun unavailableControlLaneRetriesLatestStateAndCloseCancelsRetries() {
        var available = false
        val states = mutableListOf<Int>()
        val mic = IccoaMicrophone({ method, body ->
            if (available && method == 6) states += ProtoReader(body).int(1)
            available
        }, {})
        mic.setWanted(true)
        assertTrue(mic.flush())
        available = true
        assertFalse(mic.flush())
        assertEquals(listOf(1), states)
        available = false
        mic.setWanted(false)
        assertTrue(mic.flush())
        mic.close()
        available = true
        assertFalse(mic.flush())
        assertEquals(listOf(1), states)
    }

    @Test fun closingAnUnusedMicrophoneDoesNotSendAnyRequests() {
        val mic = IccoaMicrophone({ _, _ -> fail("Unused microphone"); true }, {})
        mic.close()
    }

    @Test fun cancelledUnqueuedStartIsNotReplayedWhenLaneAppears() {
        var available = false
        val states = mutableListOf<Int>()
        val mic = IccoaMicrophone({ method, body ->
            if (available && method == 6) states += ProtoReader(body).int(1)
            available
        }, {})
        mic.setWanted(true)
        mic.setWanted(false)
        available = true
        assertFalse(mic.flush())
        assertTrue(states.isEmpty())
        mic.setWanted(true)
        assertEquals(listOf(1), states)
    }

    @Test fun retryOfPartialStartDoesNotRepeatVoiceAndKeepsItsPositionBeforeMicrophone() {
        val sent = mutableListOf<Pair<Int, Int>>()
        var microphoneAvailable = false
        val mic = IccoaMicrophone({ method, body ->
            val accepted = method != 6 || microphoneAvailable
            if (accepted) sent += method to ProtoReader(body).int(if (method == 9) 4 else 1)
            accepted
        }, {})
        mic.setWanted(true)
        assertTrue(mic.flush())
        assertEquals(listOf(9 to 1), sent)
        microphoneAvailable = true
        assertFalse(mic.flush())
        assertEquals(listOf(9 to 1, 6 to 1), sent)
        assertFalse(mic.flush())
        assertEquals(2, sent.size)
    }

    @Test fun cancellingPartialStartClearsVoiceWithoutStartingMicrophone() {
        val sent = mutableListOf<Pair<Int, Int>>()
        var microphoneAvailable = false
        val mic = IccoaMicrophone({ method, body ->
            val accepted = method != 6 || microphoneAvailable
            if (accepted) sent += method to ProtoReader(body).int(if (method == 9) 4 else 1)
            accepted
        }, {})
        mic.setWanted(true)
        mic.setWanted(false)
        microphoneAvailable = true
        assertFalse(mic.flush())
        mic.close()
        assertEquals(listOf(9 to 1, 9 to 0), sent)
    }

    @Test fun voiceOffWaitsForMicrophoneOffAndRetriesWithoutRepeatingMicrophoneOff() {
        val sent = mutableListOf<Pair<Int, Int>>()
        var rejectedMethod = 0
        val mic = IccoaMicrophone({ method, body ->
            val accepted = method != rejectedMethod
            if (accepted) sent += method to ProtoReader(body).int(if (method == 9) 4 else 1)
            accepted
        }, {})
        mic.setWanted(true)
        rejectedMethod = 6
        mic.setWanted(false)
        assertTrue(mic.flush())
        assertEquals(listOf(9 to 1, 6 to 1), sent)
        rejectedMethod = 9
        assertTrue(mic.flush())
        assertEquals(listOf(9 to 1, 6 to 1, 6 to 0), sent)
        rejectedMethod = 0
        assertFalse(mic.flush())
        assertEquals(listOf(9 to 1, 6 to 1, 6 to 0, 9 to 0), sent)
    }

    @Test fun newStartCancelsUnqueuedVoiceOffAfterPartialStop() {
        val sent = mutableListOf<Pair<Int, Int>>()
        var rejectVoiceOff = false
        val mic = IccoaMicrophone({ method, body ->
            val value = ProtoReader(body).int(if (method == 9) 4 else 1)
            val accepted = !(rejectVoiceOff && method == 9 && value == 0)
            if (accepted) sent += method to value
            accepted
        }, {})
        mic.setWanted(true)
        rejectVoiceOff = true
        mic.setWanted(false)
        mic.setWanted(true)
        rejectVoiceOff = false
        assertFalse(mic.flush())
        assertEquals(listOf(9 to 1, 6 to 1, 6 to 0, 6 to 1), sent)
    }

    @Test fun phoneStateProviderIsEvaluatedAgainWhenRetryingAndDoesNotSupplyCallState() {
        var locked = false
        var available = false
        val attempted = mutableListOf<ByteArray>()
        val mic = IccoaMicrophone({ method, body ->
            if (method == 9) attempted += body
            available
        }, {}, phoneStateBody = { voice ->
            ProtoWriter().bool(2, locked).bool(4, voice).bool(5, false).bool(6, false).toByteArray()
        })
        mic.setWanted(true)
        locked = true
        available = true
        assertFalse(mic.flush())
        mic.close()
        assertEquals(3, attempted.size)
        assertEquals(0, ProtoReader(attempted[0]).int(2))
        assertEquals(1, ProtoReader(attempted[1]).int(2))
        assertEquals(1, ProtoReader(attempted[1]).int(4))
        assertEquals(0, ProtoReader(attempted[2]).int(4))
        for (body in attempted) assertEquals(-1, ProtoReader(body).int(1, -1))
    }

    @Test fun temporaryPhoneStateFailureKeepsTheTransitionPending() {
        var failed = true
        val sent = mutableListOf<Int>()
        val mic = IccoaMicrophone({ method, _ -> sent += method; true }, {},
            phoneStateBody = { voice ->
                if (failed) throw IllegalStateException("phone state unavailable")
                ProtoWriter().bool(4, voice).toByteArray()
            })
        mic.setWanted(true)
        assertTrue(mic.flush())
        assertTrue(sent.isEmpty())
        failed = false
        assertFalse(mic.flush())
        assertEquals(listOf(9, 6), sent)
    }

    @Test fun inputDiagnosticsDistinguishSilenceFromPcmSignal() {
        val logs = mutableListOf<String>()
        val mic = IccoaMicrophone({ _, _ -> true }, {}, logs::add)
        mic.setWanted(true)
        mic.receive(9, byteArrayOf(0, 0))
        mic.receive(9, byteArrayOf(0, -128, -1, 127))
        mic.setWanted(false)
        assertTrue(logs.any { it.contains("first PCM packet: 2 bytes, peak=0") })
        assertTrue(logs.any { it.contains("packets=2 bytes=6 peak=32768") })
        mic.setWanted(true)
        mic.setWanted(false)
        assertTrue(logs.last().contains("car voice off queued"))
        assertTrue(logs.any { it.contains("packets=0 bytes=0 peak=0") })
    }
}
