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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private var recovery: Job? = null
    private var reopen: Job? = null
    private var lifecycle = 0L
    private var attempt = 0L
    private var peersVersion = 0L
    private var groupVersion = 0L
    private var stopDiagnostics: (() -> Unit)? = null
    private var failures = 0
    private var lastNoMatch = ""
    @Volatile private var searchStartedAt = 0L
    @Volatile var busyStreak = 0
        private set
    @Volatile var myName = ""
        private set
    private var servicesChannel: WifiP2pManager.Channel? = null
    private val servicesSeen = HashSet<String>()
    private val peerDetails = HashMap<String, String>()
    private val SERVICES_AFTER_MS = 8_000L
    private val NARROW_AFTER_MS = 2_000L
    private val NARROW = listOf(5745, 5785, 5765, 5180, 5200, 5220, 5240, 5805, 5825)
    @Volatile private var namedAt = 0L
    private var notedMatch = ""
    private var narrowNoted = false
    private var narrowIndex = 0
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    private val _state = MutableStateFlow("off")
    private val _link = MutableStateFlow<Link?>(null)
    var onJoined: ((Link) -> Unit)? = null
    var onLeft: (() -> Unit)? = null
    var onRetry: ((name: String, attempt: Int) -> Unit)? = null
    var onJoinTimeout: ((seconds: Int) -> Unit)? = null

    fun start() {
        val m = manager
        if (m == null) {
            _state.value = "wifi direct unavailable"
            DiagLog.w(tag, "WiFi Direct unavailable on this device")
            return
        }
        if (channel != null || receiver != null) return
        lifecycle++
        val life = lifecycle
        val ch = openChannel()
        if (ch == null) {
            _state.value = "wifi direct unavailable"
            DiagLog.w(tag, "WiFi Direct channel could not be created")
            return
        }
        channel = ch
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (lifecycle == life && receiver === this) handle(i)
            }
        }
        receiver = r
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
        stopDiagnostics = P2pDiagnostics.register(context, m) {
            "attempt=$attempt peer=${connecting ?: "none"} recovering=${recovery != null}" +
                if (connecting != null) " elapsed=${android.os.SystemClock.elapsedRealtime() - attemptAt}ms" else ""
        }
        _state.value = "ready"
        loop = scope.launch(Dispatchers.Main) {
            var tick = 0
            while (isActive) {
                if (searching && enabled && recovery == null && _link.value == null && connecting == null) {
                    when {
                        narrowDue(tick) -> discoverOn(NARROW[narrowIndex++ % NARROW.size])
                        wantsServices() && tick % 2 == 1 -> discoverServices()
                        else -> discover()
                    }
                    tick++
                }
                delay(JoinWatch.DISCOVER_EVERY_MS)
            }
        }
        checkExisting()
    }

    private fun openChannel(): WifiP2pManager.Channel? {
        val m = manager ?: return null
        val life = lifecycle
        var made: WifiP2pManager.Channel? = null
        made = runCatching {
            m.initialize(context, Looper.getMainLooper()) {
                if (lifecycle != life || channel !== made) return@initialize
                DiagLog.w(tag, "WiFi Direct channel lost, opening it again")
                cancelAttempt()
                _peers.value = emptyList()
                peersVersion++
                channel = null
                reopen?.cancel()
                reopen = scope.launch(Dispatchers.Main) {
                    delay(1000)
                    if (lifecycle == life && channel == null && receiver != null) {
                        channel = openChannel()
                        checkExisting()
                        if (searching && enabled) discover()
                    }
                }
            }
        }.getOrNull()
        return made
    }

    private fun current(ch: WifiP2pManager.Channel, life: Long) =
        lifecycle == life && receiver != null && channel === ch

    private fun cancelAttempt() {
        attempt++
        watchdog?.cancel()
        watchdog = null
        recovery?.cancel()
        recovery = null
        connecting = null
    }

    fun search() {
        if (searching) return
        searching = true
        searchStartedAt = android.os.SystemClock.elapsedRealtime()
        _state.value = "searching"
        DiagLog.i(tag, "WiFi Direct search started")
        val life = lifecycle
        scope.launch(Dispatchers.Main) {
            if (lifecycle == life && receiver != null && recovery == null && _link.value == null && connecting == null) discover()
        }
    }

    fun checkExisting() {
        val m = manager ?: return
        val ch = channel ?: return
        val life = lifecycle
        val group = groupVersion
        runCatching {
            m.requestConnectionInfo(ch) { info ->
                if (current(ch, life) && enabled && groupVersion == group && recovery == null && info != null && info.groupFormed) {
                    DiagLog.i(tag, "already on a WiFi Direct group, picking it up")
                    onGroup(info)
                }
            }
        }
    }

    private fun narrowDue(tick: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !armed || tick % 3 != 2) return false
        if (android.os.SystemClock.elapsedRealtime() - namedAt < NARROW_AFTER_MS) return false
        if (CarMatch.pick(_peers.value, prefs.carP2pName, prefs.carBtAddress) != null) return false
        return runCatching { manager?.isChannelConstrainedDiscoverySupported == true }.getOrDefault(false)
    }

    @SuppressLint("NewApi")
    private fun discoverOn(mhz: Int) {
        val m = manager ?: return
        val ch = channel ?: return
        if (!narrowNoted) {
            narrowNoted = true
            DiagLog.i(tag, "WiFi Direct: this phone has not seen the car's group yet, also searching the channels cars use one at a time (${NARROW.joinToString()} MHz)")
        }
        runCatching {
            m.discoverPeersOnSpecificFrequency(ch, mhz, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) { DiagLog.d(tag, "search on $mhz MHz failed reason=$reason (${failureName(reason)})") }
            })
        }.onFailure { DiagLog.d(tag, "search on $mhz MHz not possible: ${it.message}") }
    }

    private fun wantsServices(): Boolean {
        val want = prefs.carP2pName.trim()
        return want.startsWith("_") || (want.isEmpty() && android.os.SystemClock.elapsedRealtime() - searchStartedAt > SERVICES_AFTER_MS)
    }

    private fun discoverServices() {
        val m = manager ?: return
        val ch = channel ?: return
        val life = lifecycle
        if (servicesChannel !== ch) {
            runCatching {
                m.setDnsSdResponseListeners(ch,
                    { instance, type, device -> if (current(ch, life)) onService("dns-sd", instance.orEmpty(), type.orEmpty(), device) },
                    { domain, _, device -> if (current(ch, life)) DiagLog.i(tag, "WiFi Direct service record from '${device?.deviceName}': $domain") })
                m.setUpnpServiceResponseListener(ch) { names, device ->
                    if (current(ch, life)) onService("upnp", names.orEmpty().joinToString(), "", device)
                }
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
        if (!enabled || recovery != null || _link.value != null || connecting != null) return
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
        if (!enabled || recovery != null || connecting != null || _link.value != null) return
        val life = lifecycle
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!current(ch, life)) return
                if (busyStreak > 0) DiagLog.i(tag, "WiFi Direct searching again after $busyStreak refusals")
                busyStreak = 0
                if (_link.value == null && connecting == null) _state.value = "searching"
            }

            override fun onFailure(reason: Int) {
                if (!current(ch, life)) return
                _state.value = "discovery failed ($reason)"
                if (reason == WifiP2pManager.BUSY) busyStreak++
                DiagLog.w(tag, "discoverPeers failed reason=$reason (${failureName(reason)})" +
                    if (reason == WifiP2pManager.BUSY && busyStreak == 3) "; the phone keeps refusing to search, usually because its hotspot is on or another app is using WiFi Direct (screen casting, Nearby Share)" else "")
            }
        })
    }

    private fun failureName(reason: Int) = when (reason) {
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
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
                if (!on) {
                    cancelAttempt()
                    groupVersion++
                    peersVersion++
                    _peers.value = emptyList()
                    _state.value = "wifi direct off"
                    if (_link.value != null) {
                        _link.value = null
                        onLeft?.invoke()
                    }
                } else if (searching) discover()
            }
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> DiagLog.d(tag,
                "discovery state=${i.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)} attempt=$attempt recovering=${recovery != null}")
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val me: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                me?.deviceName?.takeIf { it.isNotBlank() }?.let { myName = it }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                @Suppress("DEPRECATION")
                val network: android.net.NetworkInfo? = i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                DiagLog.d(tag, "connection changed: formed=${info?.groupFormed} weAreOwner=${info?.isGroupOwner} " +
                    "network=${network?.detailedState} attempt=$attempt recovering=${recovery != null}")
                if (info != null && info.groupFormed) {
                    onGroup(info)
                } else {
                    groupVersion++
                    if (_link.value != null) {
                        cancelAttempt()
                        peersVersion++
                        _peers.value = emptyList()
                        _link.value = null
                        _state.value = "searching"
                        DiagLog.w(tag, "left the car group")
                        onLeft?.invoke()
                    }
                }
            }
        }
    }

    private fun refreshPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        val life = lifecycle
        val version = ++peersVersion
        m.requestPeers(ch) { list ->
            if (!current(ch, life) || version != peersVersion) return@requestPeers
            list.deviceList.forEach { describe(it) }
            val ps = list.deviceList.map { Peer(it.deviceName ?: "", it.deviceAddress ?: "", it.status, it.isGroupOwner) }
            _peers.value = ps
            DiagLog.d(tag, "peers: " + ps.joinToString { "${it.name}[${statusName(it.status)}${if (it.groupOwner) ", group up" else ""}]" }.ifBlank { "none" })
            autoMatch(ps)
        }
    }

    private fun onGroup(info: WifiP2pInfo) {
        val m = manager ?: return
        val ch = channel ?: return
        if (!enabled || recovery != null) return
        val life = lifecycle
        val id = attempt
        val group = groupVersion
        val ip = info.groupOwnerAddress?.hostAddress
        if (ip == null) {
            DiagLog.d(tag, "group formed without an owner address yet, asking again")
            scope.launch(Dispatchers.Main) {
                delay(1000)
                if (current(ch, life) && groupVersion == group && attempt == id && recovery == null && _link.value == null) m.requestConnectionInfo(ch) { again ->
                    if (current(ch, life) && groupVersion == group && attempt == id && recovery == null && again != null && again.groupFormed) onGroup(again)
                }
            }
            return
        }
        if (_link.value?.groupOwnerIp == ip) return
        m.requestGroupInfo(ch) { g ->
            if (!current(ch, life) || groupVersion != group || attempt != id || recovery != null || _link.value?.groupOwnerIp == ip) return@requestGroupInfo
            if (g == null) return@requestGroupInfo
            val l = Link(ip, g.`interface`, info.isGroupOwner, g.networkName ?: connecting ?: "")
            connecting = null
            watchdog?.cancel()
            watchdog = null
            failures = 0
            _link.value = l
            _state.value = "joined ${l.name.ifBlank { ip }}"
            DiagLog.i(tag, "joined group owner=$ip iface=${l.iface} weAreOwner=${l.weAreOwner} network=${g.networkName} ${g.frequency} MHz")
            onJoined?.invoke(l)
        }
    }

    private fun theCar(ps: List<Peer>, want: String = prefs.carP2pName): Peer? {
        val hit = CarMatch.pick(ps, want, prefs.carBtAddress) ?: return null
        val (p, why) = hit
        if (why != CarMatch.Why.NAME && notedMatch != p.address) {
            notedMatch = p.address
            DiagLog.i(tag, when (why) {
                CarMatch.Why.ADDRESS -> "WiFi Direct: '${p.name}' (${p.address}) does not carry the car's name, but its address belongs to the car's bluetooth radio (${prefs.carBtAddress}), so it is the car"
                else -> "WiFi Direct: the car named itself '${want.trim()}' over bluetooth but its group (${p.address}) has no name; it is the only nameless group nearby, so it is taken as the car"
            })
        }
        return p
    }

    private fun autoMatch(ps: List<Peer>) {
        if (!enabled || recovery != null || _link.value != null || connecting != null) return
        val want = prefs.carP2pName.trim()
        val target = theCar(ps, want)
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
        when (target.status) {
            WifiP2pDevice.INVITED -> {
                attempt++
                connecting = target.name
                attemptAt = android.os.SystemClock.elapsedRealtime()
                _state.value = "invitation pending for ${target.name}"
                val timeoutMs = prefs.carJoinTimeoutSeconds * 1_000L
                DiagLog.i(tag, "invitation pending for ${target.name}, allowing ${timeoutMs / 1000}s (INVITED does not identify who initiated it)")
                watch(target, timeoutMs)
            }
            WifiP2pDevice.CONNECTED -> checkExisting()
            else -> if (armed) connect(target)
        }
    }

    fun inSight(name: String): Boolean =
        _link.value != null || connecting != null || theCar(_peers.value, name) != null

    fun connectByName(name: String) {
        prefs.carP2pName = name
        armed = true
        namedAt = android.os.SystemClock.elapsedRealtime()
        val p = theCar(_peers.value, name) ?: return
        connect(p)
    }

    fun carReady() {
        if (_link.value != null) return
        val name = prefs.carP2pName.trim()
        if (name.isEmpty()) return
        if (!JoinWatch.actOnCarReady(connecting != null || recovery != null)) return
        val p = theCar(_peers.value, name)
        if (p == null) {
            discover()
            return
        }
        if (p.status == WifiP2pDevice.CONNECTED) return
        DiagLog.i(tag, "received another wireless notification from the car; trying its discovered WiFi Direct peer")
        connect(p)
    }

    fun connect(p: Peer) {
        val m = manager ?: return
        val ch = channel ?: return
        if (!enabled || recovery != null || _link.value != null || connecting != null) return
        val life = lifecycle
        val id = ++attempt
        connecting = p.name
        _state.value = "connecting to ${p.name}"
        val timeoutMs = prefs.carJoinTimeoutSeconds * 1_000L
        DiagLog.i(tag, "connecting to ${p.name} (${p.address}), join timeout ${timeoutMs / 1000}s")
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
        DiagLog.d(tag, "join attempt=$id peerStatus=${statusName(p.status)} peerOwner=${p.groupOwner} " +
            "wps=${if (cfg.wps.setup == WpsInfo.PBC) "push-button" else "keypad"} ownerIntent=${cfg.groupOwnerIntent} persistentSave=false")
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!current(ch, life) || attempt != id || connecting != p.name) return
                DiagLog.d(tag, "join attempt=$id accepted by Android; waiting for group formation")
                watch(p, timeoutMs)
            }
            override fun onFailure(reason: Int) {
                if (!current(ch, life) || attempt != id || connecting != p.name) return
                _state.value = "connect failed ($reason)"
                DiagLog.w(tag, "connect to ${p.name} failed reason=$reason (${failureName(reason)}) attempt=$id")
                restart(p, if (reason == WifiP2pManager.BUSY) "WiFi Direct was busy" else "the request failed ($reason)", cancel = false)
            }
        })
    }

    private fun watch(p: Peer, timeoutMs: Long) {
        watchdog?.cancel()
        val id = attempt
        watchdog = scope.launch(Dispatchers.Main) {
            while (isActive && attempt == id && _link.value == null && connecting == p.name) {
                val waited = android.os.SystemClock.elapsedRealtime() - attemptAt
                val status = _peers.value.firstOrNull { it.address.equals(p.address, true) }?.status
                when (JoinWatch.next(waited, status == WifiP2pDevice.CONNECTED, NetUtil.p2pGroupUp(), status == WifiP2pDevice.AVAILABLE, timeoutMs)) {
                    JoinWatch.Next.RETRY -> {
                        DiagLog.w(tag, "join attempt=$id timed out after ${waited}ms peerStatus=${status?.let(::statusName) ?: "not in latest list"} " +
                            "groupInterfaceUp=${NetUtil.p2pGroupUp()}")
                        onJoinTimeout?.invoke((waited / 1000).toInt())
                        restart(p, "no answer after ${waited / 1000}s")
                        return@launch
                    }
                    JoinWatch.Next.DECLINED -> {
                        restart(p, "the peer is available again without a formed group", declined = true)
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
        val life = lifecycle
        val id = ++attempt
        watchdog?.cancel()
        watchdog = null
        recovery?.cancel()
        connecting = null
        peersVersion++
        _peers.value = emptyList()
        failures++
        _state.value = "recovering connection"
        DiagLog.w(tag, "joining ${p.name}: $why; recovering before retry ${failures + 1}")
        onRetry?.invoke(p.name, failures + 1)
        recovery = scope.launch(Dispatchers.Main) {
            if (cancel) {
                while (isActive && current(ch, life) && enabled && attempt == id) {
                    if (clearGroup(m, ch)) break
                    DiagLog.w(tag, "WiFi Direct cleanup not confirmed; waiting before trying cleanup again")
                    delay(JoinWatch.DISCOVER_EVERY_MS)
                }
            }
            if (!current(ch, life) || !enabled || attempt != id) return@launch
            if (JoinWatch.renewChannel(failures)) renewChannel()
            delay(JoinWatch.retryDelayMs(failures, declined))
            if (lifecycle != life || receiver == null || !enabled || attempt != id) return@launch
            peersVersion++
            _peers.value = emptyList()
            recovery = null
            _state.value = "searching"
            DiagLog.i(tag, "recovery complete; waiting for the car in a fresh WiFi Direct peer list")
            refreshPeers()
            discover()
        }
    }

    private suspend fun clearGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        val cancelled = awaitAction("cancelConnect") { m.cancelConnect(ch, it) }
        val removed = if (cancelled == -1 || cancelled == WifiP2pManager.ERROR) {
            awaitAction("removeGroup") { m.removeGroup(ch, it) }
        } else null
        try {
            return withTimeoutOrNull(5_000) {
                while (true) {
                    val gone = CompletableDeferred<Boolean>()
                    try {
                        m.requestGroupInfo(ch) { group -> gone.complete(group == null) }
                        if (gone.await()) {
                            if (cancelled == -1 && removed == -1) break
                            if (!idleNetwork(m, ch)) return@withTimeoutOrNull false
                            DiagLog.d(tag, "WiFi Direct cleanup confirmed by idle network after an uncertain action result")
                            break
                        }
                    } finally {
                        gone.cancel()
                    }
                    delay(250)
                }
                DiagLog.d(tag, "WiFi Direct cleanup confirmed: no group")
                true
            } ?: false
        } catch (e: SecurityException) {
            DiagLog.w(tag, "group query permission denied: ${e.javaClass.simpleName}")
            return false
        } catch (e: IllegalArgumentException) {
            DiagLog.w(tag, "group query unavailable: ${e.javaClass.simpleName}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun idleNetwork(m: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        val idle = CompletableDeferred<Boolean>()
        try {
            m.requestNetworkInfo(ch) { network: android.net.NetworkInfo? ->
                idle.complete(network?.detailedState in setOf(
                    android.net.NetworkInfo.DetailedState.IDLE,
                    android.net.NetworkInfo.DetailedState.DISCONNECTED,
                    android.net.NetworkInfo.DetailedState.FAILED
                ))
            }
            return withTimeoutOrNull(2_000) { idle.await() } == true && !NetUtil.p2pGroupUp()
        } finally {
            idle.cancel()
        }
    }

    private suspend fun awaitAction(name: String, action: (WifiP2pManager.ActionListener) -> Unit): Int? {
        val result = CompletableDeferred<Int>()
        try {
            action(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { result.complete(-1) }
                override fun onFailure(reason: Int) { result.complete(reason) }
            })
            val reason = withTimeoutOrNull(2_000) { result.await() }
            DiagLog.d(tag, "$name: " + when (reason) {
                null -> "callback timed out"
                -1 -> "completed"
                else -> "failed reason=$reason (${failureName(reason)})"
            })
            return reason
        } catch (e: SecurityException) {
            DiagLog.w(tag, "$name permission denied: ${e.javaClass.simpleName}")
            return null
        } catch (e: IllegalArgumentException) {
            DiagLog.w(tag, "$name unavailable: ${e.javaClass.simpleName}")
            return null
        } finally {
            result.cancel()
        }
    }

    private fun renewChannel() {
        val old = channel
        channel = openChannel()
        runCatching { old?.close() }
        DiagLog.i(tag, "WiFi Direct channel renewed after $failures failed joins")
    }

    fun stop() {
        lifecycle++
        loop?.cancel()
        loop = null
        cancelAttempt()
        reopen?.cancel()
        reopen = null
        stopDiagnostics?.invoke()
        stopDiagnostics = null
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
        peersVersion++
        _peers.value = emptyList()
        _link.value = null
        searching = false
        namedAt = 0L
        notedMatch = ""
        narrowNoted = false
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
