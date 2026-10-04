package app.ft.carlife

import app.ft.core.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongInfoTest {
    @Test
    fun aSongWithoutCoverStillCarriesEveryField() {
        val r = ProtoReader(CarLifeSession.songPayload("2", "Unknown artist", "", null, 61_000, 40, "1234"))
        for (field in 1..9) assertTrue("field $field missing; the car's CarLife crashed on a song like this", r.has(field))
        assertEquals("2", r.string(2))
        assertEquals(0, r.bytes(5)?.size ?: -1)
    }

    @Test
    fun theCoverIsSentWhenThereIsOne() {
        val art = ByteArray(5530) { 7 }
        val r = ProtoReader(CarLifeSession.songPayload("1985", "Bo Burnham", "Inside", art, 220_000, 40, "99"))
        assertEquals(5530, r.bytes(5)?.size)
        assertEquals(220_000, r.int(6))
    }
}
