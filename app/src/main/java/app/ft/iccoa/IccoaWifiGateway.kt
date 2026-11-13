package app.ft.iccoa

import java.net.Inet4Address
import java.net.InetAddress

internal object IccoaWifiGateway {
    /** Android DhcpInfo stores IPv4 integers in little-endian byte order. */
    fun fromDhcp(local: Inet4Address, prefix: Int, dhcpAddress: Int, dhcpGateway: Int): Inet4Address? {
        fun address(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        fun number(bytes: ByteArray) = bytes.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 255) }
        if (prefix !in 1..31 || !local.address.contentEquals(address(dhcpAddress))) return null
        val peer = InetAddress.getByAddress(address(dhcpGateway)) as Inet4Address
        val first = peer.address[0].toInt() and 255
        if (first !in 1..223 || peer.isLoopbackAddress || peer == local) return null
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val host = number(local.address)
        val gateway = number(peer.address)
        if ((host and mask) != (gateway and mask)) return null
        if (prefix < 31 && ((gateway and mask) == gateway || (gateway or mask) == 0xffffffffL)) return null
        return peer
    }
}
