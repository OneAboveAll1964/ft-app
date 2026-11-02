package app.ft.carlife

import app.ft.aa.AaNavigation
import app.ft.aa.AaProtocol
import app.ft.core.NavigationInfo
import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NavigationRelayTest {
    private val turns = mutableListOf<Triple<Int, NavigationInfo, Int>>()
    private val modules = mutableListOf<Boolean>()
    private val relay = NavigationRelay({ action, info, total -> turns += Triple(action, info, total) }, { modules += it }, dispatch = { it() })
    private val turn = NavigationInfo(true, 8, "Lenina", distanceMeters = 300, timeSeconds = 25)

    @Test fun subscriptionReplaysLatestTurnAndStopClearsIt() {
        relay.update(turn)
        assertTrue(turns.isEmpty())
        assertFalse(relay.active)
        relay.subscribe(true)
        assertEquals(Triple(1, turn, 300), turns.single())
        relay.update(turn.copy(distanceMeters = 200))
        assertEquals(2, turns.last().first)
        assertEquals(300, turns.last().third)
        relay.subscribe(false)
        assertEquals(3, turns.last().first)
        assertEquals(listOf(true, false), modules)
        relay.subscribe(true)
        assertEquals(1, turns.last().first)
        assertEquals(200, turns.last().third)
    }

    @Test fun voiceEndDoesNotDeactivateAnOngoingRoute() {
        relay.subscribe(true)
        relay.update(turn)
        relay.voice(true)
        relay.voice(false)
        assertEquals(listOf(true), modules)
        relay.voice(true)
        relay.update(NavigationInfo())
        assertEquals(3, turns.last().first)
        assertTrue(relay.active)
        relay.voice(false)
        assertEquals(listOf(true, false), modules)
    }

    @Test fun disablingClearsImmediatelyAndEnablingReplaysTheCurrentTurn() {
        relay.subscribe(true)
        relay.update(turn)
        relay.enable(false)
        assertEquals(listOf(1, 3), turns.map { it.first })
        assertFalse(relay.active)
        relay.update(turn.copy(distanceMeters = 120))
        relay.subscribe(false)
        relay.subscribe(true)
        assertEquals(2, turns.size)
        relay.enable(true)
        assertEquals(Triple(1, turn.copy(distanceMeters = 120), 120), turns.last())
        assertTrue(relay.active)
    }

    @Test fun disabledPreferenceSurvivesReconnectAndDoesNotSuppressSpeech() {
        val disabled = NavigationRelay({ action, info, total -> turns += Triple(action, info, total) },
            { modules += it }, dispatch = { it() }, enabled = false)
        repeat(2) {
            disabled.subscribe(true)
            disabled.update(turn)
            disabled.voice(true)
            assertTrue(disabled.active)
            disabled.voice(false)
            assertFalse(disabled.active)
            disabled.reset()
        }
        assertTrue(turns.isEmpty())
        assertEquals(listOf(true, false, true, false), modules)
    }

    @Test fun disablingDuringSpeechClearsOnlyTheVisualInstruction() {
        relay.subscribe(true)
        relay.update(turn)
        relay.voice(true)
        relay.enable(false)
        assertEquals(3, turns.last().first)
        assertTrue(relay.active)
        relay.voice(false)
        assertFalse(relay.active)
        relay.update(NavigationInfo())
        relay.enable(true)
        assertEquals(listOf(1, 3), turns.map { it.first })
        assertEquals(listOf(true, false), modules)
    }

    @Test fun speechStillWorksWithoutClusterSubscription() {
        relay.voice(true)
        relay.update(turn)
        relay.voice(false)
        assertEquals(listOf(true, false), modules)
        assertTrue(turns.isEmpty())
    }

    @Test fun changedTurnWaitsForItsDistanceWithoutSendingAFakeArrival() {
        relay.subscribe(true)
        relay.update(turn)
        relay.update(turn.copy(maneuver = 7, distanceMeters = null))
        assertEquals(1, turns.size)
        relay.update(turn.copy(maneuver = 7, distanceMeters = 100))
        assertEquals(2, turns.last().first)
        assertEquals(100, turns.last().third)
        relay.update(turn.copy(maneuver = 7, distanceMeters = 100))
        assertEquals(2, turns.size)
    }

    @Test fun resetDropsSubscriptionAndOldRoute() {
        relay.subscribe(true)
        relay.update(turn)
        relay.reset()
        assertFalse(relay.active)
        relay.subscribe(true)
        assertEquals(1, turns.size)
    }

    @Test fun blockedWriteDoesNotBlockChangesOrResetAndKeepsOneConsumer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val sent = Collections.synchronizedList(mutableListOf<NavigationInfo>())
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val workers = Collections.synchronizedList(mutableListOf<Thread>())
        val changing = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val queued = NavigationRelay({ _, info, _ ->
            if (sent.isEmpty()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            sent += info
            effects += "turn:${info.road}"
        }, { effects += "module:$it" }, dispatch = { task ->
            starts.incrementAndGet()
            val worker = Thread { task() }.apply { isDaemon = true }
            workers += worker
            worker.start()
        })
        try {
            queued.subscribe(true)
            queued.update(turn)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            changing.submit {
                queued.enable(false)
                queued.voice(true)
                queued.reset()
                queued.enable(true)
                queued.subscribe(true)
                queued.update(turn.copy(road = "New road"))
            }.get(2, TimeUnit.SECONDS)
            assertEquals(1, starts.get())
            release.countDown()
            workers.single().join(2000)
            assertFalse(workers.single().isAlive)
            assertEquals(listOf("Lenina", "New road"), sent.map { it.road })
            assertEquals(listOf("module:true", "turn:Lenina", "module:true", "turn:New road"), effects)
        } finally {
            release.countDown()
            changing.shutdownNow()
            workers.forEach { it.join(2000) }
        }
    }

    @Test fun failedPacketDoesNotStrandQueuedClearsAndModuleUpdates() {
        val tasks = mutableListOf<() -> Unit>()
        val effects = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        val queued = NavigationRelay({ action, _, _ ->
            effects += "turn:$action"
            if (action == 1) throw IllegalStateException("transport closed")
        }, { effects += "module:$it" }, dispatch = { tasks += it }, onError = { errors += it })
        queued.subscribe(true)
        queued.update(turn)
        queued.enable(false)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).invoke()
        assertEquals(listOf("module:true", "turn:1", "turn:3", "module:false"), effects)
        assertEquals(1, errors.size)
        queued.voice(true)
        tasks.single().invoke()
        assertEquals("module:true", effects.last())
    }

    @Test fun dispatcherFailureAllowsTheNextChangeToRestartTheConsumer() {
        var reject = true
        val queued = NavigationRelay({ _, _, _ -> }, { modules += it }, dispatch = {
            if (reject) throw IllegalStateException("dispatcher unavailable") else it()
        })
        assertThrows(IllegalStateException::class.java) { queued.voice(true) }
        reject = false
        queued.voice(false)
        assertEquals(listOf(true, false), modules)
    }

    @Test fun voiceWriterWaitsForModuleUpdateBeforeSendingAudio() {
        val tasks = LinkedBlockingQueue<() -> Unit>()
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val finished = CountDownLatch(1)
        val queued = NavigationRelay({ _, _, _ -> }, { effects += "module:$it" }, dispatch = { tasks.add(it) })
        val writer = Thread {
            queued.voiceAndWait(true)
            effects += "audio"
            queued.voiceAndWait(false)
            effects += "next phrase"
            finished.countDown()
        }.apply { isDaemon = true; start() }
        try {
            val start = tasks.poll(2, TimeUnit.SECONDS)!!
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertTrue(effects.isEmpty())
            start()
            // Service any subsequent consumer until the writer completes, without relying on
            // how soon the scheduler resumes it after the first barrier.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (finished.count != 0L && System.nanoTime() < deadline) {
                tasks.poll(50, TimeUnit.MILLISECONDS)?.invoke()
            }
            assertEquals(0L, finished.count)
            assertEquals(listOf("module:true", "audio", "module:false", "next phrase"), effects)
        } finally {
            queued.reset()
            writer.interrupt()
            writer.join(2000)
        }
    }

    @Test fun resetReleasesVoiceWriterEvenWhenTransportIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val queued = NavigationRelay({ _, _, _ -> }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, dispatch = { task -> Thread { task() }.apply { isDaemon = true; start() } })
        val writer = Thread {
            queued.voiceAndWait(true)
            finished.countDown()
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            queued.reset()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.interrupt()
            writer.join(2000)
        }
    }

    @Test fun realAaWireDataBecomesACompleteCarLifeNextTurnPayload() {
        val decoder = AaNavigation()
        relay.subscribe(true)
        relay.update(decoder.accept(AaProtocol.NAV_STATE,
            byteArrayOf(10, 14, 10, 2, 8, 8, 18, 8, 10, 6, 76, 101, 110, 105, 110, 97))!!)
        relay.update(decoder.accept(AaProtocol.NAV_CURRENT_POSITION,
            byteArrayOf(10, 7, 10, 3, 8, -84, 2, 16, 25))!!)
        val (action, info, total) = turns.single()
        val icon = byteArrayOf(-119, 80, 78, 71)
        val r = ProtoReader(NavigationRelay.payload(action, info, total, icon))
        for (field in 1..6) assertTrue("required field $field missing", r.has(field))
        assertEquals(1, r.int(1))
        assertEquals(3, r.int(2))
        assertEquals("Lenina", r.string(3))
        assertEquals(300, r.int(4))
        assertEquals(300, r.int(5))
        assertEquals(25, r.int(6))
        assertArrayEquals(icon, r.fields[6]!!.filterIsInstance<ByteArray>().single())
        relay.update(NavigationInfo())
        val end = turns.last()
        val cleared = ProtoReader(NavigationRelay.payload(end.first, end.second, end.third, icon))
        assertEquals(3, cleared.int(1))
        assertEquals(24, cleared.int(2))
        assertEquals(0, cleared.int(5))
    }

    @Test fun navigationAndMusicAreOfferedButAssistantGuideIsNot() {
        val request = ProtoWriter().int32(1, 3)
        for (id in 0..2) request.message(2, ProtoWriter().int32(1, id))
        val response = ProtoReader(CarLifeSession.subscribeDone(request.toByteArray()))
        assertEquals(3, response.int(1))
        assertEquals(listOf(true, false, true), response.messages(2).map { it.bool(2) })
    }

    @Test fun maneuverMappingCoversBothSidesAndDestination() {
        assertEquals(7, NavigationRelay.turnCode(7))
        assertEquals(3, NavigationRelay.turnCode(8))
        assertEquals(5, NavigationRelay.turnCode(11))
        assertEquals(5, NavigationRelay.turnCode(12))
        assertEquals(9, NavigationRelay.turnCode(34))
        assertEquals(24, NavigationRelay.turnCode(42))
    }
}
