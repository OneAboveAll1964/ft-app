package app.ft.aa

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class AaStarterCancellationTest {
    @Test fun interruptedCallerDoesNotOpenSettingsOrArmHelper() {
        val context = mock(Context::class.java)
        Thread.currentThread().interrupt()
        try {
            assertEquals(AaSwitch.Result.FAILED, AaSwitch.start(context))
            assertTrue(Thread.currentThread().isInterrupted)
            verifyNoInteractions(context)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptedCallerCannotStartAutomaticAttempt() {
        val context = mock(Context::class.java)
        Thread.currentThread().interrupt()
        try {
            assertFalse(AaKeep.switchOn(context))
            assertTrue(Thread.currentThread().isInterrupted)
            verifyNoInteractions(context)
        } finally {
            Thread.interrupted()
        }
    }
}
