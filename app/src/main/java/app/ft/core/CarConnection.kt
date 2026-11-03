package app.ft.core

import android.content.Context
import app.ft.FTApp
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.iccoa.IccoaService
import app.ft.supercast.SuperCast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App entry points select a backend here. Each backend owns its own connection lifecycle. */
object CarConnection {
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    val isBusy: Boolean get() = CarLifeService.isBusy || IccoaService.isBusy ||
        AaHeadUnitService.isBusy || SuperCast.isBusy

    /** Called at dispatch, creation and after teardown, including failed starts. */
    internal fun lifecycleChanged() { mutableBusy.value = isBusy }

    fun startAuto(context: Context) {
        val prefs = FTApp.instance.prefs
        if (!prefs.autoConnect) return
        when (prefs.carProtocol) {
            CarProtocol.CARLIFE -> {
                IccoaService.stop(context)
                CarLifeService.startAuto(context)
            }
            CarProtocol.ICCOA -> {
                if (CarLifeService.state.value.running) CarLifeService.stop(context)
                IccoaService.start(context)
            }
        }
    }

    fun switchOn(context: Context) {
        FTApp.instance.prefs.autoConnect = true
        startAuto(context)
    }

    fun switchOff(context: Context) {
        FTApp.instance.prefs.autoConnect = false
        if (CarLifeService.isBusy) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.isBusy) AaHeadUnitService.stop(context)
        SuperCast.stopAll()
    }

    fun selectProtocol(context: Context, protocol: CarProtocol) {
        val prefs = FTApp.instance.prefs
        if (prefs.carProtocol == protocol) return
        val wasOn = prefs.autoConnect || CarLifeService.state.value.running || IccoaService.state.value.running
        prefs.carProtocol = protocol
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        DiagLog.i("Connection", "Selected ${protocol.id}; restart=$wasOn")
        if (wasOn) switchOn(context)
    }

    /** Apply a restored protocol/auto-connect preference without keeping the old backend alive. */
    fun restart(context: Context) {
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        startAuto(context)
    }
}
