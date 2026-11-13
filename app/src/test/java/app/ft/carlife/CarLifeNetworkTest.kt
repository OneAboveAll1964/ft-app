package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

class CarLifeNetworkTest {
    @Test fun aConfirmedInterfaceNeverFallsBackToAnUnrelatedP2pNetwork() {
        val foreign = network("p2p-foreign", "192.168.50.25")
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(foreign)) }
            assertNull(NetUtil.wifiDirectIpv4("p2p-car"))
            assertNull(NetUtil.wifiDirectIpv4(null))
            assertNull(NetUtil.wifiDirectIpv4(""))
        }
    }

    @Test fun onlyTheConfirmedGroupsInterfaceProvidesTheCarAddress() {
        val foreign = network("p2p-foreign", "192.168.50.25")
        val car = network("p2p-car", "192.168.49.25")
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(foreign, car)) }
            assertEquals("192.168.49.25", NetUtil.wifiDirectIpv4("p2p-car"))
        }
    }

    private fun network(name: String, address: String): NetworkInterface =
        mock(NetworkInterface::class.java).also {
            `when`(it.name).thenReturn(name)
            `when`(it.isUp).thenReturn(true)
            `when`(it.inetAddresses).thenAnswer { Collections.enumeration(listOf(InetAddress.getByName(address))) }
        }
}
