package app.ft.core

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.roundToInt

object Sizing {
    const val PHONE_WIDTH_DP = 384
    const val CAR_HEIGHT_DP = 480

    fun phone(base: Context): Configuration {
        val m = base.resources.displayMetrics
        val short = minOf(m.widthPixels, m.heightPixels)
        return Configuration().apply {
            fontScale = 1f
            if (short > 0) densityDpi = (short.toFloat() * DisplayMetrics.DENSITY_DEFAULT / PHONE_WIDTH_DP).roundToInt()
        }
    }

    fun carDpi(heightPx: Int): Int =
        (heightPx.toFloat() * DisplayMetrics.DENSITY_DEFAULT / CAR_HEIGHT_DP).roundToInt().coerceIn(120, 640)
}
