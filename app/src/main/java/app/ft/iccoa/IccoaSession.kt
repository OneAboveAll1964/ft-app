package app.ft.iccoa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.ft.core.DiagLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One independently owned ICCOA connection: GATT bootstrap, AP join, PIN SignIn and UCAR lanes.
 * Current AP credentials and agreement keys are never persisted. If owner chooses
 * automatic remembered-car reconnect, only the previously confirmed PIN is kept
 * encrypted under a non-exportable AndroidKeyStore key; never logged.
 * Status/PIN/closed callbacks run on main; ready and messages run on network workers.
 * [onReady] must synchronously open media listeners before returning (before AuthConfirm).
 */
@SuppressLint("MissingPermission")
class IccoaSession(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPinRequired: () -> Unit,
    private val onReady: (Network, String) -> Unit,
    private val onMessage: (Int, Int, ByteArray) -> Unit,
    private val onClosed: (String, Boolean) -> Unit,
    private val onControlReady: () -> Unit = {},
    private val trustedCarName: String = "",
    private val tryRememberedPin: Boolean = false
) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val trustedPin by lazy { IccoaTrustedPin(context) }
    @Volatile private var current: Attempt? = null
    val cipher: IccoaControl.Cipher? get() = current?.cipher

    private class Attempt(val profile: IccoaClientProfile) {
        val clientId get() = profile.id
        val resources = mutableListOf<Closeable>()
        val workers = Executors.newCachedThreadPool { Thread(it, "ICCOA-session") }
        val controlSender = sender("ICCOA-control-send", 32)
        val audioSender = sender("ICCOA-audio-send", 8)
        val sequence = AtomicInteger(1000)
        val serverInfo = ByteArrayOutputStream()
        @Volatile var closed = false
        @Volatile var cipher: IccoaControl.Cipher? = null
        @Volatile var control: Socket? = null
        @Volatile var audio: Socket? = null
        @Volatile var controlWritingAt = 0L
        @Volatile var audioWritingAt = 0L
        var firstHeartbeatWritten = false // Accessed only by the control sender.
        var gatt: BluetoothGatt? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var network: Network? = null
        var servicesRequested = false
        var servicesHandled = false
        var pinReady = false
        var pin: String? = null
        var pinAttempt = 0
        var reusingPin = false
        var wifiRequested = false
        var authStarted = false
        var hasWifiAddress = false
        var gatewayRetryScheduled = false

        companion object {
            private fun sender(name: String, capacity: Int) = ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
                { task -> Thread(task, name) }, ThreadPoolExecutor.AbortPolicy()
            )
        }
    }

    @Synchronized fun start(address: String) {
        stop()
        val attempt = Attempt(IccoaClientProfile.load(context))
        current = attempt
        post(attempt) {
            try {
                status(attempt, "Подключение ICCOA по Bluetooth…")
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                require(adapter?.isEnabled == true) { "Bluetooth disabled" }
                synchronized(attempt) {
                    if (!active(attempt)) return@post
                    attempt.gatt = adapter.getRemoteDevice(address).connectGatt(
                        context, false, bluetoothCallback(attempt), BluetoothDevice.TRANSPORT_LE
                    ) ?: error("GATT unavailable")
                }
                later(attempt, 20_000) {
                    // pinReady becomes false again while a submitted PIN is in flight.
                    // The initial Bluetooth watchdog must stop once bootstrap is ready.
                    if (!attempt.servicesHandled && !attempt.wifiRequested) fail(attempt, "Тайм-аут подключения ICCOA по Bluetooth", transportFailure = true)
                }
            } catch (e: Exception) { fail(attempt, "Не удалось подключиться по Bluetooth (${e.javaClass.simpleName})", transportFailure = true) }
        }
    }

    fun submitPin(pin: String) = submitPin(pin, fromCache = false)

    private fun submitPin(pin: String, fromCache: Boolean) {
        val attempt = current ?: return
        post(attempt) {
            if (!pin.matches(Regex("[0-9]{6}"))) {
                status(attempt, "Введите шестизначный код с экрана автомобиля")
                onPinRequired()
                return@post
            }
            if (!attempt.pinReady || attempt.wifiRequested) return@post
            try {
                val gatt = attempt.gatt ?: error("GATT unavailable")
                val characteristic = gatt.getService(SERVICE)?.getCharacteristic(CLIENT_INFO)
                    ?: error("Missing client characteristic")
                val payload = attempt.profile.clientInfo(pin, Build.MODEL, IccoaClientProfile.channel(context))
                require(payload.size <= 512) { "Client info too large" }
                attempt.pinReady = false
                attempt.pin = pin
                attempt.reusingPin = fromCache
                val revision = ++attempt.pinAttempt
                attempt.serverInfo.reset()
                status(attempt, "Проверка кода ICCOA…")
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                payload.fill(0)
                if (!accepted) {
                    if (fromCache) trustedPin.clear()
                    return@post requestPin(attempt, "Не удалось отправить код. Введите текущий PIN")
                }
                later(attempt, 12_000) {
                    if (!attempt.wifiRequested && revision == attempt.pinAttempt) {
                        if (attempt.reusingPin) {
                            trustedPin.clear()
                            DiagLog.w("ICCOA", "Remembered-car PIN rejected during BLE bootstrap, manual PIN required")
                        }
                        requestPin(attempt, "Автомобиль не принял код. Введите новый код с его экрана")
                    }
                }
            } catch (e: Exception) { fail(attempt, "Ошибка отправки кода ICCOA (${e.javaClass.simpleName})") }
        }
    }

    /** Explicit stop is silent; terminal transport failures invoke onClosed exactly once. */
    @Synchronized fun stop() {
        val attempt = current ?: return
        current = null
        close(attempt)
    }

    fun decryptInput(body: ByteArray): ByteArray =
        (cipher ?: error("ICCOA authentication is not complete")).decryptUibc(body)

    fun sendControl(method: Int, body: ByteArray): Boolean = current?.let { send(it, 1, method, body, 1) } ?: false
    fun sendAudio(method: Int, body: ByteArray): Boolean = current?.let { send(it, 4, method, body, 0) } ?: false

    private fun send(attempt: Attempt, category: Int, method: Int, body: ByteArray, format: Int): Boolean {
        if (!active(attempt)) return false
        val socket = (if (category == 4) attempt.audio else attempt.control) ?: return false
        val key = attempt.cipher ?: return false
        if (body.size > IccoaControl.MAX_FRAME_SIZE - 56) return false
        val copy = body.copyOf()
        try {
            (if (category == 4) attempt.audioSender else attempt.controlSender).execute {
                try {
                    if (!active(attempt)) return@execute
                    val frame = IccoaControl.frame(category, method, 0, key.encrypt(copy), attempt.sequence.getAndIncrement(), format)
                    if (category == 4) attempt.audioWritingAt = System.nanoTime() else attempt.controlWritingAt = System.nanoTime()
                    socket.getOutputStream().run { write(frame); flush() }
                    if (category == 1 && method == 1 && !attempt.firstHeartbeatWritten) {
                        attempt.firstHeartbeatWritten = true
                        DiagLog.i("ICCOA", "CONTROL first-heartbeat written")
                    } else if (category == 1 && method != 1) {
                        DiagLog.i("ICCOA", "CONTROL written method=$method bytes=${copy.size}")
                    }
                } catch (e: Exception) {
                    if (active(attempt)) DiagLog.w("ICCOA", "SEND category=$category method=$method ${IccoaTransportError.describe(e)}")
                    fail(attempt, "Канал ICCOA $category закрыт (${e.javaClass.simpleName})", transportFailure = true)
                } finally {
                    copy.fill(0)
                    if (category == 4) attempt.audioWritingAt = 0 else attempt.controlWritingAt = 0
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            copy.fill(0)
            // Audio backpressure drops one packet; control messages must not disappear silently.
            if (category != 4 && active(attempt)) fail(attempt, "Переполнена очередь управления ICCOA")
            return false
        }
    }

    private fun bluetoothCallback(attempt: Attempt) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT connection status=$status state=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(512)) discover(attempt, gatt)
                later(attempt, 2_000) { discover(attempt, gatt) }
            } else if (!attempt.wifiRequested && (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)) {
                fail(attempt, "Соединение Bluetooth ICCOA закрыто (status=$status)", transportFailure = true)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT MTU=$mtu status=$status")
            discover(attempt, gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt || attempt.servicesHandled) return@post
            DiagLog.i("ICCOA", "GATT services status=$status")
            try {
                require(status == BluetoothGatt.GATT_SUCCESS)
                val service = gatt.getService(SERVICE) ?: error("ICCOA service missing")
                val incoming = service.getCharacteristic(SERVER_INFO) ?: error("Server info missing")
                require(service.getCharacteristic(CLIENT_INFO) != null)
                require(gatt.setCharacteristicNotification(incoming, true))
                attempt.servicesHandled = true
                // The observed Carlink bootstrap enables local notification routing and
                // proceeds directly to CLIENT_INFO. XA60 stalls on the extra CCCD write;
                // do not introduce an ATT operation absent from that exchange.
                DiagLog.i("ICCOA", "GATT ICCOA channel ready; checking remembered-car credentials")
                val cached = if (tryRememberedPin && trustedCarName.isNotBlank())
                    trustedPin.load(attempt.profile.id, trustedCarName) else null
                if (cached != null) {
                    attempt.pinReady = true
                    DiagLog.i("ICCOA", "One remembered-car authentication attempt; manual fallback on rejection")
                    submitPin(cached, fromCache = true)
                } else requestPin(attempt)
            } catch (e: Exception) { fail(attempt, "Устройство не предоставило канал ICCOA (${e.javaClass.simpleName})") }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT characteristic write uuid=${characteristic.uuid} status=$status")
            if (gatt === attempt.gatt && characteristic.uuid == CLIENT_INFO && status != BluetoothGatt.GATT_SUCCESS && !attempt.wifiRequested) {
                if (attempt.reusingPin) {
                    trustedPin.clear()
                    DiagLog.w("ICCOA", "Remembered-car BLE PIN rejected; requesting fresh PIN")
                }
                requestPin(attempt, "Автомобиль отклонил запись кода (status=$status)")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
        }
    }

    private fun discover(attempt: Attempt, gatt: BluetoothGatt) {
        if (gatt !== attempt.gatt || attempt.servicesRequested) return
        attempt.servicesRequested = true
        try {
            if (!gatt.discoverServices()) fail(attempt, "Не удалось запросить службы ICCOA")
        } catch (e: Exception) { fail(attempt, "Ошибка служб Bluetooth (${e.javaClass.simpleName})") }
    }

    private fun requestPin(attempt: Attempt, message: String = "Введите шестизначный код с экрана автомобиля") {
        attempt.pin = null
        attempt.reusingPin = false
        attempt.pinReady = true
        status(attempt, message)
        onPinRequired()
    }

    private fun receiveBootstrap(attempt: Attempt, gatt: BluetoothGatt, fragment: ByteArray) = post(attempt) {
        try {
            if (gatt !== attempt.gatt || attempt.wifiRequested || attempt.pin == null) return@post
            DiagLog.i("ICCOA", "GATT server-info fragment received: ${fragment.size} bytes")
            if (fragment.firstOrNull() == '{'.code.toByte()) attempt.serverInfo.reset()
            require(attempt.serverInfo.size() + fragment.size <= 4096)
            attempt.serverInfo.write(fragment)
            if (fragment.toString(Charsets.UTF_8).trimEnd().lastOrNull() != '}') return@post
            val raw = attempt.serverInfo.toByteArray()
            val info = try { JSONObject(raw.toString(Charsets.UTF_8)) } finally { raw.fill(0); attempt.serverInfo.reset() }
            require(info.optInt("type", 1001) == 1002) {
                "P2P_UNSUPPORTED"
            }
            joinWifi(attempt, info)
        } catch (e: Exception) {
            fail(attempt, if (e.message == "P2P_UNSUPPORTED")
                "Автомобиль запросил Wi-Fi Direct. Эта версия ICCOA поддерживает только режим точки доступа (AP)"
            else "Некорректные параметры Wi-Fi ICCOA (${e.javaClass.simpleName})")
        } finally { fragment.fill(0) }
    }

    private fun joinWifi(attempt: Attempt, info: JSONObject) {
        val specifier = WifiNetworkSpecifier.Builder().setSsid(info.getString("ssid"))
            .setWpa2Passphrase(info.getString("psk")).apply {
                val mac = info.optString("mac")
                if (mac.isNotBlank()) setBssid(MacAddress.fromString(mac))
            }.build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post(attempt) {
                attempt.network = network
                // Link properties may not yet exist in onAvailable; onLinkPropertiesChanged retries.
                connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = post(attempt) {
                networkReady(attempt, network, properties)
            }
            override fun onUnavailable() { fail(attempt, "Подключение к Wi-Fi автомобиля отменено или истёк тайм-аут") }
            override fun onLost(network: Network) { fail(attempt, "Соединение с Wi-Fi автомобиля потеряно", transportFailure = true) }
        }
        attempt.wifiRequested = true
        attempt.networkCallback = callback
        status(attempt, "Подключение к Wi-Fi автомобиля. Подтвердите запрос Android")
        try {
            connectivity.requestNetwork(request, callback, 60_000)
        } catch (e: SecurityException) {
            fail(attempt, "Android запретил подключение к Wi-Fi автомобиля (SecurityException)")
            return
        } catch (e: Exception) {
            fail(attempt, "Не удалось запросить Wi-Fi автомобиля (${e.javaClass.simpleName})", transportFailure = true)
            return
        }
        later(attempt, 65_000) {
            if (!attempt.authStarted) fail(attempt, if (attempt.hasWifiAddress)
                "Wi-Fi автомобиля подключён, но не получен проверенный адрес шлюза"
            else "Не получен IPv4-адрес Wi-Fi автомобиля", transportFailure = true)
        }
    }

    private fun networkReady(attempt: Attempt, network: Network, properties: LinkProperties) {
        if (attempt.authStarted) return
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        val address = link.address as Inet4Address
        val local = address.hostAddress ?: return
        attempt.hasWifiAddress = true
        val routed = properties.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }?.gateway
        // On concurrent Wi-Fi Android returns this requesting app's specifier connection.
        // Still reject a stale DHCP snapshot unless its local address/subnet match this Network.
        @Suppress("DEPRECATION")
        val fallback = if (routed == null) runCatching {
            context.getSystemService(WifiManager::class.java)?.dhcpInfo?.let {
                IccoaWifiGateway.fromDhcp(address, link.prefixLength, it.ipAddress, it.gateway)
            }
        }.getOrNull() else null
        val peer = (routed ?: fallback)?.hostAddress
        if (peer == null) {
            if (!attempt.gatewayRetryScheduled) {
                attempt.gatewayRetryScheduled = true
                status(attempt, "Wi-Fi подключён. Ожидание адреса шлюза автомобиля…")
                later(attempt, 1_000) {
                    attempt.gatewayRetryScheduled = false
                    post(attempt) {
                        connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
                    }
                }
            }
            return
        }
        DiagLog.i("ICCOA", "Wi-Fi gateway source=${if (routed != null) "routes" else "validated-dhcp"}")
        val pin = attempt.pin ?: return fail(attempt, "Код ICCOA утрачен. Подключитесь заново")
        attempt.authStarted = true
        attempt.network = network
        attempt.pin = null
        status(attempt, "Проверка подлинности ICCOA…")
        worker(attempt) { authenticate(attempt, network, local, peer, pin) }
    }

    private fun authenticate(attempt: Attempt, network: Network, local: String, peer: String, pin: String) {
        var stage = "tcp-bind"
        fun progress(value: String) {
            stage = value
            if (active(attempt)) DiagLog.i("ICCOA", "AUTH $value")
        }
        try {
            val socket = Socket()
            if (!track(attempt, socket)) return
            network.bindSocket(socket)
            progress("tcp-connect")
            socket.connect(InetSocketAddress(peer, 57209), 8_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            progress("build-request")
            val auth = IccoaAuth(pin, attempt.clientId, Build.MODEL, IccoaClientIdentity.load())
            progress("send-request")
            socket.getOutputStream().run { write(IccoaControl.frame(3, 1, 1, auth.request(), 1)); flush() }
            progress("read-response")
            val response = IccoaControl.read(socket.getInputStream()) { header ->
                if (active(attempt)) DiagLog.i("ICCOA", "AUTH header $header")
            }
            // The reference accepts the first auth-lane response without specifying method/type.
            // Its protobuf transcript is still authenticated below before any listener is exposed.
            if (response.category != 3 || response.format != 1) throw IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_LANE)
            val key = auth.accept(response.body, ::progress)
            synchronized(attempt) {
                if (!active(attempt)) return
                attempt.cipher = key
            }
            progress("open-control-listeners")
            for (port in intArrayOf(57219, 57229, 57239, 57249, 57259)) listen(attempt, local, peer, port)
            if (!active(attempt)) return
            // External callbacks may acquire their owner's lifecycle lock. Never hold ours here.
            progress("open-media-listeners")
            onReady(network, peer)
            if (!active(attempt)) return
            progress("send-confirm")
            socket.getOutputStream().run { write(IccoaControl.frame(3, 3, 0, auth.confirm(key, local), 2)); flush() }
            if (trustedCarName.isNotBlank()) {
                val stored = trustedPin.save(attempt.profile.id, trustedCarName, pin)
                DiagLog.i("ICCOA", "Authenticated car credential storage: " +
                    if (stored) "ENCRYPTED" else "UNAVAILABLE")
            }
            status(attempt, "ICCOA: аутентификация завершена, ожидание каналов автомобиля…")
            later(attempt, 25_000) {
                if (attempt.control == null) fail(attempt, "Автомобиль не открыл канал управления ICCOA")
            }
            watchWrites(attempt)
            // Auth is a bootstrap lane: receivers may close it after confirmation.
            socket.soTimeout = 0
            try { while (active(attempt)) IccoaControl.read(socket.getInputStream()) } catch (_: Exception) { }
            socket.close()
        } catch (e: Exception) {
            if (!active(attempt)) return
            if (attempt.reusingPin) {
                trustedPin.clear()
                DiagLog.w("ICCOA", "Remembered-car authentication rejected. Fresh PIN needed on next connection")
            }
            // Exception messages may contain peer data. Log only our fixed codes and stack locations.
            val reason = (e as? IccoaHandshakeException)?.diagnostic ?: e.javaClass.simpleName
            val location = e.stackTrace.firstOrNull { it.className.startsWith("app.ft.iccoa.") }
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            DiagLog.w("ICCOA", "AUTH failed stage=$stage reason=$reason at=$location")
            val hint = when ((e as? IccoaHandshakeException)?.reason) {
                IccoaHandshakeException.Reason.PIN_MAC -> "Код и ответ автомобиля не совпали. Начните новое подключение с текущим кодом"
                IccoaHandshakeException.Reason.RESPONSE_RESULT -> when (e.result) {
                    1L -> "Автомобиль запросил новое подключение с кодом"
                    2L -> "Автомобиль запросил подтверждение на своём экране"
                    3L -> "Автомобиль сообщил о внутренней ошибке"
                    4L -> "Автомобиль отклонил режим подключения"
                    else -> "Автомобиль отклонил подключение"
                }
                else -> "Отправьте отчёт из FT"
            }
            fail(attempt, "Сбой ICCOA на этапе $stage ($reason). $hint")
        }
    }

    private fun listen(attempt: Attempt, local: String, peer: String, port: Int) {
        val lane = IccoaLaneServer(port, InetSocketAddress(local, port), InetAddress.getByName(peer),
            onConnected = { socket ->
                synchronized(attempt) {
                    if (active(attempt)) {
                        if (port == IccoaLaneServer.CONTROL_PORT) {
                            attempt.control = socket
                            val heartbeat = IccoaHeartbeat(
                                send = { body -> send(attempt, 1, 1, body, 1) },
                                schedule = { delay, action -> later(attempt, delay, action) })
                            attempt.resources += heartbeat
                            DiagLog.i("ICCOA", "CONTROL starting heartbeat on accept")
                            heartbeat.start()
                        }
                        if (port == IccoaLaneServer.AUDIO_PORT) attempt.audio = socket
                    }
                }
                if (port == IccoaLaneServer.CONTROL_PORT && active(attempt)) onControlReady()
            },
            onDisconnected = { socket ->
                synchronized(attempt) {
                    if (attempt.control === socket) attempt.control = null
                    if (attempt.audio === socket) attempt.audio = null
                }
            },
            onFrame = { frame -> handleFrame(attempt, frame) },
            log = { message -> if (active(attempt)) DiagLog.i("ICCOA", message) })
        if (!track(attempt, lane)) return
        worker(attempt) {
            try {
                lane.run()
            } catch (e: Exception) { fail(attempt, "Канал ICCOA $port закрыт (${e.javaClass.simpleName})", transportFailure = true) }
        }
    }

    private fun handleFrame(attempt: Attempt, frame: IccoaControl.Frame) {
        if (!active(attempt)) return
        val key = attempt.cipher ?: error("Session key unavailable")
        // Empty/plain protocol messages exist in the reference; encrypted envelopes fail closed.
        val body = if (IccoaControl.isEncrypted(frame.body)) key.decrypt(frame.body) else frame.body
        if (!active(attempt)) return
        if (frame.category == 1 && frame.method == 23) {
            fail(attempt, "Автомобиль завершил соединение ICCOA")
            return
        }
        if (frame.category != 1 || frame.method != 1) onMessage(frame.category, frame.method, body)
    }

    private fun watchWrites(attempt: Attempt): Unit = post(attempt) {
        val now = System.nanoTime()
        if (listOf(attempt.controlWritingAt, attempt.audioWritingAt).any { it > 0 && now - it > TimeUnit.SECONDS.toNanos(5) }) {
            fail(attempt, "Тайм-аут отправки данных ICCOA")
        } else {
            later(attempt, 2_000) { watchWrites(attempt) }
        }
    }

    private fun active(attempt: Attempt) = current === attempt && !attempt.closed
    private fun post(attempt: Attempt, action: () -> Unit) {
        main.post {
            if (active(attempt)) try { action() } catch (e: Exception) {
                fail(attempt, "Ошибка подключения ICCOA (${e.javaClass.simpleName})")
            }
        }
    }
    private fun later(attempt: Attempt, delay: Long, action: () -> Unit) {
        main.postAtTime({ if (active(attempt)) action() }, attempt, android.os.SystemClock.uptimeMillis() + delay)
    }
    private fun status(attempt: Attempt, message: String) = post(attempt) {
        DiagLog.i("ICCOA", message)
        onStatus(message)
    }
    private fun worker(attempt: Attempt, action: () -> Unit) {
        try { attempt.workers.execute { if (active(attempt)) action() } } catch (_: RejectedExecutionException) { }
    }
    private fun track(attempt: Attempt, resource: Closeable): Boolean = synchronized(attempt) {
        if (!active(attempt)) { runCatching { resource.close() }; false }
        else { attempt.resources += resource; true }
    }

    private fun fail(attempt: Attempt, message: String, transportFailure: Boolean = false) = post(attempt) {
        synchronized(this) {
            if (!active(attempt)) return@post
            current = null
            close(attempt)
        }
        onClosed(message, transportFailure)
    }

    private fun close(attempt: Attempt) {
        synchronized(attempt) {
            attempt.closed = true
            attempt.cipher = null
            attempt.pin = null
            attempt.resources.forEach { runCatching { it.close() } }
            attempt.resources.clear()
            attempt.control = null
            attempt.audio = null
        }
        main.removeCallbacksAndMessages(attempt)
        attempt.workers.shutdownNow()
        attempt.controlSender.shutdownNow()
        attempt.audioSender.shutdownNow()
        val releaseAndroidResources = {
            runCatching { attempt.gatt?.disconnect() }
            runCatching { attempt.gatt?.close() }
            attempt.gatt = null
            attempt.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            attempt.networkCallback = null
            attempt.network = null
            attempt.serverInfo.reset()
            DiagLog.i("ICCOA", "Session resources released: Bluetooth, Wi-Fi request and sockets")
        }
        // Main-thread callers must release Android handles before a new scan/attempt starts.
        if (Looper.myLooper() == main.looper) releaseAndroidResources()
        else main.post { releaseAndroidResources() }
    }

    companion object {
        private val SERVICE = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100")
        private val CLIENT_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101")
        private val SERVER_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102")
    }
}
