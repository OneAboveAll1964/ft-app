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
import android.os.Build
import android.os.Looper
import app.ft.core.DiagLog
import app.ft.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    @Volatile private var attemptAt = 0L
    @Volatile private var searching = false
    @Volatile private var enabled = true
    @Volatile private var armed = false
    private var watchdog: Job? = null
    private var failures = 0
    private var lastNoMatch = ""
    @Volatile private var searchStartedAt = 0L
    @Volatile var busyStreak = 0
        private set
    private var servicesChannel: WifiP2pManager.Channel? = null
    private val servicesSeen = HashSet<String>()
    private val peerDetails = HashMap<String, String>()
    private val SERVICES_AFTER_MS = 15_000L
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    private val _state = MutableStateFlow("off")
    private val _link = MutableStateFlow<Link?>(null)
    var onJoined: ((Link) -> Unit)? = null
    var onLeft: (() -> Unit)? = null
    var onRetry: ((name: String, attempt: Int) -> Unit)? = null

    fun start() {
        val m = manager
        if (m == null) {
            _state.value = "wifi direct unavailable"
            DiagLog.w(tag, "WiFi Direct unavailable on this device")
            return
        }
        if (channel != null) return
        val ch = openChannel()
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
        _state.value = "ready"
        loop = scope.launch(Dispatchers.Main) {
            var tick = 0
            while (isActive) {
                if (searching && _link.value == null && connecting == null) {
                    if (wantsServices() && tick % 2 == 1) discoverServices() else discover()
                    tick++
                }
                delay(JoinWatch.DISCOVER_EVERY_MS)
            }
        }
        checkExisting()
    }

    private fun openChannel(): WifiP2pManager.Channel? {
        val m = manager ?: return null
        var made: WifiP2pManager.Channel? = null
        made = runCatching {
            m.initialize(context, Looper.getMainLooper()) {
                if (channel !== made) return@initialize
                DiagLog.w(tag, "WiFi Direct channel lost, opening it again")
                channel = null
                scope.launch(Dispatchers.Main) {
                    delay(1000)
                    if (channel == null && receiver != null) {
                        channel = openChannel()
                        checkExisting()
                    }
                }
            }
        }.getOrNull()
        return made
    }

    fun search() {
        if (searching) return
        searching = true
        searchStartedAt = android.os.SystemClock.elapsedRealtime()
        _state.value = "searching"
        DiagLog.i(tag, "WiFi Direct search started")
        scope.launch(Dispatchers.Main) { if (_link.value == null && connecting == null) discover() }
    }

    fun checkExisting() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    DiagLog.i(tag, "already on a WiFi Direct group, picking it up")
                    onGroup(info)
                }
            }
        }
    }

    private fun wantsServices(): Boolean {
        val want = prefs.carP2pName.trim()
        return want.startsWith("_") || (want.isEmpty() && android.os.SystemClock.elapsedRealtime() - searchStartedAt > SERVICES_AFTER_MS)
    }

    private fun discoverServices() {
        val m = manager ?: return
        val ch = channel ?: return
        if (servicesChannel !== ch) {
            runCatching {
                m.setDnsSdResponseListeners(ch,
                    { instance, type, device -> onService("dns-sd", instance.orEmpty(), type.orEmpty(), device) },
                    { domain, txt, device -> DiagLog.i(tag, "WiFi Direct service record from '${device?.deviceName}' (${device?.deviceAddress}): $domain $txt") })
                m.setUpnpServiceResponseListener(ch) { names, device -> onService("upnp", names.orEmpty().joinToString(), "", device) }
                m.clearServiceRequests(ch, null)
                m.addServiceRequest(ch, android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest.newInstance(), null)
                m.addServiceRequest(ch, android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest.newInstance(), null)
                servicesChannel = ch
                DiagLog.i(tag, "WiFi Direct: also looking for the car's CarLife service, not only its name")
            }.onFailure { DiagLog.w(tag, "WiFi Direct service search could not start: ${it.message}") }
        }
        m.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                DiagLog.d(tag, "discoverServices failed reason=$reason (${failureName(reason)})")
                if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) servicesChannel = null
            }
        })
    }

    private fun onService(kind: String, instance: String, type: String, device: WifiP2pDevice?) {
        val d = device ?: return
        val key = "${d.deviceAddress}|$instance"
        if (servicesSeen.add(key)) DiagLog.i(tag, "WiFi Direct $kind service '$instance' $type from '${d.deviceName}' (${d.deviceAddress})")
        if (_link.value != null || connecting != null) return
        val want = prefs.carP2pName.trim()
        if (want.isEmpty()) return
        val base = instance.substringBefore('.')
        val match = base.equals(want, true) || (base.isNotEmpty() && want.startsWith(base, true) && base.startsWith("_"))
        if (!match) return
        DiagLog.i(tag, "WiFi Direct: '${d.deviceName}' (${d.deviceAddress}) offers the car's service '$base', joining it")
        connect(Peer(d.deviceName ?: want, d.deviceAddress ?: return, d.status, d.isGroupOwner))
    }

    private fun describe(d: WifiP2pDevice) {
        val sig = "${d.deviceName}|${d.status}|${d.isGroupOwner}"
        if (peerDetails[d.deviceAddress] == sig) return
        peerDetails[d.deviceAddress ?: return] = sig
        DiagLog.d(tag, "WiFi Direct device '${d.deviceName}' ${d.deviceAddress} ${statusName(d.status)} owner=${d.isGroupOwner} type=${d.primaryDeviceType} " +
            "push-button=${d.wpsPbcSupported()} keypad=${d.wpsKeypadSupported()} display=${d.wpsDisplaySupported()} services=${d.isServiceDiscoveryCapable}" +
            if (Build.VERSION.SDK_INT >= 30) " wfd=${d.wfdInfo?.let { "on=${it.isEnabled} type=${it.deviceType}" } ?: "none"}" else "")
    }

    private fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (busyStreak > 0) DiagLog.i(tag, "WiFi Direct searching again after $busyStreak refusals")
                busyStreak = 0
                if (_link.value == null && connecting == null) _state.value = "searching"
            }

            override fun onFailure(reason: Int) {
                _state.value = "discovery failed ($reason)"
                if (reason == WifiP2pManager.BUSY) busyStreak++
                DiagLog.w(tag, "discoverPeers failed reason=$reason (${failureName(reason)})" +
                    if (reason == WifiP2pManager.BUSY && busyStreak == 3) "; the phone keeps refusing to search, usually because its hotspot is on or another app is using WiFi Direct (screen casting, Nearby Share)" else "")
            }
        })
    }

    private fun failureName(reason: Int) = when (reason) {
        WifiP2pManager.ERROR -> "error"
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $reason"
    }

    private fun handle(i: Intent) {
        val m = manager ?: return
        val ch = channel ?: return
        when (i.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                enabled = on
                if (on) servicesChannel = null
                DiagLog.i(tag, if (on) "WiFi Direct enabled" else "WiFi Direct disabled")
                if (!on) _state.value = "wifi direct off"
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> m.requestPeers(ch) { list ->
                list.deviceList.forEach { describe(it) }
                val ps = list.deviceList.map { Peer(it.deviceName ?: "", it.deviceAddress ?: "", it.status, it.isGroupOwner) }
                _peers.value = ps
                if (ps.isNotEmpty()) DiagLog.d(tag, "peers: " + ps.joinToString { "${it.name}[${statusName(it.status)}${if (it.groupOwner) ", group up" else ""}]" })
                autoMatch(ps)
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (info != null && info.groupFormed) {
                    onGroup(info)
                } else if (_link.value != null) {
                    _link.value = null
                    connecting = null
                    _state.value = "searching"
                    DiagLog.w(tag, "left the car group")
                    onLeft?.invoke()
                } else if (connecting != null && android.os.SystemClock.elapsedRealtime() - attemptAt > JoinWatch.GIVE_UP_MS) {
                    connecting = null
                }
            }
        }
    }

    private fun onGroup(info: WifiP2pInfo) {
        val m = manager ?: return
        val ch = channel ?: return
        val ip = info.groupOwnerAddress?.hostAddress
        if (ip == null) {
            DiagLog.d(tag, "group formed without an owner address yet, asking again")
            scope.launch(Dispatchers.Main) {
                delay(1000)
                if (_link.value == null) m.requestConnectionInfo(ch) { again -> if (again != null && again.groupFormed) onGroup(again) }
            }
            return
        }
        if (_link.value?.groupOwnerIp == ip) return
        m.requestGroupInfo(ch) { g ->
            val l = Link(ip, g?.`interface`, info.isGroupOwner, g?.networkName ?: connecting ?: "")
            connecting = null
            watchdog?.cancel()
            watchdog = null
            failures = 0
            _link.value = l
            _state.value = "joined ${l.name.ifBlank { ip }}"
            DiagLog.i(tag, "joined group owner=$ip iface=${l.iface} weAreOwner=${l.weAreOwner} network=${g?.networkName} ${g?.frequency ?: 0} MHz")
            onJoined?.invoke(l)
        }
    }

    private fun autoMatch(ps: List<Peer>) {
        if (_link.value != null || connecting != null) return
        val want = prefs.carP2pName.trim()
        val target = ps.firstOrNull { p ->
            if (want.isNotEmpty()) p.name.equals(want, true) || p.name.contains(want, true) else p.name.contains("carlife", true)
        }
        if (target == null) {
            val nearby = ps.joinToString { "'${it.name}'" }.ifBlank { "nothing" }
            val sig = "$want|$nearby"
            if (sig != lastNoMatch) {
                lastNoMatch = sig
                DiagLog.i(tag, if (want.isEmpty()) "WiFi Direct: FT does not know the car's name yet (the car sends it over bluetooth) and nothing nearby is called CarLife; nearby: $nearby"
                    else "WiFi Direct: the car '$want' is not among the nearby devices yet; nearby: $nearby")
            }
            return
        }
        val m = manager ?: return
        val ch = channel ?: return
        when (target.status) {
            WifiP2pDevice.INVITED -> {
                connecting = target.name
                attemptAt = android.os.SystemClock.elapsedRealtime()
                _state.value = "invited by ${target.name}"
                DiagLog.i(tag, "${target.name} already invited us, giving it ${JoinWatch.INVITE_MS / 1000}s")
                watch(target)
            }
            WifiP2pDevice.CONNECTED -> m.requestConnectionInfo(ch) { info -> if (info != null && info.groupFormed) onGroup(info) }
            else -> if (armed) connect(target)
        }
    }

    fun inSight(name: String): Boolean =
        _link.value != null || connecting != null || _peers.value.any { it.name.equals(name, true) || it.name.contains(name, true) }

    fun connectByName(name: String) {
        prefs.carP2pName = name
        armed = true
        val p = _peers.value.firstOrNull { it.name == name } ?: return
        connect(p)
    }

    fun carReady() {
        if (_link.value != null) return
        val name = prefs.carP2pName.trim()
        if (name.isEmpty()) return
        val attempting = connecting != null
        if (!JoinWatch.interruptForReady(android.os.SystemClock.elapsedRealtime() - attemptAt, attempting)) return
        val p = _peers.value.firstOrNull { it.name.equals(name, true) }
        if (p == null) {
            discover()
            return
        }
        if (p.status == WifiP2pDevice.CONNECTED) return
        DiagLog.i(tag, "the car says its WiFi Direct is ready, inviting it now")
        if (attempting) restart(p, "the car said it is ready", declined = true) else connect(p)
    }

    fun connect(p: Peer) {
        val m = manager ?: return
        val ch = channel ?: return
        if (_link.value != null || connecting != null) return
        connecting = p.name
        _state.value = "connecting to ${p.name}"
        DiagLog.i(tag, "connecting to ${p.name} (${p.address})")
        val fresh = runCatching {
            WifiP2pConfig.Builder()
                .setDeviceAddress(android.net.MacAddress.fromString(p.address))
                .enablePersistentMode(false)
                .build()
        }.getOrNull()
        val cfg = (fresh ?: WifiP2pConfig()).apply {
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
        attemptAt = android.os.SystemClock.elapsedRealtime()
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = watch(p)
            override fun onFailure(reason: Int) {
                _state.value = "connect failed ($reason)"
                DiagLog.w(tag, "connect to ${p.name} failed reason=$reason")
                restart(p, if (reason == WifiP2pManager.BUSY) "WiFi Direct was busy" else "the request failed ($reason)", cancel = false)
            }
        })
    }

    private fun watch(p: Peer) {
        watchdog?.cancel()
        watchdog = scope.launch(Dispatchers.Main) {
            while (isActive && _link.value == null && connecting == p.name) {
                val waited = android.os.SystemClock.elapsedRealtime() - attemptAt
                val status = _peers.value.firstOrNull { it.address.equals(p.address, true) }?.status
                when (JoinWatch.next(waited, status == WifiP2pDevice.CONNECTED, NetUtil.p2pGroupUp(), status == WifiP2pDevice.AVAILABLE)) {
                    JoinWatch.Next.RETRY -> {
                        restart(p, "no answer after ${waited / 1000}s")
                        return@launch
                    }
                    JoinWatch.Next.DECLINED -> {
                        restart(p, "the car turned the invitation down", declined = true)
                        return@launch
                    }
                    JoinWatch.Next.WAIT -> Unit
                }
                delay(500)
            }
        }
    }

    private fun restart(p: Peer, why: String, cancel: Boolean = true, declined: Boolean = false) {
        val m = manager ?: return
        val ch = channel ?: return
        watchdog?.cancel()
        watchdog = null
        failures++
        DiagLog.w(tag, "joining ${p.name}: $why, trying again (attempt ${failures + 1})")
        onRetry?.invoke(p.name, failures + 1)
        val again = {
            if (cancel) runCatching { m.removeGroup(ch, null) }
            if (JoinWatch.renewChannel(failures)) renewChannel()
            connecting = null
            discover()
            scope.launch(Dispatchers.Main) {
                delay(JoinWatch.retryDelayMs(failures, declined))
                if (enabled && _link.value == null && connecting == null) {
                    connect(_peers.value.firstOrNull { it.address.equals(p.address, true) } ?: p)
                }
            }
            Unit
        }
        if (!cancel) {
            again()
            return
        }
        runCatching {
            m.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { again() }
                override fun onFailure(reason: Int) { again() }
            })
        }.onFailure { again() }
    }

    private fun renewChannel() {
        val old = channel
        channel = openChannel()
        runCatching { old?.close() }
        DiagLog.i(tag, "WiFi Direct channel renewed after $failures failed joins")
    }

    fun stop() {
        loop?.cancel()
        loop = null
        watchdog?.cancel()
        watchdog = null
        failures = 0
        armed = false
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val m = manager
        val ch = channel
        channel = null
        if (m != null && ch != null) runCatching { m.stopPeerDiscovery(ch, null) }
        if (m != null && ch != null && servicesChannel === ch) runCatching { m.clearServiceRequests(ch, null) }
        servicesChannel = null
        servicesSeen.clear()
        peerDetails.clear()
        searching = false
        runCatching { ch?.close() }
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
