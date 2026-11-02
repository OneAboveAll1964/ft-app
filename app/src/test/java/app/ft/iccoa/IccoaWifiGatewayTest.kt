package app.ft.iccoa

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class IccoaWifiGatewayTest {
    private fun address(text: String) = InetAddress.getByName(text) as Inet4Address
    private fun dhcp(text: String) = address(text).address.foldIndexed(0) { index, result, byte ->
        result or ((byte.toInt() and 255) shl (index * 8))
    }
    private fun gateway(local: String, prefix: Int, reportedLocal: String, peer: String) =
        IccoaWifiGateway.fromDhcp(address(local), prefix, dhcp(reportedLocal), dhcp(peer))?.hostAddress

    @Test fun usesTheReportedGatewayRatherThanGuessingDotOne() {
        assertEquals("192.168.43.254", gateway("192.168.43.22", 24, "192.168.43.22", "192.168.43.254"))
        assertEquals("172.20.0.1", gateway("172.20.7.18", 20, "172.20.7.18", "172.20.0.1"))
    }

    @Test fun rejectsDhcpFromAnotherConcurrentWifiConnection() {
        assertNull(gateway("192.168.43.22", 24, "192.168.1.2", "192.168.1.1"))
        assertNull(gateway("192.168.43.22", 24, "192.168.43.23", "192.168.43.1"))
    }

    @Test fun rejectsMissingSelfReservedAndOutOfSubnetGateways() {
        listOf("0.0.0.0", "127.0.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "192.168.43.22", "192.168.42.1", "192.168.43.0", "192.168.43.255").forEach {
            assertNull(it, gateway("192.168.43.22", 24, "192.168.43.22", it))
        }
    }

    @Test fun validatesTheActualPrefixInsteadOfAssumingSlash24() {
        assertNull(gateway("192.168.43.130", 25, "192.168.43.130", "192.168.43.1"))
        assertNull(gateway("192.168.43.130", 25, "192.168.43.130", "192.168.43.128"))
        assertEquals("192.168.43.129", gateway("192.168.43.130", 25, "192.168.43.130", "192.168.43.129"))
        listOf(-1, 0, 32, 33).forEach { assertNull(gateway("192.168.43.22", it, "192.168.43.22", "192.168.43.1")) }
    }

    @Test fun pointToPointPrefixAllowsTheOtherEndpoint() {
        assertEquals("192.168.43.0", gateway("192.168.43.1", 31, "192.168.43.1", "192.168.43.0"))
    }
}
