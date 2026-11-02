package app.ft.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Opt-in recovery of lost classic audio profiles, shared by the projection backends.
 * A bonded-device list identifies the target; only a fresh discovery result proves proximity.
 * All state is confined to the main dispatcher, including Android Bluetooth callbacks.
 */
@SuppressLint("MissingPermission")
class CarBluetoothReconnect(
    context: Context,
    private val prefs: Prefs,
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val hasPermission: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        } else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    },
    private val requestConnection: (BluetoothProfile, BluetoothDevice) -> Boolean = { proxy, device ->
        // Android has no public API for initiating A2DP/HFP connections. Some phones refuse this.
        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) == true
    }
) : AutoCloseable {
    private val context = context.applicationContext
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private val listeners = mutableMapOf<Int, BluetoothProfile.ServiceListener>()
    private val connected = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()
    private var job: Job? = null
    private var generation = 0
    private var found: CompletableDeferred<Unit>? = null
    private var ownDiscovery = false
    private var registered = false
    private var started = false
    private var closed = false
    private var target = ""
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "carBtReconnect" || key == "carBtAddress") scope.launch { refresh() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            try {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
                    BluetoothDevice.ACTION_FOUND -> device(intent)?.let(::onDeviceFound)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> device(intent)?.let { car ->
                        if (isTarget(car) && connected.isNotEmpty()) {
                            missing.addAll(connected)
                            connected.clear()
                            recover()
                        }
                    }
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> device(intent)?.let { car ->
                        onConnectionChanged(
                            if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) BluetoothProfile.A2DP else BluetoothProfile.HEADSET,
                            car, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1),
                            intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                        )
                    }
                }
            } catch (e: SecurityException) {
                cancelRecovery()
                DiagLog.w(TAG, "Bluetooth permission unavailable")
            }
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        prefs.registerListener(preferenceListener)
        refresh()
    }

    internal fun refresh() {
        if (closed) return
        if (!prefs.carBtReconnect) {
            disable()
            return
        }
        if (target != prefs.carBtAddress) {
            cancelRecovery()
            connected.clear()
            target = prefs.carBtAddress
        }
        if (!registered) {
            runCatching {
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }.onFailure { DiagLog.w(TAG, "Bluetooth events unavailable: ${it.javaClass.simpleName}") }
        }
        if (!available()) {
            cancelRecovery()
            releaseProfiles()
            connected.clear()
            return
        }
        for (id in PROFILE_IDS) {
            if (id in listeners) continue
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (closed || !prefs.carBtReconnect || listeners[id] !== this) {
                        runCatching { adapter?.closeProfileProxy(profile, proxy) }
                        return
                    }
                    profiles.put(id, proxy)?.takeIf { it !== proxy }?.let {
                        runCatching { adapter?.closeProfileProxy(id, it) }
                    }
                    runCatching {
                        proxy.connectedDevices.forEach { onConnectionChanged(id, it, BluetoothProfile.STATE_CONNECTED, -1) }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    // Keep the binding so Android can deliver a replacement proxy after a service restart.
                }
            }
            listeners[id] = listener
            if (runCatching { adapter?.getProfileProxy(context, listener, id) }.getOrNull() != true) listeners.remove(id)
        }
    }

    internal fun onConnectionChanged(id: Int, car: BluetoothDevice, state: Int, previous: Int) {
        if (closed || !prefs.carBtReconnect || !available()) return
        if (state == BluetoothProfile.STATE_CONNECTED) rememberCar(car)
        if (!isTarget(car)) return
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                connected.add(id)
                missing.remove(id)
                if (missing.isEmpty()) cancelRecovery()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                val wasConnected = connected.remove(id)
                if (wasConnected || previous == BluetoothProfile.STATE_CONNECTED) {
                    missing.add(id)
                    recover()
                }
            }
        }
    }

    private fun rememberCar(car: BluetoothDevice) {
        val known = isTarget(car)
        val named = prefs.carBtName.isNotBlank() && car.name?.contains(prefs.carBtName, ignoreCase = true) == true
        val automotive = car.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
        // Never adopt arbitrary headphones just because they are paired or connected.
        if (car.bondState == BluetoothDevice.BOND_BONDED && (known || named || automotive)) {
            if (!known) {
                cancelRecovery()
                connected.clear()
                target = car.address
                prefs.carBtAddress = target
            }
        }
    }

    internal fun onDeviceFound(car: BluetoothDevice) {
        if (!closed && prefs.carBtReconnect && isTarget(car) && car.bondState == BluetoothDevice.BOND_BONDED) found?.complete(Unit)
    }

    private fun isTarget(car: BluetoothDevice) = target.isNotBlank() && car.address.equals(target, ignoreCase = true)
    private fun available() = runCatching { !closed && prefs.carBtReconnect && hasPermission() && adapter?.isEnabled == true }.getOrDefault(false)

    private fun recover() {
        if (job?.isActive == true || missing.isEmpty() || !available() || !registered) return
        val address = target
        val car = runCatching { adapter?.bondedDevices?.firstOrNull { it.address.equals(address, true) } }.getOrNull() ?: return
        val ticket = ++generation
        job = scope.launch {
            try {
                for (attempt in 1..3) {
                    delay(if (attempt == 1) 3_000 else 15_000)
                    if (!available() || target != address) break
                    refresh()
                    missing.removeAll { profiles[it]?.getConnectionState(car) == BluetoothProfile.STATE_CONNECTED }
                    if (missing.isEmpty() || car.bondState != BluetoothDevice.BOND_BONDED) break
                    val sighting = CompletableDeferred<Unit>()
                    found = sighting
                    if (adapter?.isDiscovering != true) ownDiscovery = adapter?.startDiscovery() == true
                    DiagLog.i(TAG, "Looking for last car, recovery $attempt/3")
                    val seen = withTimeoutOrNull(15_000) { sighting.await(); true } == true
                    found = null
                    stopDiscovery()
                    // Do not interrupt discovery started by another app or connect during discovery.
                    repeat(20) { if (adapter?.isDiscovering == true) delay(100) }
                    if (!seen || adapter?.isDiscovering == true || !available() || target != address) continue
                    if (car.bondState != BluetoothDevice.BOND_BONDED) break
                    for (id in missing.toList()) {
                        val proxy = profiles[id] ?: continue
                        val state = proxy.getConnectionState(car)
                        if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) continue
                        val accepted = runCatching { requestConnection(proxy, car) }.getOrDefault(false)
                        DiagLog.i(TAG, "Profile $id recovery $attempt/3 requested=$accepted")
                    }
                    // An accepted request is not a connection; wait for the real profile event.
                    delay(8_000)
                    if (missing.isEmpty()) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.w(TAG, "Bluetooth recovery stopped: ${e.javaClass.simpleName}")
            } finally {
                if (ticket == generation) {
                    found = null
                    stopDiscovery()
                    missing.clear()
                }
            }
        }
    }

    private fun stopDiscovery() {
        if (ownDiscovery) runCatching { adapter?.cancelDiscovery() }
        ownDiscovery = false
    }

    private fun cancelRecovery() {
        generation++
        job?.cancel()
        job = null
        found = null
        missing.clear()
        stopDiscovery()
    }

    private fun releaseProfiles() {
        listeners.clear() // Invalidates callbacks from bindings still being acquired.
        profiles.toMap().forEach { (id, proxy) -> runCatching { adapter?.closeProfileProxy(id, proxy) } }
        profiles.clear()
    }

    private fun disable() {
        cancelRecovery()
        connected.clear()
        releaseProfiles()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    override fun close() {
        if (closed) return
        closed = true
        if (started) prefs.unregisterListener(preferenceListener)
        disable()
        scope.cancel()
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        const val TAG = "BtReconnect"
        val PROFILE_IDS = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
    }
}
