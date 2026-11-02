package app.ft.carlife

import app.ft.core.MusicInfo
import app.ft.core.MusicTrack
import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AaMusicRelayTest {
    private class Rig {
        val work = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        var now = 0L
        var fail = false
        val relay = AaMusicRelay(
            dispatch = { work.addLast(it) },
            sendInfo = { if (fail) throw IllegalStateException("write failed"); events += "track:${it.id}" },
            sendPosition = { events += "position:$it" }, clock = { now },
            onError = { events += "error" }
        )
        fun drain() { while (work.isNotEmpty()) work.removeFirst()() }
        fun info(id: Long = 1, playing: Boolean = true, position: Int? = 0) =
            MusicInfo(MusicTrack(id, "Song $id", "Artist", "Album", 220), playing, position)
    }

    @Test fun connectionReadinessAndSubscriptionReplayInfoBeforePosition() {
        val r = Rig()
        r.relay.update(r.info()); r.drain()
        assertTrue(r.events.isEmpty())
        r.relay.ready(true); r.drain()
        assertEquals(listOf("track:1", "position:0"), r.events)
        r.relay.subscribe(false); r.drain()
        r.relay.update(r.info(2)); r.drain()
        assertEquals(2, r.events.size)
        r.relay.subscribe(true); r.drain()
        assertEquals(listOf("track:2", "position:0"), r.events.takeLast(2))
    }

    @Test fun pauseDoesNotPublishMetadataOrProgressAndResumeReplays() {
        val r = Rig()
        r.relay.ready(true)
        r.relay.update(r.info(playing = false)); r.drain()
        assertTrue(r.events.isEmpty())
        r.relay.update(r.info()); r.drain()
        r.events.clear()
        r.relay.update(r.info(playing = false, position = 1000)); r.drain()
        assertTrue(r.events.isEmpty())
        r.relay.update(r.info(position = 1000)); r.drain()
        assertEquals(listOf("track:1", "position:1000"), r.events)
    }

    @Test fun preferenceAndProjectionResumeReplayWithoutRequiringANewTrack() {
        val r = Rig()
        r.relay.ready(true); r.relay.enable(false); r.relay.update(r.info()); r.drain()
        assertTrue(r.events.isEmpty())
        r.relay.enable(true); r.drain()
        r.events.clear()
        r.relay.ready(false); r.drain()
        r.relay.ready(true); r.drain()
        assertEquals(listOf("track:1", "position:0"), r.events)
    }

    @Test fun progressIsRateLimitedButBackwardSeekIsImmediate() {
        val r = Rig()
        r.relay.ready(true); r.relay.update(r.info(position = 2000)); r.drain()
        r.events.clear()
        r.now = 100
        r.relay.update(r.info(position = 3000)); r.drain()
        assertTrue(r.events.isEmpty())
        r.now = 1000
        r.relay.update(r.info(position = 4000)); r.drain()
        r.now = 1001
        r.relay.update(r.info(position = 500)); r.drain()
        assertEquals(listOf("position:4000", "position:500"), r.events)
    }

    @Test fun queuedUpdatesAreCoalescedAndCloseInvalidatesPendingWork() {
        val r = Rig()
        r.relay.ready(true)
        for (i in 1L..100L) r.relay.update(r.info(i))
        assertEquals(1, r.work.size)
        r.drain()
        assertEquals(listOf("track:100", "position:0"), r.events)
        r.events.clear()
        r.relay.update(r.info(101))
        r.relay.close(); r.drain()
        r.relay.update(r.info(102)); r.drain()
        assertTrue(r.events.isEmpty())
    }

    @Test fun failedSendDoesNotStrandTheWorkerAndNextUpdateRetriesInfo() {
        val r = Rig()
        r.relay.ready(true); r.fail = true; r.relay.update(r.info()); r.drain()
        assertEquals(listOf("error"), r.events)
        r.fail = false; r.relay.update(r.info(position = 1000)); r.drain()
        assertEquals(listOf("track:1", "position:1000"), r.events.takeLast(2))
    }

    @Test fun trackChangeWhileSendingSuppressesTheOldTracksProgress() {
        val work = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val first = MusicTrack(1, "First", "", "", 100)
        lateinit var relay: AaMusicRelay
        relay = AaMusicRelay({ work += it }, {
            events += "track:${it.id}"
            if (it.id == 1L) relay.update(MusicInfo(first.copy(id = 2), true, 2000))
        }, { events += "position:$it" })
        relay.ready(true); relay.update(MusicInfo(first, true, 1000))
        while (work.isNotEmpty()) work.removeFirst()()
        assertEquals(listOf("track:1", "track:2", "position:2000"), events)
    }

    @Test fun pauseAndCloseDoNotWaitForArtworkAndInvalidateItsResult() {
        for (close in listOf(false, true)) {
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            val pending = ArrayDeque<() -> Unit>()
            val track = MusicTrack(1, "Song", "", "", 100)
            lateinit var relay: AaMusicRelay
            relay = AaMusicRelay({ pending.addLast(it) }, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // The session checks validity again after cover conversion.
                if (relay.allows(it)) events += "track"
            }, { events += "position" })
            try {
                relay.update(MusicInfo(track, true, 1000))
                relay.ready(true)
                // Start one coalesced worker only after both inputs are present.
                val task = pending.removeFirst()
                executor.execute { try { task() } finally { finished.countDown() } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // These calls run while the artwork callback is blocked.
                if (close) relay.close() else relay.update(MusicInfo(track, false, 1000))
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertTrue(events.isEmpty())
            } finally {
                release.countDown()
                relay.close()
                executor.shutdownNow()
            }
        }
    }

    @Test fun payloadIncludesAllRequiredFieldsAndCorrectTimeUnits() {
        val track = MusicTrack(7, "Song", "Artist", "Album", 220)
        val r = ProtoReader(AaMusicRelay.payload(track, null))
        for (field in 1..9) assertTrue("missing field $field", r.has(field))
        assertEquals(220, r.int(6))
        assertEquals("Song", r.string(2))
        assertEquals(0, r.bytes(5)!!.size)
        assertNotEquals(r.string(1), ProtoReader(AaMusicRelay.payload(track.copy(id = 8), null)).string(1))
    }
}
