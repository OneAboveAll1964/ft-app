package app.ft.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class CarBluetoothReconnectTest {
    private class Fixture(scope: TestScope) {
        val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }
        val adapter = mock(BluetoothAdapter::class.java)
        val prefs = mock(Prefs::class.java)
        val car = mock(BluetoothDevice::class.java)
        val a2dp = mock(BluetoothProfile::class.java)
        val headset = mock(BluetoothProfile::class.java)
        val listeners = mutableMapOf<Int, BluetoothProfile.ServiceListener>()
        var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        var enabled = true
        var permission = true
        var radioOn = true
        var discovering = false
        var address = "AA:BB:CC:DD:EE:FF"
        val requests = mutableListOf<Pair<BluetoothProfile, BluetoothDevice>>()
        val monitor = CarBluetoothReconnect(context, prefs, adapter, scope.backgroundScope, { permission }) { profile, device ->
            requests += profile to device
            true
        }

        init {
            `when`(prefs.carBtReconnect).thenAnswer { enabled }
            `when`(prefs.carBtAddress).thenAnswer { address }
            `when`(prefs.carBtName).thenReturn("")
            doAnswer { address = it.getArgument(0); null }.`when`(prefs).carBtAddress = anyString()
            doAnswer { preferenceListener = it.getArgument(0); null }.`when`(prefs).registerListener(
                any<SharedPreferences.OnSharedPreferenceChangeListener>() ?: SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> }
            )
            `when`(adapter.isEnabled).thenAnswer { radioOn }
            `when`(adapter.isDiscovering).thenAnswer { discovering }
            `when`(adapter.startDiscovery()).thenAnswer { discovering = true; true }
            `when`(adapter.cancelDiscovery()).thenAnswer { discovering = false; true }
            `when`(adapter.bondedDevices).thenReturn(setOf(car))
            `when`(car.address).thenReturn(address)
            `when`(car.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            `when`(a2dp.connectedDevices).thenReturn(emptyList())
            `when`(headset.connectedDevices).thenReturn(emptyList())
            doAnswer { listeners[it.getArgument(2)] = it.getArgument(1); true }.`when`(adapter)
                .getProfileProxy(eq(context), any(BluetoothProfile.ServiceListener::class.java), anyInt())
        }

        fun start() {
            monitor.start()
            listeners[BluetoothProfile.A2DP]?.onServiceConnected(BluetoothProfile.A2DP, a2dp)
            listeners[BluetoothProfile.HEADSET]?.onServiceConnected(BluetoothProfile.HEADSET, headset)
        }
        fun connect(id: Int = BluetoothProfile.A2DP, device: BluetoothDevice = car) =
            monitor.onConnectionChanged(id, device, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
        fun drop(id: Int = BluetoothProfile.A2DP, device: BluetoothDevice = car) =
            monitor.onConnectionChanged(id, device, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED)
    }

    @Test fun disabledSettingDoesNotAcquireProfilesOrSearch() = runTest {
        val f = Fixture(this)
        f.enabled = false
        f.start()
        f.drop()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).getProfileProxy(any(), any(), anyInt())
        verify(f.adapter, never()).startDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun rememberedPairedCarAloneDoesNotTriggerRecovery() = runTest {
        val f = Fixture(this)
        f.start()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun lostConnectionWithoutFreshSightingNeverConnectsAndStopsAfterThreeSearches() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        verify(f.adapter, times(3)).startDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun freshSightingConnectsOnlyLostProfileOfExactCar() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(0, f.requests.size)
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        verify(f.adapter).cancelDiscovery()
        f.connect()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, times(1)).startDiscovery()
        f.monitor.close()
    }

    @Test fun anotherDeviceCannotTriggerRecoveryOrProveCarIsNearby() = runTest {
        val f = Fixture(this)
        val other = mock(BluetoothDevice::class.java)
        `when`(other.address).thenReturn("00:11:22:33:44:55")
        `when`(other.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        f.start(); f.connect(device = other); f.drop(device = other)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(other)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("AA:BB:CC:DD:EE:FF", f.address)
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun nativeRecoveryDuringGracePeriodAvoidsSearch() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent()
        f.connect()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        f.monitor.close()
    }

    @Test fun bothLostProfilesAreRecoveredButDuplicateDropsDoNotResetBudget() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.connect(BluetoothProfile.HEADSET)
        f.drop(); f.drop(BluetoothProfile.HEADSET); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        repeat(3) { round ->
            f.monitor.onDeviceFound(f.car); runCurrent()
            if (round < 2) { advanceTimeBy(23_000); runCurrent() }
        }
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, times(3)).startDiscovery()
        assertEquals(3, f.requests.count { it.first === f.a2dp })
        assertEquals(3, f.requests.count { it.first === f.headset })
        f.monitor.close()
    }

    @Test fun disablingSettingDuringSearchCancelsImmediatelyAndCanBeEnabledAgain() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.enabled = false
        f.preferenceListener!!.onSharedPreferenceChanged(null, "carBtReconnect"); runCurrent()
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        verify(f.adapter).cancelDiscovery()
        verify(f.adapter).closeProfileProxy(BluetoothProfile.A2DP, f.a2dp)
        f.enabled = true
        f.preferenceListener!!.onSharedPreferenceChanged(null, "carBtReconnect"); runCurrent()
        verify(f.adapter, times(2)).getProfileProxy(any(), any(), eq(BluetoothProfile.A2DP))
        f.monitor.close()
    }

    @Test fun bluetoothOffCancelsSearchWithoutTryingToEnableRadio() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.radioOn = false; f.monitor.refresh()
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        verify(f.adapter).cancelDiscovery()
        verify(f.adapter, never()).enable()
        f.monitor.close()
    }

    @Test fun permissionMissingOrBondRemovedPreventsConnection() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.permission = false
        f.monitor.onDeviceFound(f.car); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        f.permission = true
        `when`(f.car.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        f.drop(); runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun closingMonitorRejectsLateDiscoveryAndProfileCallbacks() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        val callback = f.listeners.getValue(BluetoothProfile.HEADSET)
        f.monitor.close()
        f.monitor.onDeviceFound(f.car)
        val lateProxy = mock(BluetoothProfile::class.java)
        callback.onServiceConnected(BluetoothProfile.HEADSET, lateProxy)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.HEADSET, lateProxy)
        f.monitor.close()
        verify(f.context, times(1)).unregisterReceiver(any())
    }

    @Test fun changingLastCarCancelsOldTargetAndOldSightingsAreIgnored() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.address = "00:11:22:33:44:55"
        f.monitor.refresh()
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun staleSightingIsNotReusedOnNextAttempt() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertEquals(1, f.requests.size)
        verify(f.adapter, times(3)).startDiscovery()
        f.monitor.close()
    }

    @Test fun discoveryOwnedByAnotherAppIsNotCancelled() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        f.discovering = true
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        verify(f.adapter, never()).cancelDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun cancellationOfPreviousEpisodeDoesNotClearNewRecovery() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        // Complete one recovery and lose the link again before the cancelled coroutine unwinds.
        f.connect(); f.drop(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun actualConnectedCarAudioDeviceIsRememberedForIccoaToo() = runTest {
        val f = Fixture(this)
        f.address = ""
        val carClass = mock(BluetoothClass::class.java)
        `when`(carClass.deviceClass).thenReturn(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)
        `when`(f.car.bluetoothClass).thenReturn(carClass)
        f.start(); f.connect()
        assertEquals("AA:BB:CC:DD:EE:FF", f.address)
        f.drop(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }
}
