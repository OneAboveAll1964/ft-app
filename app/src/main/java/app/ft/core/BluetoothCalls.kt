package app.ft.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class BluetoothCallsStatus { CHECKING, CONNECTED, DISCONNECTED, OFF, PERMISSION_REQUIRED, UNAVAILABLE }

data class BluetoothCallsState(
    val status: BluetoothCallsStatus = BluetoothCallsStatus.CHECKING,
    val devices: List<String> = emptyList()
)

/** Observes the real HFP profile; BLE discovery and an ACL connection are not call audio. */
@SuppressLint("MissingPermission")
class BluetoothCalls(
    context: Context,
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val hasPermission: () -> Boolean = {
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(BluetoothCallsState())
    val state: StateFlow<BluetoothCallsState> = mutableState
    private var profile: BluetoothProfile? = null
    private var serviceAvailable = false
    private var listener: BluetoothProfile.ServiceListener? = null
    private var registered = false
    private var closed = false
    private val timeout = Runnable {
        if (!closed && mutableState.value.status == BluetoothCallsStatus.CHECKING) {
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }
    private fun requestProfile() {
        val callback = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                if (closed || listener !== this) {
                    runCatching { adapter?.closeProfileProxy(id, proxy) }
                    return
                }
                handler.removeCallbacks(timeout)
                profile?.takeIf { it !== proxy }?.let {
                    runCatching { adapter?.closeProfileProxy(id, it) }
                }
                profile = proxy
                serviceAvailable = true
                refresh()
            }

            override fun onServiceDisconnected(id: Int) {
                if (!closed && listener === this) {
                    // Keep the current binding for Android's transient service reconnect.
                    serviceAvailable = false
                    refresh()
                }
            }
        }
        listener = callback
        beginWait()
        if (adapter?.getProfileProxy(context, callback, BluetoothProfile.HEADSET) != true) {
            releaseProfile()
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }

    private fun beginWait() {
        publish(BluetoothCallsState())
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 5_000)
    }

    private fun releaseProfile() {
        handler.removeCallbacks(timeout)
        // Invalidate pending callbacks before closing the old binding.
        listener = null
        serviceAvailable = false
        val previous = profile
        profile = null
        previous?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    fun start() {
        if (closed) return
        if (!registered) {
            runCatching {
                // Bluetooth broadcasts originate from a privileged process outside the system UID.
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }.onFailure { DiagLog.w("BluetoothCalls", "HFP broadcasts unavailable: ${it.javaClass.simpleName}") }
        }
        refresh()
    }

    fun refresh() {
        if (closed) return
        try {
            when {
                !hasPermission() -> {
                    releaseProfile()
                    publish(BluetoothCallsState(BluetoothCallsStatus.PERMISSION_REQUIRED))
                }
                adapter == null -> publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
                !adapter.isEnabled -> {
                    releaseProfile()
                    publish(BluetoothCallsState(BluetoothCallsStatus.OFF))
                }
                profile != null && serviceAvailable -> {
                    val devices = profile!!.connectedDevices
                    publish(BluetoothCallsState(
                        if (devices.isEmpty()) BluetoothCallsStatus.DISCONNECTED else BluetoothCallsStatus.CONNECTED,
                        devices.map { it.name?.takeIf(String::isNotBlank) ?: it.address }.sorted()
                    ))
                }
                listener == null -> requestProfile()
                mutableState.value.status != BluetoothCallsStatus.CHECKING &&
                    mutableState.value.status != BluetoothCallsStatus.UNAVAILABLE -> beginWait()
            }
        } catch (_: SecurityException) {
            releaseProfile()
            publish(BluetoothCallsState(BluetoothCallsStatus.PERMISSION_REQUIRED))
        } catch (e: Exception) {
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
            DiagLog.w("BluetoothCalls", "HFP query failed: ${e.javaClass.simpleName}")
        }
    }

    private fun publish(value: BluetoothCallsState) {
        if (mutableState.value != value) {
            mutableState.value = value
            DiagLog.i("BluetoothCalls", "HFP ${value.status}; devices=${value.devices.joinToString().ifBlank { "none" }}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        releaseProfile()
    }
}
