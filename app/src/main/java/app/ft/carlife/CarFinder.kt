package app.ft.carlife

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Looper
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

@SuppressLint("MissingPermission")
class CarFinder(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    data class Peer(val name: String, val address: String, val status: Int, val groupOwner: Boolean)
    data class Link(val groupOwnerIp: String, val iface: String?, val weAreOwner: Boolean, val name: String)

    private val tag = "P2P"
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var loop: Job? = null
    @Volatile private var connecting: String? = null
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers
    private val _state = MutableStateFlow("off")
    val state: StateFlow<String> = _state
    private val _link = MutableStateFlow<Link?>(null)
    val link: StateFlow<Link?> = _link
    var onJoined: ((Link) -> Unit)? = null
    var onLeft: (() -> Unit)? = null

    fun start() {
        val m = manager
        if (m == null) {
            _state.value = "wifi direct unavailable"
            DiagLog.w(tag, "WiFi Direct unavailable on this device")
            return
        }
        if (channel != null) return
        val ch = runCatching { m.initialize(context, Looper.getMainLooper()) { DiagLog.w(tag, "channel lost"); channel = null } }.getOrNull()
        if (ch == null) {
            _state.value = "wifi direct unavailable"
            DiagLog.w(tag, "WiFi Direct channel could not be created")
            return
        }
        channel = ch
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = handle(i)
        }
        receiver = r
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
        _state.value = "searching"
        DiagLog.i(tag, "WiFi Direct search started")
        loop = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (_link.value == null && connecting == null) discover()
                delay(12_000)
            }
        }
    }

    private fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (_link.value == null && connecting == null) _state.value = "searching"
            }

            override fun onFailure(reason: Int) {
                _state.value = "discovery failed ($reason)"
                DiagLog.w(tag, "discoverPeers failed reason=$reason")
            }
        })
    }

    private fun handle(i: Intent) {
        val m = manager ?: return
        val ch = channel ?: return
        when (i.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                DiagLog.i(tag, if (on) "WiFi Direct enabled" else "WiFi Direct disabled")
                if (!on) _state.value = "wifi direct off"
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> m.requestPeers(ch) { list ->
                val ps = list.deviceList.map { Peer(it.deviceName ?: "", it.deviceAddress ?: "", it.status, it.isGroupOwner) }
                _peers.value = ps
                if (ps.isNotEmpty()) DiagLog.d(tag, "peers: " + ps.joinToString { "${it.name}[${statusName(it.status)}]" })
                autoMatch(ps)
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (info != null && info.groupFormed) {
                    val ip = info.groupOwnerAddress?.hostAddress ?: return
                    m.requestGroupInfo(ch) { g ->
                        val l = Link(ip, g?.`interface`, info.isGroupOwner, g?.networkName ?: connecting ?: "")
                        connecting = null
                        _link.value = l
                        _state.value = "joined ${l.name.ifBlank { ip }}"
                        DiagLog.i(tag, "joined group owner=$ip iface=${l.iface} weAreOwner=${l.weAreOwner} network=${g?.networkName}")
                        onJoined?.invoke(l)
                    }
                } else if (_link.value != null) {
                    _link.value = null
                    connecting = null
                    _state.value = "searching"
                    DiagLog.w(tag, "left the car group")
                    onLeft?.invoke()
                } else if (connecting != null) {
                    connecting = null
                }
            }
        }
    }

    private fun autoMatch(ps: List<Peer>) {
        if (_link.value != null || connecting != null) return
        val want = prefs.carP2pName.trim()
        val target = ps.firstOrNull { p ->
            if (want.isNotEmpty()) p.name.equals(want, true) || p.name.contains(want, true) else p.name.contains("carlife", true)
        } ?: return
        connect(target)
    }

    fun connectByName(name: String) {
        prefs.carP2pName = name
        val p = _peers.value.firstOrNull { it.name == name } ?: return
        connect(p)
    }

    fun connect(p: Peer) {
        val m = manager ?: return
        val ch = channel ?: return
        if (_link.value != null || connecting != null) return
        connecting = p.name
        _state.value = "connecting to ${p.name}"
        DiagLog.i(tag, "connecting to ${p.name} (${p.address})")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = p.address
            val pin = prefs.carWpsPin.trim()
            if (pin.isNotEmpty()) {
                wps.setup = WpsInfo.KEYPAD
                wps.pin = pin
            } else {
                wps.setup = WpsInfo.PBC
            }
            groupOwnerIntent = 0
        }
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                connecting = null
                _state.value = "connect failed ($reason)"
                DiagLog.w(tag, "connect to ${p.name} failed reason=$reason")
            }
        })
    }

    fun registerService(ports: Map<String, Int>) {
        val m = manager ?: return
        val ch = channel ?: return
        val info = WifiP2pDnsSdServiceInfo.newInstance("carlife", "_carlife._tcp", ports.mapValues { it.value.toString() })
        m.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = DiagLog.i(tag, "advertising _carlife._tcp on the car link")
            override fun onFailure(reason: Int) = DiagLog.d(tag, "service advert failed $reason")
        })
    }

    fun probe(ip: String, ports: List<Int>) {
        scope.launch(Dispatchers.IO) {
            for (port in ports) {
                val open = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 700); true } }.getOrDefault(false)
                DiagLog.i(tag, "head unit $ip:$port ${if (open) "open" else "closed"}")
            }
        }
    }

    fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.removeGroup(ch, null) }
        _link.value = null
    }

    fun stop() {
        loop?.cancel()
        loop = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) runCatching { m.stopPeerDiscovery(ch, null) }
        channel = null
        _state.value = "off"
    }

    private fun statusName(s: Int) = when (s) {
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.AVAILABLE -> "available"
        else -> "unavailable"
    }
}
