package app.ft.iccoa

/**
 * Finds a fresh remembered BLE receiver candidate even when its MAC rotates
 * and the device name is empty. The advertised token is untrusted and only
 * selects where to attempt strict OEM EC-key authentication.
 */
internal object IccoaReceiverCandidate {
    fun select(peers: List<IccoaPeer>, savedToken: String, nowElapsedMs: Long,
               maxAgeMs: Long = 4_000L): IccoaPeer? {
        if (savedToken.isBlank()) return null
        val candidates = peers.asSequence()
            .filter { it.connectable && it.hint && it.receiverToken == savedToken &&
                fresh(it, nowElapsedMs, maxAgeMs) }
            .toList()
        val newest = candidates.maxOfOrNull { it.lastSeenElapsedMs } ?: return null
        // Equal timestamps cannot identify which rotating address is current.
        return candidates.filter { it.lastSeenElapsedMs == newest }.singleOrNull()
    }

    fun fresh(peer: IccoaPeer, nowElapsedMs: Long, maxAgeMs: Long = 4_000L): Boolean =
        peer.lastSeenElapsedMs > 0L && nowElapsedMs >= peer.lastSeenElapsedMs &&
            nowElapsedMs - peer.lastSeenElapsedMs <= maxAgeMs

    fun selectRemembered(peers: List<IccoaPeer>, address: String, name: String,
                         token: String, scanComplete: Boolean, nowElapsedMs: Long): IccoaPeer? {
        if (address.isBlank()) return null
        // A known token takes precedence over an obsolete address. If its current
        // advertisement is missing or ambiguous, do not bypass that with old metadata.
        if (token.isNotBlank()) return select(peers, token, nowElapsedMs)
        val freshPeers = peers.filter { it.connectable && it.hint && fresh(it, nowElapsedMs) }
        val direct = freshPeers.filter { it.address.equals(address, ignoreCase = true) }
        if (direct.size == 1) return direct.single()
        if (!scanComplete || name.isBlank()) return null
        return freshPeers.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }.singleOrNull()
    }
}
