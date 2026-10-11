package app.ft.carlife

import app.ft.R

object WheelAction {
    const val NONE = 0
    const val ANDROID_AUTO = 1
    const val CAR_HOME = 2
    const val MAPS = 3
    const val PHONE_SCREEN = 4
    const val PLAY_PAUSE = 5
    const val VOICE = 6

    val ALL = listOf(NONE, ANDROID_AUTO, CAR_HOME, MAPS, PHONE_SCREEN, PLAY_PAUSE, VOICE)

    fun label(action: Int): Int = when (action) {
        ANDROID_AUTO -> R.string.tile_aa
        CAR_HOME -> R.string.wheel_action_home
        MAPS -> R.string.tile_maps
        PHONE_SCREEN -> R.string.tile_phone
        PLAY_PAUSE -> R.string.wheel_action_play
        VOICE -> R.string.wheel_action_voice
        else -> R.string.wheel_action_none
    }
}
