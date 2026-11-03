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
        return peers.asSequence()
            .filter { it.connectable && it.hint && it.receiverToken == savedToken &&
                it.lastSeenElapsedMs > 0L && nowElapsedMs >= it.lastSeenElapsedMs &&
                nowElapsedMs - it.lastSeenElapsedMs <= maxAgeMs }
            .maxByOrNull { it.lastSeenElapsedMs }
    }
}
