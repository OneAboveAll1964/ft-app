package app.ft.core

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class AaRootProcessTest {
    @Test fun timeoutTerminatesShellWithoutReadingOutput() {
        val process = mock(Process::class.java)
        `when`(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(false)
        `when`(process.isAlive).thenReturn(true)
        assertFalse(AaRootProcess.await(process))
        verify(process).destroy()
        verify(process).destroyForcibly()
        verify(process, never()).getInputStream()
        verify(process, never()).getErrorStream()
    }

    @Test fun interruptionTerminatesShellAndPropagates() {
        val process = mock(Process::class.java)
        `when`(process.waitFor(2, TimeUnit.SECONDS)).thenThrow(InterruptedException())
        try {
            AaRootProcess.await(process)
            throw AssertionError("Interruption was swallowed")
        } catch (_: InterruptedException) {
            verify(process).destroy()
        }
    }

    @Test fun successfulCommandCompletesAndClosesProcess() {
        val process = mock(Process::class.java)
        `when`(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true)
        `when`(process.exitValue()).thenReturn(0)
        assertTrue(AaRootProcess.await(process))
        verify(process).destroy()
    }
}
