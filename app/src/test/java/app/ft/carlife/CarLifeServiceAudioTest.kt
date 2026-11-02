package app.ft.carlife

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import app.ft.FTApp
import app.ft.core.CarAudioBus
import app.ft.core.CarProtocol
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.projection.AudioCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CarLifeServiceAudioTest {
    private val service = mock(CarLifeService::class.java, CALLS_REAL_METHODS)
    private val audio = mock(AudioCapture::class.java)
    private val projection = mock(MediaProjection::class.java)
    private val session = mock(CarLifeSession::class.java)
    private val app = mock(FTApp::class.java)
    private val prefs = mock(Prefs::class.java)
    private val queue = Queue()
    private val recoveryScope = TestScope()
    @Suppress("UNCHECKED_CAST")
    private val state = CarLifeService::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(null) as MutableStateFlow<CarState>
    private val previous = state.value

    init {
        doReturn(app).`when`(service).application
        `when`(app.prefs).thenReturn(prefs)
        `when`(prefs.carProtocol).thenReturn(CarProtocol.CARLIFE)
        doReturn(0).`when`(service).checkSelfPermission(anyString())
        `when`(session.projecting).thenReturn(true)
        `when`(audio.active).thenReturn(true)
        `when`(audio.startedAt).thenReturn(123L)
        set("tag", "CarLifeTest")
        set("audio", audio)
        set("projection", projection)
        set("projectionLock", Any())
        set("session", session)
        set("soundQueue", queue)
        set("scope", recoveryScope.backgroundScope)
        CarAudioBus.inCall = false
    }

    @After fun cleanup() {
        recoveryScope.cancel()
        CarAudioBus.inCall = false
        state.value = previous
    }

    @Test fun quietDeclineWithoutPendingLaunchReleasesSoundWait() {
        state.value = CarState(waitingForShare = true, shareForSound = true)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(CarLifeService.ACTION_SHARE_DECLINED)
        `when`(intent.getBooleanExtra("quiet", false)).thenReturn(true)
        service.onStartCommand(intent, 0, 1)
        assertFalse(state.value.waitingForShare)
        assertFalse(state.value.shareForSound)
        assertEquals(true, get("quietShareFailed"))
    }

    @Test fun normalCallEndRestartsTheCaptureThatWasPlayingBeforeCall() {
        mode(AudioManager.MODE_IN_CALL)
        assertTrue(CarAudioBus.inCall)
        assertTrue(queue.tasks.isEmpty())
        mode(AudioManager.MODE_NORMAL)
        assertFalse(CarAudioBus.inCall)
        assertEquals(1, queue.tasks.size)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        verify(audio).stop()
        verify(audio).start(projection)
    }

    @Test fun callEndCannotRestartSoundForAReplacedProjection() {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        val replacement = mock(MediaProjection::class.java)
        set("projection", replacement)
        queue.runAll()
        verify(audio, never()).stop()
        verify(audio, never()).start(projection)
        verify(audio, never()).start(replacement)
    }

    @Test fun callEndCannotReuseARevokedProjection() {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        set("projection", null)
        queue.runAll()
        verify(audio, never()).stop()
        verify(audio, never()).start(projection)
    }

    @Test fun queuedRecoveryWaitsForTheNextCallToEnd() {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        mode(AudioManager.MODE_IN_CALL)
        queue.runAll()
        verify(audio, never()).stop()
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        verify(audio).start(projection)
    }

    @Test fun callWithoutPhoneCaptureDoesNotStartItAfterwards() {
        `when`(audio.active).thenReturn(false)
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        assertTrue(queue.tasks.isEmpty())
    }

    @Test fun failedStartAfterCallIsRetriedAndHealthyCaptureIsKept() = recoveryScope.runTest {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        `when`(audio.start(projection)).thenReturn(false, true)
        queue.runAll()
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        queue.runAll()
        verify(audio, times(2)).start(projection)
        `when`(audio.active).thenReturn(true)
        advanceTimeBy(750)
        runCurrent()
        queue.runAll()
        verify(audio, times(2)).start(projection)
        verify(audio, times(1)).stop()
    }

    @Test fun captureDyingJustAfterRestartIsRetried() = recoveryScope.runTest {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        doAnswer { `when`(audio.startedAt).thenReturn(456L); true }.`when`(audio).start(projection)
        queue.runAll()
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        queue.runAll()
        verify(audio, times(2)).start(projection)
    }

    @Test fun repeatedStartFailuresHaveABoundedRetryCount() = recoveryScope.runTest {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        repeat(5) {
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            queue.runAll()
        }
        verify(audio, times(4)).start(projection)
    }

    @Test fun retryCannotRestartAReplacementCapture() = recoveryScope.runTest {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        runCurrent()
        `when`(audio.startedAt).thenReturn(456L)
        advanceTimeBy(750)
        runCurrent()
        queue.runAll()
        verify(audio, times(1)).start(projection)
    }

    @Test fun queuedRetryIsDiscardedWhenAnotherCallStarts() = recoveryScope.runTest {
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        mode(AudioManager.MODE_IN_CALL)
        queue.runAll()
        verify(audio, times(1)).start(projection)
    }

    @Test fun queuedRetryIsDiscardedWhenWatchingStops() = recoveryScope.runTest {
        val manager = mock(AudioManager::class.java)
        doReturn(manager).`when`(service).getSystemService(Context.AUDIO_SERVICE)
        mode(AudioManager.MODE_IN_CALL)
        mode(AudioManager.MODE_NORMAL)
        `when`(audio.active).thenReturn(false)
        queue.runAll()
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        invoke("stopWatchingCalls")
        queue.runAll()
        verify(audio, times(1)).start(projection)
    }

    @Test fun fallbackPollingObservesEndOfCall() = runTest {
        val manager = mock(AudioManager::class.java)
        doReturn(manager).`when`(service).getSystemService(Context.AUDIO_SERVICE)
        `when`(manager.mode).thenReturn(AudioManager.MODE_IN_CALL)
        `when`(audio.active).thenReturn(false)
        set("scope", backgroundScope)
        invoke("watchCalls")
        assertTrue(CarAudioBus.inCall)
        runCurrent()
        `when`(manager.mode).thenReturn(AudioManager.MODE_NORMAL)
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(CarAudioBus.inCall)
        invoke("stopWatchingCalls")
    }

    private fun mode(value: Int) = CarLifeService::class.java.getDeclaredMethod("onAudioMode", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, value)
    private fun invoke(name: String) = CarLifeService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    private fun set(name: String, value: Any?) = CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    private fun get(name: String) = CarLifeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private class Queue : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runAll() {
            while (tasks.isNotEmpty()) {
                val previous = DiagLog.entries.value.lastOrNull()
                tasks.removeFirst().run()
                val failures = DiagLog.entries.value.takeLastWhile { it !== previous }
                    .filter { it.text.startsWith("a step of the car sound failed") }
                assertTrue(failures.joinToString("\n") { it.text }, failures.isEmpty())
            }
        }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
}
