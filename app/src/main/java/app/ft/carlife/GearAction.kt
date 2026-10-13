package app.ft.carlife

import app.ft.R

object GearAction {
    const val NONE = 0
    const val CAR_SCREEN = 1
    const val FT_HOME = 2
    const val MAPS = 3
    const val ANDROID_AUTO = 4

    val ALL = listOf(NONE, CAR_SCREEN, FT_HOME, MAPS, ANDROID_AUTO)

    const val SOUND_KEEP = 0
    const val SOUND_PAUSE = 1
    const val SOUND_LOWER = 2

    val SOUNDS = listOf(SOUND_KEEP, SOUND_PAUSE, SOUND_LOWER)

    val LEVELS = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80)

    const val GEAR_PARK = 2
    const val GEAR_REVERSE = 5

    fun restores(action: Int): Boolean = action == CAR_SCREEN

    fun label(action: Int): Int = when (action) {
        CAR_SCREEN -> R.string.gear_action_car_screen
        FT_HOME -> R.string.wheel_action_home
        MAPS -> R.string.tile_maps
        ANDROID_AUTO -> R.string.tile_aa
        else -> R.string.wheel_action_none
    }

    fun soundLabel(sound: Int): Int = when (sound) {
        SOUND_PAUSE -> R.string.gear_sound_pause
        SOUND_LOWER -> R.string.gear_sound_lower
        else -> R.string.gear_sound_keep
    }
}

class GearSound {
    data class Move(val level: Float, val pause: Boolean, val resume: Boolean)

    var level = 1f
        private set
    var paused = false
        private set

    fun shift(sound: Int, lowerTo: Int, playing: Boolean): Move {
        val want = if (sound == GearAction.SOUND_LOWER) lowerTo.coerceIn(0, 100) / 100f else 1f
        val pause = sound == GearAction.SOUND_PAUSE && playing && !paused
        val resume = sound != GearAction.SOUND_PAUSE && paused
        if (pause) paused = true
        if (resume) paused = false
        level = if (paused) minOf(level, want) else want
        return Move(level, pause, resume)
    }

    fun settle(): Boolean {
        if (!paused || level >= 1f) return false
        level = 1f
        return true
    }

    fun forget() {
        level = 1f
        paused = false
    }
}
