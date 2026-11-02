package app.ft.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*

class BluetoothCallsTest {
    private val context = mock(Context::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
    }
    private val adapter = mock(BluetoothAdapter::class.java).also {
        `when`(it.isEnabled).thenReturn(true)
    }
    private val handler = mock(Handler::class.java)
    private val profile = mock(BluetoothProfile::class.java)
    private lateinit var listener: BluetoothProfile.ServiceListener
    private lateinit var timeout: Runnable
    private var permissionGranted = true
    private val monitor = BluetoothCalls(context, adapter, handler) { permissionGranted }

    init {
        doAnswer {
            listener = it.getArgument(1)
            true
        }.`when`(adapter).getProfileProxy(eq(context), any(BluetoothProfile.ServiceListener::class.java), eq(BluetoothProfile.HEADSET))
        doAnswer {
            timeout = it.getArgument(0)
            true
        }.`when`(handler).postDelayed(any(Runnable::class.java), eq(5_000L))
        `when`(profile.connectedDevices).thenReturn(emptyList())
    }

    private fun connectProfile() {
        monitor.refresh()
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
    }

    @Test fun pairedCarDoesNotCountAsConnectedForCalls() {
        val car = mock(BluetoothDevice::class.java)
        `when`(adapter.bondedDevices).thenReturn(setOf(car))
        connectProfile()
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        assertEquals(emptyList<String>(), monitor.state.value.devices)
        monitor.close()
    }

    @Test fun reportsActualHfpDevicesAndUpdatesOnDisconnect() {
        val headphones = mock(BluetoothDevice::class.java)
        `when`(headphones.name).thenReturn("Headphones")
        `when`(profile.connectedDevices).thenReturn(listOf(headphones))
        connectProfile()
        assertEquals(BluetoothCallsState(BluetoothCallsStatus.CONNECTED, listOf("Headphones")), monitor.state.value)
        `when`(profile.connectedDevices).thenReturn(emptyList())
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun bluetoothOffIsNotOverwrittenByPendingTimeout() {
        monitor.refresh()
        `when`(adapter.isEnabled).thenReturn(false)
        monitor.refresh()
        timeout.run()
        assertEquals(BluetoothCallsStatus.OFF, monitor.state.value.status)
        `when`(adapter.isEnabled).thenReturn(true)
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.CHECKING, monitor.state.value.status)
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun timeoutIsUnknownRatherThanDisconnectedAndLateResultRecovers() {
        monitor.refresh()
        timeout.run()
        assertEquals(BluetoothCallsStatus.UNAVAILABLE, monitor.state.value.status)
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun permissionLossIsShownAndRefreshRecoversAfterGrant() {
        connectProfile()
        `when`(profile.connectedDevices).thenThrow(SecurityException())
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.PERMISSION_REQUIRED, monitor.state.value.status)
        doReturn(emptyList<BluetoothDevice>()).`when`(profile).connectedDevices
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.CHECKING, monitor.state.value.status)
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun serviceDisconnectRetainsProxyForCleanupAndCanReconnect() {
        connectProfile()
        listener.onServiceDisconnected(BluetoothProfile.HEADSET)
        assertEquals(BluetoothCallsStatus.CHECKING, monitor.state.value.status)
        timeout.run()
        assertEquals(BluetoothCallsStatus.UNAVAILABLE, monitor.state.value.status)
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        listener.onServiceDisconnected(BluetoothProfile.HEADSET)
        monitor.close()
        monitor.close()
        verify(adapter, times(1)).closeProfileProxy(BluetoothProfile.HEADSET, profile)
    }

    @Test fun initialPermissionCheckPreventsProxyRequestAndRecoversAfterGrant() {
        permissionGranted = false
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.PERMISSION_REQUIRED, monitor.state.value.status)
        verify(adapter, never()).getProfileProxy(any(), any(), anyInt())
        permissionGranted = true
        connectProfile()
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun bluetoothRestartClosesOldProxyAndRequestsFreshBinding() {
        connectProfile()
        val previous = listener
        `when`(adapter.isEnabled).thenReturn(false)
        monitor.refresh()
        verify(adapter).closeProfileProxy(BluetoothProfile.HEADSET, profile)
        `when`(adapter.isEnabled).thenReturn(true)
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.CHECKING, monitor.state.value.status)
        val replacement = mock(BluetoothProfile::class.java)
        `when`(replacement.connectedDevices).thenReturn(emptyList())
        listener.onServiceConnected(BluetoothProfile.HEADSET, replacement)
        previous.onServiceDisconnected(BluetoothProfile.HEADSET)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        verify(adapter, times(2)).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET))
        monitor.close()
        verify(adapter).closeProfileProxy(BluetoothProfile.HEADSET, replacement)
    }

    @Test fun pendingCallbackFromBeforeBluetoothRestartIsIgnored() {
        monitor.refresh()
        val previous = listener
        `when`(adapter.isEnabled).thenReturn(false)
        monitor.refresh()
        `when`(adapter.isEnabled).thenReturn(true)
        monitor.refresh()
        previous.onServiceConnected(BluetoothProfile.HEADSET, profile)
        verify(adapter).closeProfileProxy(BluetoothProfile.HEADSET, profile)
        assertEquals(BluetoothCallsStatus.CHECKING, monitor.state.value.status)
        val replacement = mock(BluetoothProfile::class.java)
        `when`(replacement.connectedDevices).thenReturn(emptyList())
        listener.onServiceConnected(BluetoothProfile.HEADSET, replacement)
        assertEquals(BluetoothCallsStatus.DISCONNECTED, monitor.state.value.status)
        monitor.close()
    }

    @Test fun repeatedRefreshDoesNotExtendPendingTimeout() {
        monitor.refresh()
        repeat(5) { monitor.refresh() }
        verify(handler, times(1)).postDelayed(any(Runnable::class.java), eq(5_000L))
        timeout.run()
        monitor.refresh()
        assertEquals(BluetoothCallsStatus.UNAVAILABLE, monitor.state.value.status)
        verify(adapter, times(1)).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET))
        monitor.close()
    }

    @Test fun callbackAfterCloseReleasesProxyWithoutChangingState() {
        monitor.refresh()
        monitor.close()
        val before = monitor.state.value
        listener.onServiceConnected(BluetoothProfile.HEADSET, profile)
        timeout.run()
        assertEquals(before, monitor.state.value)
        verify(adapter).closeProfileProxy(BluetoothProfile.HEADSET, profile)
    }
}
