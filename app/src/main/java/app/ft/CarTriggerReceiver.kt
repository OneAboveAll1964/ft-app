package app.ft

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.ft.carlife.CarLifeService
import app.ft.core.DiagLog
import app.ft.core.Prefs

class CarTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.autoConnect) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DiagLog.i("Trigger", "starting after ${intent.action}")
                CarLifeService.startAuto(context)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val name = runCatching { dev?.name }.getOrNull() ?: ""
                val want = prefs.carBtName.trim()
                if (want.isEmpty() || name.contains(want, true)) {
                    DiagLog.i("Trigger", "bluetooth connected to '${name.ifBlank { "device" }}', starting")
                    CarLifeService.startAuto(context, dev?.address)
                }
            }
        }
    }
}
