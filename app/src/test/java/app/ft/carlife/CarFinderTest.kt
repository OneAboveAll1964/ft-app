package app.ft.carlife

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.SystemClock
import app.ft.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

@OptIn(ExperimentalCoroutinesApi::class)
class CarFinderTest {
    @Test
    fun aMissingGroupBroadcastDoesNotCutShortAThirtySecondJoin() = checkTimeout(30)

    @Test
    fun aMissingGroupBroadcastDoesNotCutShortASixtySecondJoin() = checkTimeout(60)

    @Suppress("DEPRECATION")
    private fun checkTimeout(seconds: Int) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mock(Context::class.java)
        val prefs = mock(Prefs::class.java)
        val manager = mock(WifiP2pManager::class.java)
        val channel = mock(WifiP2pManager.Channel::class.java)
        `when`(context.getSystemService(Context.WIFI_P2P_SERVICE)).thenReturn(manager)
        `when`(manager.initialize(any(), any(), any())).thenReturn(channel)
        `when`(prefs.carP2pName).thenReturn("CarLife")
        `when`(prefs.carBtAddress).thenReturn("")
        `when`(prefs.carJoinTimeoutSeconds).thenReturn(seconds)
        val peer = WifiP2pDevice().apply {
            deviceName = "CarLife"
            deviceAddress = "02:00:00:00:00:01"
            status = WifiP2pDevice.INVITED
        }
        val peers = mock(WifiP2pDeviceList::class.java)
        `when`(peers.deviceList).thenReturn(listOf(peer))
        doAnswer {
            it.getArgument<WifiP2pManager.PeerListListener>(1).onPeersAvailable(peers)
            null
        }.`when`(manager).requestPeers(any(), any())
        val finder = CarFinder(context, prefs, backgroundScope)
        val timeouts = mutableListOf<Int>()
        val retries = mutableListOf<Int>()
        finder.onJoinTimeout = { timeouts += it }
        finder.onRetry = { _, attempt -> retries += attempt }

        try {
            mockStatic(NetworkInterface::class.java).use { network ->
                network.`when`<Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                    .thenReturn(Collections.emptyEnumeration())
                mockStatic(SystemClock::class.java).use { clock ->
                    clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { testScheduler.currentTime }
                    finder.start()
                    val receiver = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
                    verify(context).registerReceiver(receiver.capture(), any())
                    val discovered = mock(Intent::class.java)
                    `when`(discovered.action).thenReturn(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    receiver.value.onReceive(context, discovered)
                    runCurrent()

                    // The preference is snapshotted for this attempt.
                    `when`(prefs.carJoinTimeoutSeconds).thenReturn(5)
                    advanceTimeBy(16_000)
                    val noGroup = mock(Intent::class.java)
                    `when`(noGroup.action).thenReturn(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    `when`(noGroup.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO))
                        .thenReturn(WifiP2pInfo().apply { groupFormed = false })
                    receiver.value.onReceive(context, noGroup)
                    runCurrent()
                    // An unrelated name only counts as in sight while a join is active.
                    assertTrue(finder.inSight("unrelated"))
                    assertTrue(timeouts.isEmpty())
                    assertTrue(retries.isEmpty())

                    advanceTimeBy(seconds * 1_000L - 16_000 - 1)
                    runCurrent()
                    assertTrue(timeouts.isEmpty())
                    assertTrue(retries.isEmpty())
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(listOf(seconds), timeouts)
                    assertEquals(listOf(2), retries)
                    verify(manager).cancelConnect(any(), any())
                }
            }
        } finally {
            finder.stop()
            Dispatchers.resetMain()
        }
    }
}
