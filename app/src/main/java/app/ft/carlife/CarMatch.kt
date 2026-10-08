package app.ft.carlife

import kotlin.math.abs

object CarMatch {
    enum class Why { NAME, ADDRESS, NAMELESS_GROUP }

    fun pick(peers: List<CarFinder.Peer>, want: String, carBluetooth: String): Pair<CarFinder.Peer, Why>? {
        val w = want.trim()
        val byName = if (w.isNotEmpty()) peers.firstOrNull { it.name.equals(w, true) || (it.name.isNotBlank() && it.name.contains(w, true)) }
            else peers.firstOrNull { it.name.contains("carlife", true) }
        if (byName != null) return byName to Why.NAME
        if (carBluetooth.isNotBlank()) peers.firstOrNull { sameRadio(it.address, carBluetooth) }?.let { return it to Why.ADDRESS }
        if (w.isNotEmpty()) {
            val nameless = peers.filter { it.groupOwner && it.name.isBlank() }
            if (nameless.size == 1) return nameless[0] to Why.NAMELESS_GROUP
        }
        return null
    }

    fun sameRadio(wifi: String, bluetooth: String): Boolean {
        val a = mac(wifi) ?: return false
        val b = mac(bluetooth) ?: return false
        if ((a[0] and 0xFD) != (b[0] and 0xFD) || a[1] != b[1] || a[2] != b[2]) return false
        val na = (a[3] shl 16) or (a[4] shl 8) or a[5]
        val nb = (b[3] shl 16) or (b[4] shl 8) or b[5]
        return abs(na - nb) <= 3
    }

    private fun mac(s: String): IntArray? {
        val parts = s.trim().split(':', '-')
        if (parts.size != 6) return null
        val out = IntArray(6)
        for (i in 0 until 6) out[i] = parts[i].toIntOrNull(16)?.takeIf { it in 0..255 } ?: return null
        return out
    }
}
