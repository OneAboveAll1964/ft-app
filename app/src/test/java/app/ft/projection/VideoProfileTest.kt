package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class VideoProfileTest {
    private val plan = VideoPlan(1920, 720, 1280, 720, 24, 3_000_000, 22)
    private val profile = VideoProfile(plan, "settings", "car", true, true)

    @Test fun persistedProfileRoundTrips() {
        assertEquals(profile, VideoProfile.decode(profile.encode()))
    }

    @Test fun settingsOrCarChangesInvalidateReuse() {
        assertNull(profile.resume(plan, "changed", "car"))
        assertNull(profile.resume(plan, "settings", "other car"))
    }

    @Test fun corruptOrImpossibleProfilesAreIgnored() {
        assertNull(VideoProfile.decode(""))
        assertNull(VideoProfile.decode("{}"))
        assertNull(VideoProfile.decode(profile.copy(plan = plan.copy(fps = 0)).encode()))
        assertNull(VideoProfile.decode(profile.copy(plan = plan.copy(streamWidth = 0)).encode()))
    }

    @Test fun storedPlanUsesTheSameBitrateLimitsAsTheEncoder() {
        val low = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_CAR, 0, 0, 0, 0, 1)
        val high = VideoPlans.plan(1920, 720, 0, false, VideoPlans.SIZE_CAR, 0, 0, 0, 0, Int.MAX_VALUE)
        assertEquals(300_000, low.bitrate)
        assertEquals(40_000_000, high.bitrate)
    }
}
