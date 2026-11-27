package app.ft.carlife

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.RequiresApi
import app.ft.core.DiagLog

/**
 * Hooks Android 15+ (SDK 35+) [WifiP2pManager.WifiP2pListener] events for diagnostics
 * and fast-fail recovery when the system aborts group formation before the join watchdog fires.
 */
internal object P2pDiagnostics {
    /** Maps Android's WifiP2pListener failure reason codes to human-readable text. */
    fun failureReasonName(reason: Int): String = when (reason) {
        0 -> "cancelled"
        1 -> "timed out"
        2 -> "user rejected"
        3 -> "provision discovery failed"
        4 -> "group removed"
        5 -> "invitation failed"
        else -> "code $reason"
    }

    fun register(
        context: Context,
        manager: WifiP2pManager,
        describe: () -> String,
        onGroupCreationFailed: ((Int) -> Unit)? = null
    ): () -> Unit {
        if (Build.VERSION.SDK_INT < 35) return {}
        return registerModern(context, manager, describe, onGroupCreationFailed)
    }

    @RequiresApi(35)
    @SuppressLint("MissingPermission")
    private fun registerModern(
        context: Context,
        manager: WifiP2pManager,
        describe: () -> String,
        onGroupCreationFailed: ((Int) -> Unit)? = null
    ): () -> Unit {
        var active = true
        fun log(event: String) {
            if (active) DiagLog.i("P2P", "Android P2P event: $event; ${describe()}")
        }
        val listener = object : WifiP2pManager.WifiP2pListener {
            override fun onGroupCreating() = log("group creating")

            override fun onGroupNegotiationRejectedByUser() = log("group negotiation rejected by user")

            override fun onGroupCreationFailed(reason: Int) {
                log("group creation failed reason=$reason (${failureReasonName(reason)})")
                if (active) onGroupCreationFailed?.invoke(reason)
            }

            override fun onGroupCreated(info: WifiP2pInfo, group: WifiP2pGroup) =
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
