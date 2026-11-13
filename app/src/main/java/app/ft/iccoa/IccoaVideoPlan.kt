package app.ft.iccoa

import app.ft.projection.VideoPlan

/** Keep the receiver's geometry, but avoid encoding duplicate AA frames at 60 Hz. */
internal object IccoaVideoPlan {
    fun from(config: IccoaVideoConfig): VideoPlan {
        val width = config.width and -2
        val height = config.height and -2
        // AVC Level 4.0 max: 8,192 macroblocks/frame and 245,760/sec.
        // 1920x1080@30 => 8,160/frame and 244,800/sec.
        val capNative = width > 1920 || height > 1080
        val streamWidth = if (capNative) 1920 else width
        val streamHeight = if (capNative) 1080 else height
        return VideoPlan(width, height, streamWidth, streamHeight, config.fps.coerceIn(1, 30),
            8_000_000, constantBitrate = true, preserveAspect = capNative)
    }
}
