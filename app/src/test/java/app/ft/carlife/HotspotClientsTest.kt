package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class HotspotClientsTest {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun aSlash24HotspotHas253OtherAddresses() {
        val hosts = HotspotClients.hostsOf(v4("10.64.203.42"), 24)
        assertEquals(253, hosts.size)
        assertTrue("10.64.203.183" in hosts)
        assertFalse("the phone itself is not probed", "10.64.203.42" in hosts)
        assertFalse("10.64.203.0" in hosts)
        assertFalse("10.64.203.255" in hosts)
    }

    @Test
    fun aWideSubnetIsOnlySweptAroundThePhone() {
        val hosts = HotspotClients.hostsOf(v4("192.168.43.1"), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("192.168.43.") })
    }

    @Test
    fun aSmallSubnetStaysSmall() {
        val hosts = HotspotClients.hostsOf(v4("192.168.49.1"), 28)
        assertEquals((2..14).map { "192.168.49.$it" }, hosts)
    }
}
