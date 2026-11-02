package app.ft.carlife

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.RequiresApi
import app.ft.core.DiagLog

/** Additional system-wide P2P events; these do not change connection state. */
internal object P2pDiagnostics {
    fun register(context: Context, manager: WifiP2pManager, describe: () -> String): () -> Unit {
        if (Build.VERSION.SDK_INT < 35) return {}
        return registerModern(context, manager, describe)
    }

    @RequiresApi(35)
    @SuppressLint("MissingPermission")
    private fun registerModern(context: Context, manager: WifiP2pManager, describe: () -> String): () -> Unit {
        var active = true
        fun log(event: String) {
            if (active) DiagLog.i("P2P", "Android P2P event: $event; ${describe()}")
        }
        val listener = object : WifiP2pManager.WifiP2pListener {
            override fun onGroupCreating() = log("group creating")

            override fun onGroupNegotiationRejectedByUser() = log("group negotiation rejected by user")

            override fun onGroupCreationFailed(reason: Int) = log("group creation failed reason=$reason (${when (reason) {
                0 -> "cancelled"
                1 -> "timed out"
                2 -> "user rejected"
                3 -> "provision discovery failed"
                4 -> "group removed"
                5 -> "invitation failed"
                else -> "unknown"
            }})")

            override fun onGroupCreated(info: WifiP2pInfo, group: WifiP2pGroup) =
                // Never stringify WifiP2pGroup: it can expose the passphrase.
                log("group created iface=${group.`interface`} frequency=${group.frequency}MHz " +
                    "weAreOwner=${info.isGroupOwner} clients=${group.clientList.size}")

            override fun onGroupRemoved() = log("group removed")
        }
        try {
            manager.registerWifiP2pListener(context.mainExecutor, listener)
        } catch (e: RuntimeException) {
            DiagLog.w("P2P", "Android P2P event listener unavailable: ${e.javaClass.simpleName}")
            return {}
        }
        return {
            active = false
            runCatching { manager.unregisterWifiP2pListener(listener) }
        }
    }
}
