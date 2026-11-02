package app.ft.iccoa

import android.content.Context

/** Last user-approved car. Only descriptive BLE metadata is stored; never PINs or keys. */
internal class IccoaRememberedCar(context: Context) {
    private val prefs = context.getSharedPreferences("iccoa-remembered-car", Context.MODE_PRIVATE)

    fun remember(peer: IccoaPeer): Boolean {
        if (!peer.connectable || peer.address.isBlank()) return false
        return prefs.edit().putString("address", peer.address)
            .putString("name", peer.name.trim()).commit()
    }

    /**
     * Never try an encrypted remembered credential against a different car.
     * The BLE address may rotate; a uniquely discovered, hinted matching name
     * is acceptable only in the already selected/owner-approved car path.
     */
    fun matches(peer: IccoaPeer?, discovered: List<IccoaPeer>): Boolean {
        if (peer == null || !peer.connectable) return false
        val savedAddress = prefs.getString("address", "").orEmpty()
        if (savedAddress.isBlank()) return false
        if (peer.address.equals(savedAddress, ignoreCase = true)) return true
        val savedName = prefs.getString("name", "").orEmpty()
        // BLE MAC rotation is normal, but never silently choose between two
        // identically named projection receivers: a shared name is not identity.
        val matches = discovered.filter { it.connectable && it.hint &&
            it.name.trim().equals(savedName, ignoreCase = true) }
        return peer.hint && savedName.isNotBlank() &&
            peer.name.trim().equals(savedName, ignoreCase = true) &&
            matches.size == 1 && matches.single().address.equals(peer.address, ignoreCase = true)
    }

    fun select(peers: List<IccoaPeer>, scanComplete: Boolean): IccoaPeer? {
        val address = prefs.getString("address", "").orEmpty()
        if (address.isBlank()) return null
        val direct = peers.filter { it.connectable && it.address.equals(address, ignoreCase = true) }
        if (direct.size == 1) return direct.single()
        // XA60 rotates BLE addresses. A uniquely named ICCOA peer is a candidate,
        // not an authenticated car; the normal cryptographic handshake still applies.
        val name = prefs.getString("name", "").orEmpty()
        if (!scanComplete || name.isBlank()) return null
        val matching = peers.filter {
            it.connectable && it.hint && it.name.trim().equals(name, ignoreCase = true)
        }
        return matching.singleOrNull()
    }
}
