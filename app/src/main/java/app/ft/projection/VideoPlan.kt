package app.ft.projection

data class VideoPlan(
    val contentWidth: Int,
    val contentHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val fps: Int,
    val bitrate: Int,
    val qpFloor: Int = 0
)

object VideoPlans {
    const val SIZE_BAIDU = 0
    const val SIZE_CAR = 1
    const val SIZE_CUSTOM = 2
    const val START_FPS = 20
    const val DEFAULT_BITRATE = 3_000_000
    const val LOWEST_PACE = 15

    fun plan(
        carWidth: Int,
        carHeight: Int,
        askedFps: Int,
        newVehicle: Boolean,
        sizeMode: Int,
        customWidth: Int,
        customHeight: Int,
        forcedFps: Int,
        floorFps: Int,
        bitrate: Int,
        qpFloor: Int = 0
    ): VideoPlan {
        val cw = even(if (carWidth > 0) carWidth else 1280).coerceIn(320, 4096)
        val ch = even(if (carHeight > 0) carHeight else 720).coerceIn(240, 2160)
        val (sw, sh) = when {
            sizeMode == SIZE_CAR -> cw to ch
            sizeMode == SIZE_CUSTOM && customWidth > 0 && customHeight > 0 ->
                even(customWidth).coerceIn(320, 4096) to even(customHeight).coerceIn(240, 2160)
            else -> baiduSize(cw, ch, newVehicle)
        }
        return VideoPlan(cw, ch, sw, sh, startFps(askedFps, forcedFps, floorFps), if (bitrate > 0) bitrate else baiduBitrate(sw), qpFloor.coerceIn(0, 51))
    }

    fun baiduSize(carWidth: Int, carHeight: Int, newVehicle: Boolean): Pair<Int, Int> {
        val wide = newVehicle && carHeight > 0 && carWidth.toFloat() / carHeight >= 2.3f
        return when {
            carWidth < 800 -> 768 to 432
            carWidth < 1024 -> if (wide) 1024 to 384 else 848 to 480
            carWidth < 1280 -> if (wide) 1024 to 384 else 1024 to 576
            carWidth < 1920 -> if (wide) 1280 to 480 else 1280 to 720
            newVehicle -> if (wide) 1920 to 720 else 1920 to 1080
            else -> 1280 to 720
        }
    }

    fun baiduBitrate(streamWidth: Int): Int = when {
        streamWidth <= 768 -> 1_150_000
        streamWidth <= 848 -> 1_280_000
        streamWidth <= 1024 -> 1_920_000
        streamWidth <= 1280 -> 2_560_000
        else -> 3_840_000
    }

    fun startFps(asked: Int, forced: Int, floor: Int): Int {
        if (forced > 0) return forced.coerceIn(1, 60)
        val base = if (asked > 2) asked else START_FPS
        return maxOf(base, floor).coerceIn(1, 60)
    }

    fun rateChange(asked: Int, forced: Int, floor: Int): Int? {
        if (asked !in 3..30) return null
        if (forced > 0) return forced.coerceIn(1, 60)
        return maxOf(asked, floor).coerceIn(1, 60)
    }

    fun newVehicle(major: Int, minor: Int): Boolean = major == 4 || (major == 3 && minor == 2)

    fun encoderKeys(hardware: String, socModel: String, sdk: Int, brand: String, board: String, qpFloor: Int = 0): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val hw = hardware.uppercase()
        val soc = socModel.uppercase()
        when (hw) {
            "MTK" -> {
                if (soc != "MT6985" && soc != "MT6983") {
                    if (sdk >= 31) out["video-qp-p-max"] = 37
                    else if (sdk == 30 && brand.equals("vivo", true)) out["vendor.vivo-mtk-omx.qp-max"] = 37
                }
                if (sdk >= 31) {
                    out["video-qp-p-max"] = 16
                    out["video-qp-i-min"] = 16
                }
                if (sdk == 30 || sdk == 29) out["isWfdVideo"] = 1
            }
            "QCOM" -> when (soc) {
                "SM8850" -> {
                    out["color-standard"] = 2
                    out["priority"] = 2
                    out["video-qp-i-max"] = 32
                    out["video-qp-i-min"] = 22
                    out["video-qp-p-min"] = 22
                    out["vendor.qti-ext-enc-low-latency.enable"] = 1
                }
                "SDM870", "SM8350", "SM8450", "SM8475", "SM8550" -> Unit
                else -> {
                    if (sdk >= 31) out["video-qp-p-max"] = 40
                    else if (sdk == 30) out["vendor.qti-ext-enc-qp-range.qp-p-max"] = 40
                }
            }
            "SAMSUNG" -> {
                if (sdk >= 31) out["video-qp-p-max"] = 36
                else if (sdk == 30) out["vendor.sec-ext-enc-qp-range.P-maxQP"] = 36
            }
        }
        if (board == "s5e9815") {
            out["vendor.sec-ext-enc-qp-range.I-minQP"] = 23
            out["vendor.sec-ext-enc-qp-range.P-minQP"] = 14
        }
        if (hardware.lowercase().startsWith("mt")) {
            out["video-qp-max"] = 35
            out["video-qp-min"] = 16
        }
        if (qpFloor > 0 && sdk >= 31) {
            if ("video-qp-i-min" !in out) out["video-qp-i-min"] = qpFloor
            val pMax = out["video-qp-p-max"]
            if ("video-qp-p-min" !in out && (pMax == null || pMax >= qpFloor)) out["video-qp-p-min"] = qpFloor
        }
        return out
    }

    private fun even(v: Int) = v - (v and 1)
}
