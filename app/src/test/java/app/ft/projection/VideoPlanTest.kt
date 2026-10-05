package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanTest {
    @Test
    fun corollaGetsWhatBaiduSends() {
        val plan = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_BAIDU, 0, 0, 0, 0, 0)
        assertEquals(1920, plan.contentWidth)
        assertEquals(720, plan.contentHeight)
        assertEquals(1280, plan.streamWidth)
        assertEquals(720, plan.streamHeight)
        assertEquals(20, plan.fps)
        assertEquals(2_560_000, plan.bitrate)
    }

    @Test
    fun baiduSizesFollowTheCarWidth() {
        assertEquals(768 to 432, VideoPlans.baiduSize(720, 480, false))
        assertEquals(848 to 480, VideoPlans.baiduSize(800, 480, false))
        assertEquals(1024 to 576, VideoPlans.baiduSize(1024, 600, false))
        assertEquals(1280 to 720, VideoPlans.baiduSize(1280, 720, false))
        assertEquals(1280 to 720, VideoPlans.baiduSize(1920, 1080, false))
        assertEquals(1920 to 1080, VideoPlans.baiduSize(1920, 1080, true))
        assertEquals(1920 to 720, VideoPlans.baiduSize(1920, 720, true))
        assertEquals(1280 to 480, VideoPlans.baiduSize(1600, 600, true))
    }

    @Test
    fun bitrateFollowsTheStreamWidth() {
        assertEquals(1_150_000, VideoPlans.baiduBitrate(768))
        assertEquals(1_280_000, VideoPlans.baiduBitrate(848))
        assertEquals(1_920_000, VideoPlans.baiduBitrate(1024))
        assertEquals(2_560_000, VideoPlans.baiduBitrate(1280))
        assertEquals(3_840_000, VideoPlans.baiduBitrate(1920))
    }

    @Test
    fun ownSizeAndCustomSizeAreKept() {
        val own = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_CAR, 0, 0, 0, 0, 0)
        assertEquals(1920 to 720, own.streamWidth to own.streamHeight)
        assertEquals(3_840_000, own.bitrate)
        val custom = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_CUSTOM, 1281, 481, 0, 0, 1_500_000)
        assertEquals(1280 to 480, custom.streamWidth to custom.streamHeight)
        assertEquals(1_500_000, custom.bitrate)
        val unset = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_CUSTOM, 0, 0, 0, 0, 0)
        assertEquals(1280 to 720, unset.streamWidth to unset.streamHeight)
    }

    @Test
    fun startRateFollowsTheCarThenTheSettings() {
        assertEquals(20, VideoPlans.startFps(0, 0, 0))
        assertEquals(20, VideoPlans.startFps(2, 0, 0))
        assertEquals(30, VideoPlans.startFps(30, 0, 0))
        assertEquals(24, VideoPlans.startFps(30, 24, 0))
        assertEquals(25, VideoPlans.startFps(0, 0, 25))
    }

    @Test
    fun rateChangesOutsideThreeToThirtyAreRefused() {
        assertNull(VideoPlans.rateChange(2, 0, 0, 20))
        assertNull(VideoPlans.rateChange(31, 0, 0, 20))
        assertNull(VideoPlans.rateChange(60, 0, 0, 20))
        assertEquals(15, VideoPlans.rateChange(15, 0, 0, 20))
        assertEquals(29, VideoPlans.rateChange(29, 0, 0, 20))
        assertEquals(24, VideoPlans.rateChange(29, 24, 0, 20))
        assertEquals(20, VideoPlans.rateChange(16, 0, 20, 30))
    }

    @Test
    fun asksBelowFifteenKeepThePaceLikeBaidu() {
        assertEquals(27, VideoPlans.rateChange(3, 0, 0, 27))
        assertEquals(20, VideoPlans.rateChange(10, 0, 0, 20))
        assertEquals(24, VideoPlans.rateChange(5, 0, 24, 20))
        assertEquals(12, VideoPlans.rateChange(5, 12, 0, 20))
    }

    @Test
    fun newerHeadUnits() {
        assertFalse(VideoPlans.newVehicle(1, 0))
        assertFalse(VideoPlans.newVehicle(3, 1))
        assertTrue(VideoPlans.newVehicle(3, 2))
        assertTrue(VideoPlans.newVehicle(4, 0))
    }

    @Test
    fun encoderTuningMatchesBaiduPerChip() {
        assertEquals(mapOf("video-qp-p-max" to 40), VideoPlans.encoderKeys("qcom", "SM8750", 36, "samsung", "sun"))
        assertEquals(emptyMap<String, Int>(), VideoPlans.encoderKeys("qcom", "SM8550", 36, "samsung", "kalama"))
        assertEquals(22, VideoPlans.encoderKeys("qcom", "SM8850", 36, "x", "y")["video-qp-i-min"])
        assertEquals(mapOf("vendor.qti-ext-enc-qp-range.qp-p-max" to 40), VideoPlans.encoderKeys("qcom", "", 30, "x", "y"))
        assertEquals(35, VideoPlans.encoderKeys("mt6983", "MT6983", 33, "x", "y")["video-qp-max"])
    }

    @Test
    fun qualityFloorAddsMinimumsWithoutBreakingTheRange() {
        val sm8750 = VideoPlans.encoderKeys("qcom", "SM8750", 36, "samsung", "sun", 22)
        assertEquals(40, sm8750["video-qp-p-max"])
        assertEquals(22, sm8750["video-qp-i-min"])
        assertEquals(22, sm8750["video-qp-p-min"])
        val mtk = VideoPlans.encoderKeys("MTK", "MT6985", 33, "vivo", "x", 22)
        assertEquals(16, mtk["video-qp-i-min"])
        assertNull(mtk["video-qp-p-min"])
        assertNull(VideoPlans.encoderKeys("qcom", "SM8750", 30, "x", "y", 22)["video-qp-i-min"])
    }
}
