package app.ft

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import app.ft.carlife.CarLifeService
import app.ft.carlife.UsbCar
import app.ft.core.DiagLog

class UsbCarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(i: Intent?) {
        val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            else @Suppress("DEPRECATION") i?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        if (acc == null) {
            DiagLog.w("USB", "the car's USB arrived without its accessory")
            return
        }
        DiagLog.i("USB", "the car opened CarLife over USB: ${UsbCar.describe(acc)}")
        if (!FTApp.instance.prefs.autoConnect) {
            DiagLog.i("USB", "FT is switched off, leaving the car's USB alone")
            return
        }
        CarLifeService.useUsb(this, acc)
    }
}
