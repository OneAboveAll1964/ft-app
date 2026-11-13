package app.ft.iccoa

/**
 * Tracks Android Network callbacks and guards auth socket binding.
 * Key/listener activation is serialized with these callbacks on the main looper.
 * All public operations are synchronized for auth workers.
 */
internal class IccoaNetworkCallbackGate<T : Any> {
    private var selected: T? = null
    private var lastLost: T? = null

    @Synchronized fun onAvailable(network: T): Boolean {
        val changed = selected != network
        selected = network
        lastLost = null
        return changed
    }

    @Synchronized fun isCurrent(network: T): Boolean = selected == network

    @Synchronized fun bindIfCurrent(network: T, bind: () -> Unit): Boolean {
        if (selected != network) return false
        bind()
        return true
    }

    @Synchronized fun onLost(network: T): Boolean {
        if (selected != network) return false
        selected = null
        lastLost = network
        return true
    }

    @Synchronized fun isUnreplacedLoss(network: T): Boolean =
        selected == null && lastLost == network

    @Synchronized fun clear() {
        selected = null
        lastLost = null
    }
}
