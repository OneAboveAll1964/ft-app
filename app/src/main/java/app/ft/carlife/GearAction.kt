package app.ft.carlife

import app.ft.R

object GearAction {
    const val NONE = 0
    const val CAR_SCREEN = 1
    const val FT_HOME = 2
    const val MAPS = 3
    const val ANDROID_AUTO = 4

    val ALL = listOf(NONE, CAR_SCREEN, FT_HOME, MAPS, ANDROID_AUTO)

    // Firmware gear values (CarlifeGearInfo): 1=N 2=P 3=D 4=L 5=R
    const val GEAR_PARK = 2
    const val GEAR_REVERSE = 5

    // CAR_SCREEN hands the car back to its own screen (so the reverse camera shows) and FT
    // brings projection back when the car leaves that gear.
    fun restores(action: Int): Boolean = action == CAR_SCREEN

    fun label(action: Int): Int = when (action) {
        CAR_SCREEN -> R.string.gear_action_car_screen
        FT_HOME -> R.string.wheel_action_home
        MAPS -> R.string.tile_maps
        ANDROID_AUTO -> R.string.tile_aa
        else -> R.string.wheel_action_none
    }
}
