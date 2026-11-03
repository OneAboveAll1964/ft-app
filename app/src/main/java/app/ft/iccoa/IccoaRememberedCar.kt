package app.ft.iccoa

import android.content.Context
import android.os.SystemClock

/**
 * Owner-approved receiver candidate, not authentication. The BLE MAC of the
 * Toyota Space XA60 rotates and its advertised device name is empty. The
 * serviceData marker is stable in live reports, but is untrusted/public; the
 * peer must still pass ICCOA Quick with the pinned OEM server EC public key.
 */
internal class IccoaRememberedCar(context: Context) {
    private val prefs = context.getSharedPreferences("iccoa-remembered-car", Context.MODE_PRIVATE)

    fun remember(peer: IccoaPeer): Boolean {
        if (!peer.connectable || peer.address.isBlank()) return false
        val edit = prefs.edit().putString("address", peer.address)
            .putString("name", peer.name.trim())
        // A partial advertisement must not erase a previously verified hint.
        if (peer.hint && peer.receiverToken.isNotBlank())
            edit.putString("receiverToken", peer.receiverToken)
        return edit.commit()
    }

    /** Explicit owner reset; never clear trusted identity on transport error. */
    fun clear(): Boolean = prefs.edit().remove("address").remove("name")
        .remove("receiverToken").commit()

    fun matches(peer: IccoaPeer?, discovered: List<IccoaPeer>): Boolean {
        if (peer == null || !peer.connectable) return false
        val savedAddress = prefs.getString("address", "").orEmpty()
        if (savedAddress.isBlank()) return false
        if (peer.address.equals(savedAddress, ignoreCase = true)) return true
        val savedToken = prefs.getString("receiverToken", "").orEmpty()
        if (savedToken.isNotBlank() && peer.hint && peer.receiverToken == savedToken) return true
        val savedName = prefs.getString("name", "").orEmpty()
        val matching = discovered.filter { it.connectable && it.hint &&
            it.name.trim().equals(savedName, ignoreCase = true) }
        return peer.hint && savedName.isNotBlank() &&
            peer.name.trim().equals(savedName, ignoreCase = true) &&
            matching.size == 1 && matching.single().address.equals(peer.address, ignoreCase = true)
    }

    fun select(peers: List<IccoaPeer>, scanComplete: Boolean): IccoaPeer? {
        val address = prefs.getString("address", "").orEmpty()
        if (address.isBlank()) return null
        val direct = peers.filter { it.connectable && it.address.equals(address, ignoreCase = true) }
        if (direct.size == 1 && direct.single().hint) return direct.single()
        val token = prefs.getString("receiverToken", "").orEmpty()
        if (token.isNotBlank()) {
            // Scan may still contain several earlier rotating addresses.
            // Select only the freshest marker from this session; a public
            // marker never grants access without the pinned OEM ECDSA proof.
            val recent = IccoaReceiverCandidate.select(peers, token, SystemClock.elapsedRealtime())
            if (recent != null) return recent
        }
        // Legacy fallback for receivers with a stable, non-empty advertised name.
        val name = prefs.getString("name", "").orEmpty()
        if (!scanComplete || name.isBlank()) return null
        val matching = peers.filter {
            it.connectable && it.hint && it.name.trim().equals(name, ignoreCase = true)
        }
        return matching.singleOrNull()
    }
}
