package app.ft.carlife

import android.content.Context
import android.content.SharedPreferences
import app.ft.core.Prefs
import app.ft.core.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class CarLifeProjectionReturnTest {

    @Test
    fun goToForegroundResumesPausedVideoFeedAndConfirmsToCar() = runTest {
        val link = TestLink()
        var shownCalled = false
        var videoBackCalled = false
        var keyframeCalled = false

        val session = session(link, backgroundScope)
        session.onProjectionShown = { shownCalled = true }
        session.onVideoBack = { videoBackCalled = true }
        session.onNeedKeyframe = { keyframeCalled = true }

        // 1. Initial video setup
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT, videoInitPayload(1024, 600, 30))
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_START)
        assertTrue(session.projecting)
        assertEquals(VideoFeed.Mode.STARTING, session.videoMode)

        // 2. Head unit pauses projection (e.g. entering reverse / camera)
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE)
        assertFalse(session.projecting)
        assertEquals(VideoFeed.Mode.PAUSED, session.videoMode)
        shownCalled = false
        videoBackCalled = false
        keyframeCalled = false

        // 3. Head unit sends CMD_GO_TO_FOREGROUND (e.g. exiting camera / user returns)
        link.command(CarLifeProtocol.CMD_GO_TO_FOREGROUND)

        // Verify video feed resumed, keyframe was requested, foreground was confirmed, and listener was notified
        assertTrue(session.projecting)
        assertEquals(VideoFeed.Mode.RESUMING, session.videoMode)
        assertTrue(videoBackCalled)
        assertTrue(keyframeCalled)
        assertTrue(shownCalled)
        assertTrue("Expected CMD_FOREGROUND in sent commands", link.sent.contains(CarLifeProtocol.CMD_FOREGROUND))
    }

    @Test
    fun vehicleForegroundResumesPausedVideoFeed() = runTest {
        val link = TestLink()
        var shownCalled = false
        var videoBackCalled = false
        var keyframeCalled = false

        val session = session(link, backgroundScope)
        session.onProjectionShown = { shownCalled = true }
        session.onVideoBack = { videoBackCalled = true }
        session.onNeedKeyframe = { keyframeCalled = true }

        // 1. Initial video setup and pause
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT, videoInitPayload(800, 480, 24))
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_START)
        link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_PAUSE)
        assertEquals(VideoFeed.Mode.PAUSED, session.videoMode)

        shownCalled = false

        // 2. Car reports VEHICLE_FOREGROUND while paused
        link.command(CarLifeProtocol.CMD_VEHICLE_FOREGROUND)

        assertTrue(session.projecting)
        assertEquals(VideoFeed.Mode.RESUMING, session.videoMode)
        assertTrue(videoBackCalled)
        assertTrue(keyframeCalled)
        assertTrue(shownCalled)
    }

    @Test
    fun leavingReverseGearRequestsForegroundEvenWhenActionIsNone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val service = mock(CarLifeService::class.java, org.mockito.Mockito.CALLS_REAL_METHODS)
            val session = mock(CarLifeSession::class.java)
            val prefs = mock(Prefs::class.java)
            val am = mock(android.media.AudioManager::class.java)
            val context = mock(Context::class.java)

            `when`(service.getSystemService(Context.AUDIO_SERVICE)).thenReturn(am)
            `when`(service.applicationContext).thenReturn(context)
            `when`(prefs.gearReverse).thenReturn(GearAction.NONE)
            `when`(prefs.gearPark).thenReturn(GearAction.NONE)
            `when`(prefs.gearReverseSound).thenReturn(GearAction.SOUND_KEEP)
            `when`(prefs.gearParkSound).thenReturn(GearAction.SOUND_KEEP)
            `when`(session.requestForeground()).thenReturn(true)

            setField(service, "gearSound", GearSound())
            setField(service, "tag", "CarLifeServiceTest")
            setField(service, "scope", backgroundScope)
            setField(service, "session", session)
            setField(service, "prefs", prefs)

            // 1. Enter Reverse (gear 5)
            invokeOnGear(service, session, GearAction.GEAR_REVERSE)
            dispatcher.scheduler.runCurrent()
            assertEquals(true, getField(service, "gearHidProjection"))

            // 2. Leave Reverse to Drive (gear 3)
            invokeOnGear(service, session, 3)
            // Wait out GEAR_HOLD_MS (700ms)
            advanceTimeBy(800)
            dispatcher.scheduler.runCurrent()

            // Verify foreground request was triggered
            val gearReturn = getField(service, "gearReturn") as? kotlinx.coroutines.Job
            assertTrue(gearReturn != null && gearReturn.isActive)
            org.mockito.Mockito.verify(session).requestForeground()

            // 3. Confirm projection return
            invokeOnGearProjectionShown(service, session)
            assertEquals(false, getField(service, "gearHidProjection"))
            assertEquals(null, getField(service, "gearReturn"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun session(link: TestLink, scope: CoroutineScope): CarLifeSession {
        val context = mock(Context::class.java)
        val shared = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(shared)
        `when`(shared.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
        `when`(shared.getString(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
        `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
        return CarLifeSession(context, link, Prefs(context), scope).also { it.start() }
    }

    private fun videoInitPayload(w: Int, h: Int, fps: Int) = ProtoWriter()
        .int32(1, w)
        .int32(2, h)
        .int32(3, fps)
        .toByteArray()

    private class TestLink : CarLifeLink {
        override val name = "test"
        override val connected = true
        override val isOpen = true
        val sent = mutableListOf<Int>()
        private lateinit var receive: LinkMessage

        override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit,
                           onConnected: () -> Unit, onClosed: (String) -> Unit) {
            receive = onMessage
        }

        override fun send(channel: Int, inner: ByteArray): Boolean {
            sent += CarLifeFraming.serviceId(channel, inner)
            return true
        }

        override fun stop() = Unit

        fun command(id: Int, payload: ByteArray = ByteArray(0)) {
            val packet = CarLifeFraming.cmd(id, payload)
            receive(CarLifeProtocol.CH_CMD, packet.copyOfRange(0, CarLifeProtocol.HEAD_CMD), payload)
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        return CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }

    private fun invokeOnGear(service: CarLifeService, session: CarLifeSession, gear: Int) {
        CarLifeService::class.java.getDeclaredMethod("onGear", CarLifeSession::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, session, gear)
    }

    private fun invokeOnGearProjectionShown(service: CarLifeService, session: CarLifeSession) {
        CarLifeService::class.java.getDeclaredMethod("onGearProjectionShown", CarLifeSession::class.java)
            .apply { isAccessible = true }
            .invoke(service, session)
    }
}
