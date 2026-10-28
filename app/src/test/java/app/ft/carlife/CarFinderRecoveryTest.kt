package app.ft.carlife

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.SystemClock
import app.ft.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.net.NetworkInterface
import java.net.InetAddress
import java.util.Collections
import java.util.Enumeration

@OptIn(ExperimentalCoroutinesApi::class)
class CarFinderRecoveryTest {
    @Test
    fun aGroupQueryCompletingAfterDisconnectionCannotResurrectTheGroup() = check { h ->
        var joined = 0
        h.finder.onJoined = { joined++ }
        h.broadcastConnection(true)
        val stale = h.groups.single()
        h.broadcastConnection(false)
        stale.onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(0, joined)

        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(1, joined)
    }

    @Test
    fun anExistingConnectionQueryFromBeforeDisconnectionIsIgnored() = check { h ->
        h.finder.checkExisting()
        val stale = h.connections.last()
        h.broadcastConnection(false)
        stale.onConnectionInfoAvailable(h.connectionInfo(true))
        assertTrue(h.groups.isEmpty())
    }

    @Test
    fun noGroupInfoDoesNotCountAsAJoinedGroup() = check { h ->
        var joined = 0
        h.finder.onJoined = { joined++ }
        h.broadcastConnection(true)
        h.groups.single().onGroupInfoAvailable(null)
        assertEquals(0, joined)
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(1, joined)
    }

    @Test
    fun disablingWifiDirectClearsTheLinkAndAllowsTheSameGroupToReconnect() = check { h ->
        var joined = 0
        var left = 0
        h.finder.onJoined = { joined++ }
        h.finder.onLeft = { left++ }
        h.broadcastConnection(true)
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(1, joined)

        h.broadcastEnabled(false)
        h.broadcastConnection(false)
        assertEquals(1, left)
        assertFalse(h.finder.inSight("CarLife"))
        h.broadcastConnection(true)
        assertEquals("disabled radio must ignore stale connected broadcasts", 1, h.groups.size)

        h.broadcastEnabled(true)
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(2, joined)
    }

    @Test
    fun cleanupFinishesBeforeDiscoveryAndNeverReusesAnAbsentPeer() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, h.cancels.size)
        verify(h.manager, never()).removeGroup(any(), any())

        h.broadcastPeers()
        assertFalse(h.finder.inSight("unrelated"))
        h.cancels.single().onSuccess()
        runCurrent()
        assertEquals(1, h.removals.size)
        verify(h.manager, never()).requestGroupInfo(any(), any())

        h.removals.single().onSuccess()
        runCurrent()
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        runCurrent()
        advanceTimeBy(249)
        runCurrent()
        assertEquals(1, h.groups.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.groups.size)

        h.visible = emptyList()
        h.groups.last().onGroupInfoAvailable(null)
        runCurrent()
        verify(h.manager, times(2)).requestPeers(any(), any())
        advanceTimeBy(2_499)
        runCurrent()
        verify(h.manager, times(2)).requestPeers(any(), any())
        advanceTimeBy(1)
        runCurrent()
        verify(h.manager, times(3)).requestPeers(any(), any())
        verify(h.manager).discoverPeers(any(), any())
        verify(h.manager, never()).connect(any(), any(), any())
        assertFalse(h.finder.inSight("CarLife"))

        h.visible = listOf(h.car)
        h.broadcastPeers()
        runCurrent()
        assertTrue(h.finder.inSight("unrelated"))
    }

    @Test
    fun stopInvalidatesAPendingCleanupCallback() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        val pending = h.cancels.single()
        h.finder.stop()
        clearInvocations(h.manager)
        pending.onSuccess()
        advanceTimeBy(10_000)
        runCurrent()
        verifyNoInteractions(h.manager)
    }

    @Test
    fun missingActionAndNetworkCallbacksBlockJoinsAndLateCompletionIsIgnored() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        val expired = h.cancels.single()
        advanceTimeBy(2_000)
        runCurrent()
        verify(h.manager, never()).removeGroup(any(), any())
        verify(h.manager, times(1)).requestPeers(any(), any())
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        val expiredNetwork = h.networks.single()
        expired.onSuccess()
        runCurrent()
        verify(h.manager, never()).removeGroup(any(), any())
        verify(h.manager, never()).connect(any(), any(), any())
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(2, h.cancels.size)
        expiredNetwork.onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.DISCONNECTED))
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        h.cancels.last().onSuccess()
        runCurrent()
        assertEquals(1, h.removals.size)
    }

    @Test
    fun busyCleanupWithActiveNetworkIsRetriedWithoutStartingRemovalOrDiscovery() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        h.cancels.single().onFailure(WifiP2pManager.BUSY)
        runCurrent()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.CONNECTING))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, h.cancels.size)
        verify(h.manager, never()).removeGroup(any(), any())
        verify(h.manager, never()).discoverPeers(any(), any())
    }

    @Test
    fun busyRemovalAfterCancellationResumesJoiningFromFreshPeersWhenNetworkIsIdle() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.finder.connectByName("CarLife")
            advanceTimeBy(5_000)
            runCurrent()
            h.cancels.single().onSuccess()
            runCurrent()
            h.removals.single().onFailure(WifiP2pManager.BUSY)
            runCurrent()
            h.groups.single().onGroupInfoAvailable(null)
            runCurrent()
            h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.FAILED))
            runCurrent()
            h.visible = listOf(h.car.apply { status = WifiP2pDevice.AVAILABLE })
            advanceTimeBy(2_500)
            runCurrent()
            verify(h.manager).connect(any(), any(), any())
            assertEquals("cleanup must finish instead of cancelling forever", 1, h.cancels.size)
        }
    }

    @Test
    fun busyCancellationWithIdleNetworkResumesDiscoveryWithoutReusingOldPeers() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        h.cancels.single().onFailure(WifiP2pManager.BUSY)
        runCurrent()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.DISCONNECTED))
        runCurrent()
        h.visible = emptyList()
        advanceTimeBy(2_500)
        runCurrent()
        verify(h.manager).discoverPeers(any(), any())
        verify(h.manager, never()).connect(any(), any(), any())
        assertFalse(h.finder.inSight("CarLife"))
    }

    @Test
    fun nullNetworkDoesNotConfirmCleanupAfterBusyCancellation() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        h.cancels.single().onFailure(WifiP2pManager.BUSY)
        runCurrent()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        WifiP2pManager.NetworkInfoListener::class.java
            .getMethod("onNetworkInfoAvailable", NetworkInfo::class.java)
            .invoke(h.networks.single(), null)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, h.cancels.size)
        verify(h.manager, never()).discoverPeers(any(), any())
    }

    @Test
    fun aGroupQueryArrivingAfterCleanupTimedOutCannotResumeDiscovery() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        h.cancels.single().onFailure(WifiP2pManager.BUSY)
        runCurrent()
        val expired = h.groups.single()
        advanceTimeBy(5_000)
        runCurrent()
        expired.onGroupInfoAvailable(null)
        runCurrent()
        assertTrue(h.networks.isEmpty())
        verify(h.manager, never()).discoverPeers(any(), any())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, h.cancels.size)
    }

    @Test
    fun genericCleanupErrorsStillRequireConfirmationThatNoGroupExists() = check { h ->
        advanceTimeBy(5_000)
        runCurrent()
        h.cancels.single().onFailure(WifiP2pManager.ERROR)
        runCurrent()
        h.removals.single().onFailure(WifiP2pManager.ERROR)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        h.visible = emptyList()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.IDLE))
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        verify(h.manager).discoverPeers(any(), any())
        assertFalse(h.finder.inSight("CarLife"))
    }

    private fun check(block: suspend TestScope.(Harness) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            mockStatic(NetworkInterface::class.java).use { network ->
                network.`when`<Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                    .thenReturn(Collections.emptyEnumeration())
                mockStatic(SystemClock::class.java).use { clock ->
                    clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { testScheduler.currentTime }
                    val h = Harness(this)
                    try {
                        h.start()
                        runCurrent()
                        block(h)
                    } finally {
                        h.finder.stop()
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Harness(scope: TestScope) {
        val manager = mock(WifiP2pManager::class.java)
        private val context = mock(Context::class.java)
        private val prefs = mock(Prefs::class.java)
        private val channel = mock(WifiP2pManager.Channel::class.java)
        val cancels = mutableListOf<WifiP2pManager.ActionListener>()
        val removals = mutableListOf<WifiP2pManager.ActionListener>()
        val groups = mutableListOf<WifiP2pManager.GroupInfoListener>()
        val connections = mutableListOf<WifiP2pManager.ConnectionInfoListener>()
        val networks = mutableListOf<WifiP2pManager.NetworkInfoListener>()
        val car = WifiP2pDevice().apply {
            deviceName = "CarLife"
            deviceAddress = "02:00:00:00:00:01"
            status = WifiP2pDevice.INVITED
        }
        var visible: List<WifiP2pDevice> = listOf(car)
        private lateinit var receiver: BroadcastReceiver
        val finder: CarFinder

        init {
            `when`(context.getSystemService(Context.WIFI_P2P_SERVICE)).thenReturn(manager)
            `when`(manager.initialize(any(), any(), any())).thenReturn(channel)
            `when`(prefs.carP2pName).thenReturn("CarLife")
            `when`(prefs.carBtAddress).thenReturn("")
            `when`(prefs.carWpsPin).thenReturn("")
            `when`(prefs.carJoinTimeoutSeconds).thenReturn(5)
            doAnswer {
                val peers = mock(WifiP2pDeviceList::class.java)
                `when`(peers.deviceList).thenReturn(visible.toList())
                it.getArgument<WifiP2pManager.PeerListListener>(1).onPeersAvailable(peers)
                null
            }.`when`(manager).requestPeers(any(), any())
            doAnswer { cancels += it.getArgument<WifiP2pManager.ActionListener>(1); null }
                .`when`(manager).cancelConnect(any(), any())
            doAnswer { removals += it.getArgument<WifiP2pManager.ActionListener>(1); null }
                .`when`(manager).removeGroup(any(), any())
            doAnswer { groups += it.getArgument<WifiP2pManager.GroupInfoListener>(1); null }
                .`when`(manager).requestGroupInfo(any(), any())
            doAnswer { connections += it.getArgument<WifiP2pManager.ConnectionInfoListener>(1); null }
                .`when`(manager).requestConnectionInfo(any(), any())
            doAnswer { networks += it.getArgument<WifiP2pManager.NetworkInfoListener>(1); null }
                .`when`(manager).requestNetworkInfo(any(), any())
            finder = CarFinder(context, prefs, scope.backgroundScope)
        }

        fun start() {
            finder.start()
            val capture = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
            verify(context).registerReceiver(capture.capture(), any())
            receiver = capture.value
            broadcastPeers()
        }

        fun broadcastPeers() {
            val intent = mock(Intent::class.java)
            `when`(intent.action).thenReturn(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            receiver.onReceive(context, intent)
        }

        fun connectionInfo(formed: Boolean) = WifiP2pInfo().apply {
            groupFormed = formed
            groupOwnerAddress = if (formed) InetAddress.getByName("192.168.49.1") else null
        }

        @Suppress("DEPRECATION")
        fun network(state: NetworkInfo.DetailedState): NetworkInfo = mock(NetworkInfo::class.java).also {
            `when`(it.detailedState).thenReturn(state)
        }

        @Suppress("DEPRECATION")
        fun broadcastConnection(formed: Boolean) {
            val intent = mock(Intent::class.java)
            `when`(intent.action).thenReturn(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            `when`(intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO))
                .thenReturn(connectionInfo(formed))
            receiver.onReceive(context, intent)
        }

        fun broadcastEnabled(enabled: Boolean) {
            val intent = mock(Intent::class.java)
            `when`(intent.action).thenReturn(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            `when`(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)).thenReturn(
                if (enabled) WifiP2pManager.WIFI_P2P_STATE_ENABLED else WifiP2pManager.WIFI_P2P_STATE_DISABLED
            )
            receiver.onReceive(context, intent)
        }
    }
}
