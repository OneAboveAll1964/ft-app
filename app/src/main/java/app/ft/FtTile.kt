package app.ft

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.core.DiagLog
import app.ft.core.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FtTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val made = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = made
        made.launch { CarLifeService.state.collect { draw() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val on = FTApp.instance.prefs.autoConnect
        if (on) {
            DiagLog.i(TAG, "switched off from the quick settings")
            CarLifeService.switchOff(applicationContext)
        } else {
            DiagLog.i(TAG, "switched on from the quick settings")
            CarLifeService.switchOn(applicationContext)
        }
        draw()
    }

    private fun draw() {
        val tile = qsTile ?: return
        val on = FTApp.instance.prefs.autoConnect
        val car = CarLifeService.state.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_ft)
        tile.label = Lang.str(R.string.app_name)
        tile.subtitle = Lang.str(
            when {
                !on -> R.string.state_off
                car.session is CarLifeSession.State.Projecting -> R.string.tile_on_the_car
                car.running -> R.string.tile_looking
                else -> R.string.state_on
            }
        )
        tile.contentDescription = "${tile.label} ${tile.subtitle}"
        runCatching { tile.updateTile() }
    }

    companion object {
        private const val TAG = "Tile"

        fun offer(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val bar = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
            runCatching {
                bar.requestAddTileService(
                    ComponentName(context, FtTile::class.java),
                    Lang.str(R.string.app_name),
                    Icon.createWithResource(context, R.drawable.ic_tile_ft),
                    { it.run() },
                    { DiagLog.i(TAG, "quick settings answered $it") }
                )
            }.onFailure { DiagLog.w(TAG, "could not ask to add the quick settings button: ${it.message}") }
        }
    }
}
