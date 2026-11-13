package app.ft.core

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*

class ServiceLaunchesTest {
    private fun intent(): Intent = mock(Intent::class.java).also { intent ->
        var dispatched = false
        `when`(intent.putExtra(anyString(), eq(true))).thenAnswer { dispatched = true; intent }
        `when`(intent.getBooleanExtra(anyString(), eq(false))).thenAnswer { dispatched }
    }

    @Test fun queuedStartsKeepProfileLockedUntilEveryIntentArrives() {
        val launches = ServiceLaunches { }
        val first = intent()
        val second = intent()
        launches.dispatch(first)
        launches.dispatch(second)
        assertTrue(launches.hasPending)
        launches.received(first)
        assertTrue(launches.hasPending)
        launches.received(second)
        assertFalse(launches.hasPending)
    }

    @Test fun failedStartReleasesOnlyItsOwnPendingRequest() {
        val launches = ServiceLaunches { }
        val delivered = intent()
        launches.dispatch(delivered)
        launches.dispatch(intent())
        launches.finished()
        assertTrue(launches.hasPending)
        launches.received(delivered)
        assertFalse(launches.hasPending)
    }

    @Test fun frameworkRestartWithoutOurMarkerDoesNotConsumeQueuedStart() {
        val launches = ServiceLaunches { }
        launches.dispatch(intent())
        launches.received(null)
        launches.received(mock(Intent::class.java))
        assertTrue(launches.hasPending)
    }
}
