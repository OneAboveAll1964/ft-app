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
import app.ft.core.DiagLog
import app.ft.core.Bytes
import app.ft.core.Prefs
import app.ft.core.ProtoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
    fun groupMetadataCanArriveAfterTheSelectedJoinDeadline() = check { h ->
        h.broadcastEnabled(false)
        h.timeout(60)
        h.broadcastEnabled(true)
        h.broadcastPeers()
        runCurrent()
        var joined = 0
        h.finder.onJoined = { joined++ }
        advanceTimeBy(59_500)
        h.broadcastConnection(true)
        val pending = h.groups.single()
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        assertTrue("a formed group gets time for its metadata", h.cancels.isEmpty())
        pending.onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(1, joined)
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(h.cancels.isEmpty())
    }

    @Test
    fun repeatedFormedBroadcastsCannotExtendTheMetadataDeadline() = check { h ->
        advanceTimeBy(4_500)
        h.broadcastConnection(true)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(null)
        advanceTimeBy(999)
        runCurrent()
        assertTrue(h.cancels.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, h.cancels.size)
        var joined = 0
        h.finder.onJoined = { joined++ }
        h.groups.first().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals("a callback after metadata expiry is stale", 0, joined)
    }

    @Test
    fun missingOwnerAddressIsQueriedAgainWithinTheMetadataWindow() = check { h ->
        advanceTimeBy(4_500)
        h.broadcastConnection(true, ownerAddress = false)
        runCurrent()
        assertTrue(h.groups.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(h.cancels.isEmpty())
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true))
        var joined = 0
        h.finder.onJoined = { joined++ }
        h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertEquals(1, joined)
    }

    @Test
    fun anExistingGroupWithoutMetadataAlsoHasABoundedWait() = check { h ->
        h.broadcastEnabled(false)
        advanceTimeBy(60_000)
        h.visible = emptyList()
        h.broadcastEnabled(true)
        var timedOut = 0
        h.finder.onJoinTimeout = { timedOut = it }
        h.broadcastConnection(true, ownerAddress = false)
        runCurrent()
        advanceTimeBy(JoinWatch.GROUP_DETAILS_MS)
        runCurrent()
        assertEquals(1, h.cancels.size)
        assertEquals(5, timedOut)
    }

    @Test
    fun anExistingGroupWaitingForMetadataDoesNotRestartDiscovery() = check { h ->
        h.broadcastEnabled(false)
        h.visible = emptyList()
        h.broadcastEnabled(true)
        clearInvocations(h.manager)
        h.broadcastConnection(true, ownerAddress = false)
        h.finder.search()
        runCurrent()
        advanceTimeBy(JoinWatch.GROUP_DETAILS_MS - 1)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        verify(h.manager, never()).discoverServices(any(), any())
        assertTrue(h.cancels.isEmpty())
    }

    @Test
    fun recoveryWaitsForPreparationBeforeStartingItsBackoff() = check { h ->
        val ready = CompletableDeferred<Unit>()
        var preparing = false
        h.finder.onRetryReady = { preparing = true; ready.await() }
        h.finder.retry()
        runCurrent()
        h.cancels.single().onSuccess()
        runCurrent()
        h.removals.single().onSuccess()
        runCurrent()
        h.visible = emptyList()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        assertTrue(preparing)
        advanceTimeBy(10_000)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        ready.complete(Unit)
        runCurrent()
        advanceTimeBy(2_499)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        advanceTimeBy(1)
        runCurrent()
        verify(h.manager).discoverPeers(any(), any())
    }

    @Test
    fun aLiveGroupPreparationIsCoalescedAndCancelledOnStop() = check { h ->
        var started = 0
        var cancelled = false
        h.finder.onRetryReady = {
            started++
            try { awaitCancellation() } finally { cancelled = true }
        }
        h.broadcastConnection(true)
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        h.finder.retry()
        runCurrent()
        h.finder.retry()
        runCurrent()
        assertEquals(1, started)
        assertTrue(h.cancels.isEmpty())
        h.finder.stop()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun anInitiallyAvailablePeerCanTakeFourSecondsToFormAGroup() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.broadcastEnabled(false)
            h.car.status = WifiP2pDevice.AVAILABLE
            h.broadcastEnabled(true)
            h.broadcastPeers()
            h.finder.connectByName("CarLife")
            val callback = ArgumentCaptor.forClass(WifiP2pManager.ActionListener::class.java)
            verify(h.manager).connect(any(), any(), callback.capture())
            callback.value.onSuccess()
            runCurrent()

            // AVAILABLE is also the initial discovery snapshot, not a rejection.
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue("an unchanged discovery snapshot must not cancel the join", h.cancels.isEmpty())
            h.car.status = WifiP2pDevice.INVITED
            h.broadcastPeers()
            advanceTimeBy(1_000)
            runCurrent()
            var joined = 0
            h.finder.onJoined = { joined++ }
            h.broadcastConnection(true)
            h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
            assertEquals(1, joined)
            assertTrue(h.cancels.isEmpty())
        }
    }

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

        // A broadcast during cleanup must not start another invitation watchdog.
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

        // A newly discovered pending invitation can be handled normally.
        h.visible = listOf(h.car)
        h.broadcastPeers()
        runCurrent()
        assertTrue(h.finder.inSight("unrelated"))
    }

    @Test
    fun automaticRetryPreparesTheCarAfterCleanupAndAcceptsANonOwnerPeer() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            val sent = mutableListOf<ByteArray>()
            val wireless = CarWirelessSetup(
                send = { sent.add(it) },
                onCarWifiName = { h.finder.connectByName(it) },
                onCarReady = { h.finder.carReady() }
            )
            wireless.wifiDirectReady("192.168.49.23")
            h.finder.onRetryReady = { groupKept ->
                assertFalse(groupKept)
                wireless.restart(keepIp = groupKept)
            }
            var joined = 0
            h.finder.onJoined = { joined++ }
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("must not prepare the car during cancellation", sent.isEmpty())
            h.cancels.single().onSuccess()
            runCurrent()
            h.removals.single().onSuccess()
            runCurrent()
            assertTrue("must wait for confirmed teardown", sent.isEmpty())
            h.groups.single().onGroupInfoAvailable(null)
            runCurrent()
            assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST), sent.map { Bytes.u32(it, 4) })

            // Fresh Bluetooth replies can arrive immediately. They must not race
            // the recovery pause or reuse a stale peer or the old group's IP.
            wireless.feed(CarLifeFraming.cmd(CarWirelessSetup.HU_WIRELESS_INFO))
            wireless.feed(CarLifeFraming.cmd(CarWirelessSetup.HU_TARGET_INFO, ProtoWriter().string(1, "CarLife").toByteArray()))
            wireless.feed(CarLifeFraming.cmd(CarWirelessSetup.HU_IP_REQUEST))
            h.visible = listOf(h.car.apply { status = WifiP2pDevice.AVAILABLE })
            assertFalse(h.car.isGroupOwner)
            h.broadcastPeers()
            runCurrent()
            assertEquals(listOf(CarWirelessSetup.MD_WIRELESS_INFO_REQUEST, CarWirelessSetup.MD_TARGET_INFO_REQUEST), sent.map { Bytes.u32(it, 4) })
            verify(h.manager, never()).connect(any(), any(), any())
            advanceTimeBy(2_499)
            runCurrent()
            verify(h.manager, never()).connect(any(), any(), any())
            advanceTimeBy(1)
            runCurrent()
            verify(h.manager).connect(any(), any(), any())
            h.broadcastConnection(true)
            h.groups.last().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
            assertEquals(1, joined)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("a successful group ends recovery", 1, h.cancels.size)
        }
    }

    @Test
    fun aBusyConnectMustFinishCleanupBeforePreparingTheCarAgain() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            var prepared = 0
            h.finder.onRetryReady = { prepared++ }
            h.broadcastEnabled(false)
            h.visible = listOf(h.car.apply { status = WifiP2pDevice.AVAILABLE })
            h.broadcastEnabled(true)
            h.broadcastPeers()
            h.finder.connectByName("CarLife")
            val capture = ArgumentCaptor.forClass(WifiP2pManager.ActionListener::class.java)
            verify(h.manager).connect(any(), any(), capture.capture())
            capture.value.onFailure(WifiP2pManager.BUSY)
            runCurrent()
            assertEquals(1, h.cancels.size)
            assertEquals(0, prepared)
            h.cancels.single().onSuccess()
            runCurrent()
            h.removals.single().onSuccess()
            runCurrent()
            assertEquals(0, prepared)
            h.groups.single().onGroupInfoAvailable(null)
            runCurrent()
            assertEquals(1, prepared)
        }
    }

    @Test
    fun manualRetryCancelsAnInvitationBeforeItsTimeoutAndJoinsAFreshPeer() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            var timeouts = 0
            h.finder.onJoinTimeout = { timeouts++ }
            advanceTimeBy(1_000)
            h.finder.retry()
            runCurrent()
            assertEquals(1, h.cancels.size)
            verify(h.manager, never()).connect(any(), any(), any())

            h.cancels.single().onSuccess()
            runCurrent()
            h.removals.single().onSuccess()
            runCurrent()
            h.visible = listOf(h.car.apply { status = WifiP2pDevice.AVAILABLE })
            h.groups.single().onGroupInfoAvailable(null)
            runCurrent()
            advanceTimeBy(2_500)
            runCurrent()
            verify(h.manager).connect(any(), any(), any())
            // The invitation's original five-second watchdog must be gone.
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(0, timeouts)
            assertEquals(1, h.cancels.size)
        }
    }

    @Test
    fun repeatedManualRetryDoesNotOverlapCleanupOrJoinAnOldPeer() = check { h ->
        var prepared = 0
        h.finder.onRetryReady = { prepared++ }
        h.finder.retry()
        runCurrent()
        h.finder.retry()
        h.broadcastPeers()
        runCurrent()
        assertEquals(1, h.cancels.size)
        assertEquals(0, prepared)
        h.cancels.single().onSuccess()
        runCurrent()
        h.removals.single().onSuccess()
        runCurrent()
        h.visible = emptyList()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        assertEquals(1, prepared)
        advanceTimeBy(2_500)
        runCurrent()
        verify(h.manager, never()).connect(any(), any(), any())
        verify(h.manager).discoverPeers(any(), any())
    }

    @Test
    fun manualRetryIgnoresThePreviousConnectCallback() = check { h ->
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.broadcastEnabled(false)
            h.visible = listOf(h.car.apply { status = WifiP2pDevice.AVAILABLE })
            h.broadcastEnabled(true)
            h.broadcastPeers()
            h.finder.connectByName("CarLife")
            val capture = ArgumentCaptor.forClass(WifiP2pManager.ActionListener::class.java)
            verify(h.manager).connect(any(), any(), capture.capture())
            h.finder.retry()
            runCurrent()
            capture.value.onFailure(WifiP2pManager.BUSY)
            runCurrent()
            assertEquals(1, h.cancels.size)
            verify(h.manager, never()).discoverPeers(any(), any())
        }
    }

    @Test
    fun manualRetryKeepsAnEstablishedGroup() = check { h ->
        var joined = 0
        val prepared = mutableListOf<Boolean>()
        h.finder.onRetryReady = { prepared += it }
        h.finder.onJoined = { joined++ }
        h.broadcastConnection(true)
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        h.finder.retry()
        runCurrent()
        assertEquals(1, joined)
        assertEquals(listOf(true), prepared)
        assertTrue(h.cancels.isEmpty())
        assertTrue(h.removals.isEmpty())
        verify(h.manager, never()).connect(any(), any(), any())
    }

    @Test
    fun stopInvalidatesAPendingCleanupCallback() = check { h ->
        var prepared = 0
        h.finder.onRetryReady = { prepared++ }
        advanceTimeBy(5_000)
        runCurrent()
        val pending = h.cancels.single()
        h.finder.stop()
        clearInvocations(h.manager)
        pending.onSuccess()
        advanceTimeBy(10_000)
        runCurrent()
        verifyNoInteractions(h.manager)
        assertEquals(0, prepared)
    }

    @Test
    fun stoppingAPendingInvitationCancelsAndroidBeforeClosingTheChannel() = check { h ->
        h.finder.stop()
        val order = inOrder(h.manager, h.channel)
        order.verify(h.manager).cancelConnect(any(), isNull())
        order.verify(h.channel).close()
        verify(h.manager, never()).removeGroup(any(), any())
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.cancels.isEmpty())
    }

    @Test
    fun stoppingAnEstablishedGroupKeepsItForTheNextStart() = check { h ->
        h.broadcastConnection(true)
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        h.finder.stop()
        verify(h.manager, never()).cancelConnect(any(), any())
        verify(h.manager, never()).removeGroup(any(), any())
        verify(h.channel).close()
    }

    @Test
    fun busyDiscoveryRecoversAnInvitationLeftByAnEarlierService() = check { h ->
        h.busyDiscovery()
        advanceTimeBy(10_000)
        runCurrent()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(false))
        runCurrent()
        h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.CONNECTING))
        runCurrent()
        assertEquals(1, h.cancels.size)
        var prepared = 0
        h.finder.onRetryReady = { kept -> assertFalse(kept); prepared++ }
        clearInvocations(h.manager)
        advanceTimeBy(1_000)
        runCurrent()
        verify(h.manager, never()).discoverPeers(any(), any())
        h.cancels.single().onSuccess()
        runCurrent()
        h.removals.single().onSuccess()
        runCurrent()
        h.groups.single().onGroupInfoAvailable(null)
        runCurrent()
        assertEquals(1, prepared)
        doAnswer { it.getArgument<WifiP2pManager.ActionListener>(1).onSuccess(); null }
            .`when`(h.manager).discoverPeers(any(), any())
        advanceTimeBy(2_500)
        runCurrent()
        verify(h.manager).discoverPeers(any(), any())
        verify(h.manager, never()).connect(any(), any(), any())
        assertEquals(0, h.finder.busyStreak)
    }

    @Test
    fun busyDiscoveryAdoptsAnAlreadyFormedGroupWithoutCancellingIt() = check { h ->
        h.busyDiscovery()
        advanceTimeBy(10_000)
        runCurrent()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true))
        runCurrent()
        h.groups.single().onGroupInfoAvailable(mock(WifiP2pGroup::class.java))
        assertTrue(h.finder.joined)
        assertTrue(h.cancels.isEmpty())
        assertTrue(h.networks.isEmpty())
    }

    @Test
    fun busyDiscoveryDoesNotCancelAnIdleNetwork() = check { h ->
        h.busyDiscovery()
        advanceTimeBy(10_000)
        runCurrent()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(false))
        runCurrent()
        h.networks.single().onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.DISCONNECTED))
        runCurrent()
        assertTrue(h.cancels.isEmpty())
        assertTrue(h.removals.isEmpty())
    }

    @Test
    fun busyDiscoveryCannotCancelAJoinStartedWhileTheStateQueryWasPending() = check { h ->
        h.busyDiscovery()
        advanceTimeBy(10_000)
        runCurrent()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(false))
        runCurrent()
        val oldNetwork = h.networks.single()
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.finder.connect(CarFinder.Peer("CarLife", h.car.deviceAddress, WifiP2pDevice.AVAILABLE, false))
        }
        oldNetwork.onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.CONNECTING))
        runCurrent()
        assertTrue(h.cancels.isEmpty())
        verify(h.manager).connect(any(), any(), any())
    }

    @Test
    fun busyDiscoveryIgnoresAStateQueryCompletedAfterStop() = check { h ->
        h.busyDiscovery()
        advanceTimeBy(10_000)
        runCurrent()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(false))
        runCurrent()
        val oldNetwork = h.networks.single()
        h.finder.stop()
        clearInvocations(h.manager)
        oldNetwork.onNetworkInfoAvailable(h.network(NetworkInfo.DetailedState.CONNECTING))
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
        // The Android JVM stub does not initialize WifiP2pConfig.wps.
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
        // The Android callback is annotated non-null, but an unexpected empty
        // response must still not allow another join. Reflection simulates it.
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

    @Test
    fun existingConnectedGroupRetainsTheBluetoothCallerForFirstConfirmation() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.CONNECTED
        h.finder.beginBluetoothExchange("64:FE:15:28:C8:E4")
        h.finder.connectByName("CarLife")
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true))
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals(h.car.deviceAddress, h.finder.connectedPeerAddress)
        assertEquals("64:FE:15:28:C8:E4", h.finder.connectedBluetoothAddress)
    }

    @Test
    fun lockedVehicleRejectsAnExistingForeignGroupEvenWithTheSameName() = check { h ->
        h.broadcastEnabled(false)
        `when`(h.prefs.carLifeVehicleAddress).thenReturn("64:FE:15:28:C8:E4")
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.broadcastConnection(true)
        val other = WifiP2pDevice().apply {
            deviceName = "CarLife"
            deviceAddress = "02:00:00:00:00:99"
        }
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(other)
        h.groups.last().onGroupInfoAvailable(group)
        assertFalse(h.finder.joined)
        assertEquals("", h.finder.connectedPeerAddress)
    }

    @Test
    fun lockedVehicleStopsTrustingAnInterfaceWhenAndroidReplacesItsGroup() = check { h ->
        h.broadcastEnabled(false)
        `when`(h.prefs.carLifeVehicleAddress).thenReturn("64:FE:15:28:C8:E4")
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        h.car.status = WifiP2pDevice.CONNECTED
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        val generation = h.finder.connectionGeneration
        var left = 0
        h.finder.onLeft = { left++ }
        val other = WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:99" }
        `when`(group.owner).thenReturn(other)
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(group)
        assertFalse(h.finder.joined)
        assertTrue(h.finder.connectionGeneration > generation)
        assertEquals("", h.finder.connectedPeerAddress)
        assertEquals("", h.finder.connectedBluetoothAddress)
        assertEquals(1, left)
        verify(h.manager, never()).removeGroup(any(), any())
    }

    @Test
    fun lockedVehicleRecoversItsNamelessGroupByTheConfirmedWifiAddress() = check { h ->
        h.broadcastEnabled(false)
        `when`(h.prefs.carLifeVehicleAddress).thenReturn("64:FE:15:28:C8:E4")
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        h.car.deviceName = ""
        h.car.status = WifiP2pDevice.CONNECTED
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals(h.car.deviceAddress, h.finder.connectedPeerAddress)
    }

    @Test
    fun foreignPeerChangesStayQuietAndTheLogToggleDoesNotChangeTheSelectedCar() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.AVAILABLE
        `when`(h.prefs.carLifeVehicleAddress).thenReturn("64:FE:15:28:C8:E4")
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        val other = WifiP2pDevice().apply {
            deviceName = "Foreign printer"
            deviceAddress = "02:11:22:33:44:99"
            status = WifiP2pDevice.AVAILABLE
        }
        h.visible = listOf(h.car, other)
        DiagLog.clear()
        h.broadcastPeers()
        val ownSnapshot = DiagLog.entries.value.filter { it.tag == "P2P" }.map { it.text }
        assertTrue(ownSnapshot.any { it.contains("CarLife") })
        assertFalse(ownSnapshot.any { it.contains("Foreign") || it.contains(other.deviceAddress) })
        other.deviceName = "Foreign TV"
        h.broadcastPeers()
        assertEquals(ownSnapshot, DiagLog.entries.value.filter { it.tag == "P2P" }.map { it.text })
        `when`(h.prefs.showOtherDevicesInLogs).thenReturn(true)
        h.broadcastPeers()
        assertTrue(DiagLog.entries.value.any { it.text.contains("Foreign TV") })
        h.broadcastEnabled(true)
        h.broadcastPeers()
        assertTrue(h.finder.inSight("CarLife"))
        assertFalse(h.finder.joined)
    }

    @Test
    fun anExistingGroupCanAcquireFreshBluetoothProofAfterItWasAlreadyPublished() = check { h ->
        var identified = 0
        var joined = 0
        h.finder.onVehicleIdentified = { identified++ }
        h.finder.onJoined = { joined++ }
        h.broadcastEnabled(false)
        h.visible = emptyList()
        h.broadcastPeers()
        h.broadcastEnabled(true)
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals("", h.finder.connectedBluetoothAddress)
        assertEquals(0, identified)
        assertEquals(1, joined)
        val generation = h.finder.connectionGeneration

        h.finder.beginBluetoothExchange("64:FE:15:28:C8:E4")
        h.visible = listOf(h.car.apply { status = WifiP2pDevice.CONNECTED })
        h.broadcastPeers()
        h.finder.connectByName("CarLife")
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true))
        h.groups.last().onGroupInfoAvailable(group)
        assertEquals(h.car.deviceAddress, h.finder.connectedPeerAddress)
        assertEquals("64:FE:15:28:C8:E4", h.finder.connectedBluetoothAddress)
        assertEquals(1, identified)
        assertEquals(1, joined)
        assertEquals(generation, h.finder.connectionGeneration)
    }

    @Test
    fun aNewGroupCannotReuseThePreviousConnectionsGeneration() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.CONNECTED
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        val first = h.finder.connectionGeneration
        assertTrue(h.finder.joined)

        // The replacement can reuse the very same interface and IP.
        h.broadcastConnection(false)
        assertFalse(h.finder.joined)
        h.broadcastPeers()
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertTrue(h.finder.connectionGeneration > first)
        val second = h.finder.connectionGeneration

        // Also detect owner changes when Android omitted a disconnected event.
        val other = WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:99" }
        `when`(group.owner).thenReturn(other)
        h.broadcastConnection(true)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.connectionGeneration > second)
    }

    @Test
    fun losingAKnownClientInvalidatesItsCommandEvenWhenThePhoneRemainsGroupOwner() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.CONNECTED
        h.broadcastEnabled(true)
        h.broadcastPeers()
        fun refresh(group: WifiP2pGroup) {
            h.broadcastConnection(true)
            h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true).apply { isGroupOwner = true })
            h.groups.last().onGroupInfoAvailable(group)
        }
        val group = mock(WifiP2pGroup::class.java)
        val phone = WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:90" }
        `when`(group.owner).thenReturn(phone)
        `when`(group.clientList).thenReturn(listOf(h.car))
        refresh(group)
        assertEquals(h.car.deviceAddress, h.finder.connectedPeerAddress)
        val first = h.finder.connectionGeneration
        `when`(group.clientList).thenReturn(emptyList())
        refresh(group)
        assertEquals("", h.finder.connectedPeerAddress)
        assertTrue(h.finder.connectionGeneration > first)
        val other = WifiP2pDevice().apply {
            deviceName = "CarLife"
            deviceAddress = "02:00:00:00:00:91"
            status = WifiP2pDevice.CONNECTED
        }
        h.visible = listOf(other)
        h.broadcastPeers()
        `when`(group.clientList).thenReturn(listOf(other))
        refresh(group)
        assertEquals(other.deviceAddress, h.finder.connectedPeerAddress)
        assertTrue(h.finder.connectionGeneration > first)
    }

    @Test
    fun anUnidentifiedExistingGroupDoesNotPublishItsNetworkName() = check { h ->
        h.broadcastEnabled(false)
        h.visible = emptyList()
        h.broadcastPeers()
        h.broadcastEnabled(true)
        var name: String? = null
        h.finder.onJoined = { name = it.name }
        DiagLog.clear()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        `when`(group.networkName).thenReturn("Foreign private network")
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals("", name)
        assertFalse(DiagLog.entries.value.any { it.text.contains("Foreign private network") })
    }

    @Test
    fun rememberedWifiNameCannotAttributeTheOldGroupToANewBluetoothCaller() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.CONNECTED
        h.broadcastEnabled(true)
        h.finder.beginBluetoothExchange("22:33:44:55:66:77")
        // prefs.carP2pName still names the earlier car. The new caller has not
        // announced a group, so it supplies no proof of ownership of this one.
        h.broadcastPeers()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals("", h.finder.connectedBluetoothAddress)
        h.finder.connectByName("Other car group")
        h.connections.last().onConnectionInfoAvailable(h.connectionInfo(true))
        h.groups.last().onGroupInfoAvailable(group)
        assertEquals("", h.finder.connectedBluetoothAddress)
    }

    @Test
    fun aNewBluetoothCallerCannotSearchUsingThePreviousCallersSavedName() = check { h ->
        h.broadcastEnabled(false)
        h.car.status = WifiP2pDevice.AVAILABLE
        h.broadcastEnabled(true)
        h.finder.beginBluetoothExchange("22:33:44:55:66:77")
        h.broadcastPeers()
        h.finder.carReady()
        assertFalse(h.finder.inSight("CarLife"))
        verify(h.manager, never()).connect(any(), any(), any())

        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.finder.connectByName("CarLife")
            verify(h.manager).connect(any(), any(), any())
        }
    }

    @Test
    fun pinningTheJoinedCarRejectsAnotherCandidatesEarlierPeerAndServiceAnnouncement() = check { h ->
        val a = "64:FE:15:28:C8:E4"
        h.broadcastEnabled(false)
        h.finder.beginBluetoothExchange(a)
        h.finder.connectByName("CarLife")
        h.broadcastEnabled(true)
        h.broadcastPeers()
        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(h.car)
        h.groups.last().onGroupInfoAvailable(group)
        assertEquals(a, h.finder.connectedBluetoothAddress)

        // B was allowed before A's first successful projection pins A.
        h.finder.beginBluetoothExchange("22:33:44:55:66:77")
        h.finder.connectByName("_CarB")
        `when`(h.prefs.carLifeVehicleAddress).thenReturn(a)
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        h.broadcastConnection(false)
        val b = WifiP2pDevice().apply {
            deviceName = "_CarB"
            deviceAddress = "02:00:00:00:00:99"
            status = WifiP2pDevice.AVAILABLE
        }
        h.visible = listOf(b)
        h.broadcastPeers()
        assertFalse(h.finder.inSight("_CarB"))
        verify(h.manager, never()).connect(any(), any(), any())

        // Discovery still runs with no trusted name, but B's old service is rejected.
        h.finder.search()
        advanceTimeBy(15_000)
        runCurrent()
        h.serviceListener.onDnsSdServiceAvailable("_CarB", "_tcp", b)
        verify(h.manager, never()).connect(any(), any(), any())
        assertFalse(h.finder.joined)
    }

    @Test
    fun thePinnedBluetoothCallerCanAnnounceAnExactServiceOnANewWifiAddress() = check { h ->
        val a = "64:FE:15:28:C8:E4"
        h.broadcastEnabled(false)
        `when`(h.prefs.carLifeVehicleAddress).thenReturn(a)
        `when`(h.prefs.carLifeWifiAddress).thenReturn(h.car.deviceAddress)
        h.visible = emptyList()
        h.broadcastEnabled(true)
        h.finder.beginBluetoothExchange(a)
        h.finder.connectByName("_CarA")
        h.finder.search()
        advanceTimeBy(15_000)
        runCurrent()
        val rotated = WifiP2pDevice().apply {
            deviceName = "Generic head unit"
            deviceAddress = "02:00:00:00:00:99"
            status = WifiP2pDevice.AVAILABLE
        }
        h.serviceListener.onDnsSdServiceAvailable("_Car", "_tcp", rotated)
        verify(h.manager, never()).connect(any(), any(), any())
        mockConstruction(WifiP2pConfig::class.java) { config, _ -> config.wps = WpsInfo() }.use {
            h.serviceListener.onDnsSdServiceAvailable("_CarA", "_tcp", rotated)
            val config = ArgumentCaptor.forClass(WifiP2pConfig::class.java)
            verify(h.manager).connect(any(), config.capture(), any())
            assertEquals(rotated.deviceAddress, config.value.deviceAddress)
        }

        h.broadcastConnection(true)
        val group = mock(WifiP2pGroup::class.java)
        `when`(group.owner).thenReturn(rotated)
        h.groups.last().onGroupInfoAvailable(group)
        assertTrue(h.finder.joined)
        assertEquals(a, h.finder.connectedBluetoothAddress)
        assertEquals(rotated.deviceAddress, h.finder.connectedPeerAddress)
    }

    @Test
    fun aNewBluetoothCallerCannotReuseThePreviousCallersSavedService() = check { h ->
        h.broadcastEnabled(false)
        `when`(h.prefs.carP2pName).thenReturn("_CarA")
        h.visible = emptyList()
        h.broadcastEnabled(true)
        h.finder.beginBluetoothExchange("22:33:44:55:66:77")
        h.finder.search()
        advanceTimeBy(15_000)
        runCurrent()
        h.serviceListener.onDnsSdServiceAvailable("_CarA", "_tcp", h.car)
        verify(h.manager, never()).connect(any(), any(), any())
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
        val prefs = mock(Prefs::class.java)
        val channel = mock(WifiP2pManager.Channel::class.java)
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
        lateinit var serviceListener: WifiP2pManager.DnsSdServiceResponseListener

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
            doAnswer { it.getArgument<WifiP2pManager.ActionListener?>(1)?.let(cancels::add); null }
                .`when`(manager).cancelConnect(any(), any())
            doAnswer { removals += it.getArgument<WifiP2pManager.ActionListener>(1); null }
                .`when`(manager).removeGroup(any(), any())
            doAnswer { groups += it.getArgument<WifiP2pManager.GroupInfoListener>(1); null }
                .`when`(manager).requestGroupInfo(any(), any())
            doAnswer { connections += it.getArgument<WifiP2pManager.ConnectionInfoListener>(1); null }
                .`when`(manager).requestConnectionInfo(any(), any())
            doAnswer { networks += it.getArgument<WifiP2pManager.NetworkInfoListener>(1); null }
                .`when`(manager).requestNetworkInfo(any(), any())
            doAnswer { serviceListener = it.getArgument(1); null }
                .`when`(manager).setDnsSdResponseListeners(any(), any(), any())
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

        fun timeout(seconds: Int) { `when`(prefs.carJoinTimeoutSeconds).thenReturn(seconds) }

        fun busyDiscovery() {
            broadcastEnabled(false)
            visible = emptyList()
            broadcastPeers()
            broadcastEnabled(true)
            doAnswer { it.getArgument<WifiP2pManager.ActionListener>(1).onFailure(WifiP2pManager.BUSY); null }
                .`when`(manager).discoverPeers(any(), any())
            finder.connectByName("CarLife")
            finder.search()
        }

        fun connectionInfo(formed: Boolean, ownerAddress: Boolean = true) = WifiP2pInfo().apply {
            groupFormed = formed
            groupOwnerAddress = if (formed && ownerAddress) InetAddress.getByName("192.168.49.1") else null
        }

        @Suppress("DEPRECATION")
        fun network(state: NetworkInfo.DetailedState): NetworkInfo = mock(NetworkInfo::class.java).also {
            `when`(it.detailedState).thenReturn(state)
        }

        @Suppress("DEPRECATION")
        fun broadcastConnection(formed: Boolean, ownerAddress: Boolean = true) {
            val intent = mock(Intent::class.java)
            `when`(intent.action).thenReturn(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            `when`(intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO))
                .thenReturn(connectionInfo(formed, ownerAddress))
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
