package app.ft.iccoa

/** One Quick rejection authorizes one fresh discovery for a manual Normal PIN. */
internal class IccoaNormalFallback {
    private var target: IccoaPeer? = null
    private var startedAtMs = 0L
    val active: Boolean get() = target != null

    fun begin(peer: IccoaPeer?, nowElapsedMs: Long): Boolean {
        cancel()
        if (peer == null || peer.address.isBlank()) return false
        target = peer
        startedAtMs = nowElapsedMs
        return true
    }

    fun select(peers: List<IccoaPeer>, scanComplete: Boolean, nowElapsedMs: Long): IccoaPeer? {
        val previous = target ?: return null
        // Never reuse the advertisement captured before Quick authentication.
        val freshScan = peers.filter { it.lastSeenElapsedMs > startedAtMs }
        val selected = IccoaReceiverCandidate.selectRemembered(freshScan,
            previous.address, previous.name, previous.receiverToken, scanComplete, nowElapsedMs)
        if (selected != null) cancel()
        return selected
    }

    fun cancel() {
        target = null
        startedAtMs = 0L
    }
}
