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
        var projectionAudio = false
        var retainSuppressedA2dp = true
        var address = "AA:BB:CC:DD:EE:FF"
        val requests = mutableListOf<Pair<BluetoothProfile, BluetoothDevice>>()
        val monitor = CarBluetoothReconnect(context, prefs, adapter, scope.backgroundScope, { permission }, { projectionAudio }, { retainSuppressedA2dp }) { profile, device ->
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

    @Test fun confirmedCarLifeVehicleCannotBeReplacedByAnotherCarAudioDevice() = runTest {
        val f = Fixture(this)
        `when`(f.prefs.carProtocol).thenReturn(CarProtocol.CARLIFE)
        `when`(f.prefs.carLifeVehicleAddress).thenReturn(f.address)
        val other = mock(BluetoothDevice::class.java)
        `when`(other.address).thenReturn("00:11:22:33:44:55")
        `when`(other.name).thenReturn("Toyota")
        `when`(other.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        val carClass = mock(BluetoothClass::class.java)
        `when`(carClass.deviceClass).thenReturn(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)
        `when`(other.bluetoothClass).thenReturn(carClass)
        `when`(f.adapter.bondedDevices).thenReturn(setOf(f.car, other))
        f.start(); f.connect(); f.connect(device = other)
        assertEquals("AA:BB:CC:DD:EE:FF", f.address)
        f.drop(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(other); runCurrent()
        assertEquals(0, f.requests.size)
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun carLifeBindingDoesNotChangeIccoaBluetoothLearning() = runTest {
        val f = Fixture(this)
        f.address = ""
        `when`(f.prefs.carProtocol).thenReturn(CarProtocol.ICCOA)
        `when`(f.prefs.carLifeVehicleAddress).thenReturn("00:11:22:33:44:55")
        val carClass = mock(BluetoothClass::class.java)
        `when`(carClass.deviceClass).thenReturn(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)
        `when`(f.car.bluetoothClass).thenReturn(carClass)
        f.start(); f.connect()
        assertEquals("AA:BB:CC:DD:EE:FF", f.address)
        f.monitor.close()
    }

    @Test fun projectionAudioDoesNotRecoverA2dpButStillRecoversCalls() = runTest {
        val f = Fixture(this)
        f.projectionAudio = true
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        assertEquals(0, f.requests.size)
        f.connect(BluetoothProfile.HEADSET); f.drop(BluetoothProfile.HEADSET)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.headset to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun switchingToProjectionAudioCancelsA2dpSearchImmediately() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.projectionAudio = true; f.monitor.refresh()
        verify(f.adapter).cancelDiscovery()
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        verify(f.adapter, times(1)).startDiscovery()
        f.monitor.close()
    }

    @Test fun switchingRouteDuringSharedSearchKeepsOnlyCallRecovery() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.connect(BluetoothProfile.HEADSET)
        f.drop(); f.drop(BluetoothProfile.HEADSET)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.projectionAudio = true; f.monitor.refresh()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.headset to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun releasingProjectionRouteCanRecoverPreviouslyLostA2dp() = runTest {
        val f = Fixture(this)
        f.projectionAudio = true
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        f.projectionAudio = false
        f.preferenceListener!!.onSharedPreferenceChanged(null, "soundOverBluetooth"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun routeChangeWithoutObservedDisconnectDoesNotConnectPairedCar() = runTest {
        val f = Fixture(this)
        f.projectionAudio = true; f.start()
        f.projectionAudio = false; f.monitor.refresh()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun briefConnectionsDoNotResetThreeAttemptLimit() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        repeat(3) {
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            f.monitor.onDeviceFound(f.car); runCurrent()
            assertEquals(it + 1, f.requests.size)
            f.connect(); runCurrent(); advanceTimeBy(1_000); runCurrent(); f.drop()
        }
        // Repeated broadcasts and refreshes must not open another recovery cycle.
        f.drop(); f.monitor.refresh()
        advanceTimeBy(180_000); runCurrent()
        assertEquals(3, f.requests.size)
        verify(f.adapter, times(3)).startDiscovery()
        f.monitor.close()
    }

    @Test fun fullMinuteOfStableConnectionRestoresRecoveryBudget() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        repeat(3) {
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            f.monitor.onDeviceFound(f.car); runCurrent()
            f.connect(); runCurrent(); advanceTimeBy(1_000); runCurrent(); f.drop()
        }
        f.connect(); runCurrent(); advanceTimeBy(60_000); runCurrent(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(4, f.requests.size)
        f.monitor.close()
    }

    @Test fun exhaustedA2dpBudgetDoesNotBlockCallRecovery() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        verify(f.adapter, times(3)).startDiscovery()
        f.connect(BluetoothProfile.HEADSET); f.drop(BluetoothProfile.HEADSET)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.headset to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun proxyAlreadyConnectedDuringGraceDoesNotStartNewDiscovery() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        `when`(f.a2dp.getConnectionState(f.car)).thenReturn(BluetoothProfile.STATE_CONNECTED)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun refreshingDuringLastSearchDoesNotCancelFinalAttempt() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        repeat(3) { round ->
            f.monitor.refresh()
            f.monitor.onDeviceFound(f.car); runCurrent()
            assertEquals(round + 1, f.requests.size)
            if (round < 2) { advanceTimeBy(23_000); runCurrent() }
        }
        f.monitor.close()
    }

    @Test fun callProfileLostLateStillGetsItsOwnThreeAttempts() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.connect(BluetoothProfile.HEADSET); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        repeat(3) { round ->
            f.monitor.onDeviceFound(f.car); runCurrent()
            if (round < 2) { advanceTimeBy(23_000); runCurrent() }
        }
        f.drop(BluetoothProfile.HEADSET)
        repeat(3) {
            advanceTimeBy(23_000); runCurrent()
            f.monitor.onDeviceFound(f.car); runCurrent()
        }
        assertEquals(3, f.requests.count { it.first === f.a2dp })
        assertEquals(3, f.requests.count { it.first === f.headset })
        f.monitor.close()
    }

    @Test fun exhaustedCallBudgetDoesNotKeepSuppressedA2dpSearchAlive() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(BluetoothProfile.HEADSET); f.drop(BluetoothProfile.HEADSET)
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        verify(f.adapter, times(3)).startDiscovery()
        f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(true, f.discovering)
        f.projectionAudio = true; f.monitor.refresh()
        assertEquals(false, f.discovering)
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(0, f.requests.size)
        f.monitor.close()
    }

    @Test fun unavailableProfileWaitsForReplacementWithoutSpendingBudget() = runTest {
        val f = Fixture(this)
        f.start(); f.connect()
        val listener = f.listeners.getValue(BluetoothProfile.A2DP)
        listener.onServiceDisconnected(BluetoothProfile.A2DP)
        f.drop()
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        verify(f.a2dp, never()).getConnectionState(f.car)
        val replacement = mock(BluetoothProfile::class.java)
        `when`(replacement.connectedDevices).thenReturn(emptyList())
        listener.onServiceConnected(BluetoothProfile.A2DP, replacement)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        repeat(3) { round ->
            f.monitor.onDeviceFound(f.car); runCurrent()
            if (round < 2) { advanceTimeBy(23_000); runCurrent() }
        }
        assertEquals(3, f.requests.count { it.first === replacement })
        f.monitor.close()
    }

    @Test fun disconnectingStateAloneDoesNotProvePreviousConnection() = runTest {
        val f = Fixture(this)
        f.start()
        f.monitor.onConnectionChanged(BluetoothProfile.A2DP, f.car,
            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        verify(f.adapter, never()).startDiscovery()
        f.connect()
        f.monitor.onConnectionChanged(BluetoothProfile.A2DP, f.car,
            BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_CONNECTED)
        f.monitor.onConnectionChanged(BluetoothProfile.A2DP, f.car,
            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun unneededA2dpLossIsNotReplayedAfterProjectionEnds() = runTest {
        val f = Fixture(this)
        f.retainSuppressedA2dp = false
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.projectionAudio = true; f.monitor.refresh()
        assertEquals(false, f.discovering)
        advanceTimeBy(2_400_000); runCurrent()
        // Repeated losses while CarLife owns music must not leave a deferred reconnection either.
        f.drop(); f.projectionAudio = false; f.monitor.refresh()
        advanceTimeBy(120_000); runCurrent()
        verify(f.adapter, times(1)).startDiscovery()
        assertEquals(0, f.requests.size)
        // A genuinely new loss after the route was released is still recoverable.
        f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
    }

    @Test fun profileServiceLossCancelsItsActiveSearchAndRebindResumesIt() = runTest {
        val f = Fixture(this)
        f.start(); f.connect(); f.drop()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        val listener = f.listeners.getValue(BluetoothProfile.A2DP)
        listener.onServiceDisconnected(BluetoothProfile.A2DP)
        assertEquals(false, f.discovering)
        f.monitor.onDeviceFound(f.car)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.requests.size)
        listener.onServiceConnected(BluetoothProfile.A2DP, f.a2dp)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        f.monitor.onDeviceFound(f.car); runCurrent()
        assertEquals(listOf(f.a2dp to f.car), f.requests)
        f.monitor.close()
        verify(f.adapter).closeProfileProxy(BluetoothProfile.A2DP, f.a2dp)
    }
}
