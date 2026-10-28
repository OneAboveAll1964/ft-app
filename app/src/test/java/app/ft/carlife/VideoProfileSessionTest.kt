package app.ft.carlife

import android.content.Context
import android.content.SharedPreferences
import app.ft.core.Prefs
import app.ft.core.ProtoWriter
import app.ft.projection.VideoPlan
import app.ft.projection.VideoPlans
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class VideoProfileSessionTest {
    @Test fun nextSessionStartsWithTheWorkingRateAndSize() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(24)
        val saved = store.prefs.savedVideoProfile!!
        assertEquals(24, saved.plan.fps)
        val next = Connection(store, backgroundScope)
        next.init()
        assertEquals(saved.plan, next.started)
        assertEquals(24, next.session.fps)
    }

    @Test fun aRateTheCarLoweredNeverSlowsTheNextStart() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(15)
        assertEquals(15, store.prefs.savedVideoProfile!!.plan.fps)
        val next = Connection(store, backgroundScope)
        next.init()
        assertEquals(VideoPlans.START_FPS, next.started!!.fps)
        assertEquals(VideoPlans.START_FPS, next.session.fps)
    }

    @Test fun resetClearsProfileAndOldSessionCannotRecreateIt() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(24)
        store.prefs.clearVideoProfile()
        first.rate(30)
        assertNull(store.prefs.savedVideoProfile)
        val next = Connection(store, backgroundScope)
        next.init()
        assertEquals(VideoPlans.START_FPS, next.started!!.fps)
        assertNotNull(store.prefs.savedVideoProfile)
    }

    @Test fun manualOverrideInvalidatesProfileAndWinsOnReconnect() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(24)
        store.prefs.videoFps = 15
        assertNull(store.prefs.savedVideoProfile)
        first.rate(30)
        assertNull(store.prefs.savedVideoProfile)
        val next = Connection(store, backgroundScope)
        next.init()
        assertEquals(15, next.started!!.fps)
        assertFalse(store.prefs.savedVideoProfile!!.automaticFps)
    }

    @Test fun newCarScreenDoesNotReuseOldDimensionsOrRate() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(24)
        val next = Connection(store, backgroundScope)
        next.init(1280, 720)
        assertEquals(1280, next.started!!.streamWidth)
        assertEquals(VideoPlans.START_FPS, next.started!!.fps)
    }

    @Test fun explicitCarRequestTakesPriorityOverRememberedRate() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        first.init()
        first.rate(24)
        val next = Connection(store, backgroundScope)
        next.init(asked = 30)
        assertEquals(30, next.started!!.fps)
    }

    @Test fun failedEncoderStartDoesNotSaveAProfile() = runTest {
        val store = Store()
        val connection = Connection(store, backgroundScope)
        connection.session.onVideoConfig = { error("encoder refused") }
        assertThrows(IllegalStateException::class.java) { connection.init() }
        assertNull(store.prefs.savedVideoProfile)
    }

    @Test fun temporaryThermalLimitIsNotRemembered() = runTest {
        val store = Store()
        val first = Connection(store, backgroundScope)
        var liveFps = 0
        first.session.onFrameRate = { liveFps = VideoPlans.withinCap(it, 20) }
        first.init()
        first.rate(30)
        assertEquals(20, liveFps)
        assertEquals(30, store.prefs.savedVideoProfile!!.plan.fps)
        val next = Connection(store, backgroundScope)
        next.init()
        assertEquals(30, next.started!!.fps)
    }

    private class Connection(store: Store, scope: CoroutineScope) {
        private val link = TestLink()
        val session = CarLifeSession(store.context, link, Prefs(store.context), scope)
        var started: VideoPlan? = null
        init {
            session.onVideoConfig = { started = it }
            session.start()
        }
        fun init(width: Int = 1920, height: Int = 720, asked: Int = 0) =
            link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_INIT,
                ProtoWriter().int32(1, width).int32(2, height).int32(3, asked).toByteArray())
        fun rate(fps: Int) = link.command(CarLifeProtocol.CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE,
            ProtoWriter().int32(1, fps).toByteArray())
    }

    private class TestLink : CarLifeLink {
        override val name = "WiFi"
        override val connected = true
        override val isOpen = true
        private lateinit var receive: LinkMessage
        override fun start(scope: CoroutineScope, onMessage: LinkMessage, onEvent: (String) -> Unit,
                           onConnected: () -> Unit, onClosed: (String) -> Unit) { receive = onMessage }
        override fun send(channel: Int, inner: ByteArray) = true
        override fun stop() = Unit
        fun command(id: Int, payload: ByteArray) {
            val packet = CarLifeFraming.cmd(id, payload)
            receive(CarLifeProtocol.CH_CMD, packet.copyOfRange(0, CarLifeProtocol.HEAD_CMD), payload)
        }
    }

    private class Store {
        val values = mutableMapOf<String, Any>()
        val context = mock(Context::class.java)
        private val shared = mock(SharedPreferences::class.java)
        private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        val prefs: Prefs
        init {
            `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(shared)
            `when`(shared.edit()).thenReturn(editor)
            `when`(shared.contains(anyString())).thenAnswer { values.containsKey(it.getArgument(0)) }
            `when`(shared.getInt(anyString(), anyInt())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            `when`(shared.getString(anyString(), anyString())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String>(1) }
            `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            doAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }.`when`(editor).putInt(anyString(), anyInt())
            doAnswer { values[it.getArgument(0)] = it.getArgument<String>(1); editor }.`when`(editor).putString(anyString(), anyString())
            doAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }.`when`(editor).putBoolean(anyString(), anyBoolean())
            doAnswer { values.remove(it.getArgument<String>(0)); editor }.`when`(editor).remove(anyString())
            prefs = Prefs(context)
        }
    }
}
