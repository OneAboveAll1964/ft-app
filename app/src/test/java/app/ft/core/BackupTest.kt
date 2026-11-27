package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val sample = mapOf(
        "carBackground" to "ocean",
        "carAccent" to -10492992,
        "carClock24" to true,
        "carTiles" to "music,maps",
        "weatherCelsius" to 21.5f,
        "weatherAt" to 1760000000000L
    )

    @Test
    fun everySupportedTypeSurvivesARoundTrip() {
        val parsed = Backup.parse(Backup.write(sample))!!
        assertEquals("ocean", parsed["carBackground"])
        assertEquals(-10492992, parsed["carAccent"])
        assertEquals(true, parsed["carClock24"])
        assertEquals("music,maps", parsed["carTiles"])
        assertEquals(21.5, (parsed["weatherCelsius"] as Number).toDouble(), 0.001)
        assertEquals(1760000000000L, (parsed["weatherAt"] as Number).toLong())
    }

    @Test
    fun aBackupCarriesItsVersion() {
        assertTrue(Backup.write(sample).contains("\"ft\":${Backup.VERSION}"))
    }

    @Test
    fun rubbishIsRefused() {
        assertNull(Backup.parse("not json"))
        assertNull(Backup.parse("{}"))
        assertNull(Backup.parse("""{"ft":999,"values":{}}"""))
        assertNull(Backup.parse("""{"values":{"a":1}}"""))
    }

    @Test
    fun aLookCodeRoundTripsThroughBase64() {
        val json = Backup.write(sample.filterKeys { it in Backup.LOOK_KEYS })
        val code = Backup.encode(json)
        assertTrue(code.none { it == '+' || it == '/' || it == '=' })
        assertEquals(json, Backup.decode(code))
    }

    @Test
    fun aBrokenCodeDecodesToNothing() {
        assertNull(Backup.decode("!!!not base64!!!"))
    }

    @Test
    fun restoreUsesTheSamePortableSchemaOnAnEmptyInstall() {
        val legacyBackup = Backup.parse("""
            {"ft":1,"values":{"aaPort":5288,"carClock24":"false","carAccent":1.25,
            "aaWidth":4294968576,"aaHeight":"720","supercastSplit":0.25,
            "supercastToken":"local-token","carWpsPin":"12345670","savedVideoProfile":"cached",
            "futureOption":true}}
        """.trimIndent())!!
        val portable = Backup.portableValues(legacyBackup)
        assertEquals(mapOf("aaPort" to 5288, "carClock24" to false, "supercastSplit" to 0.25f), portable)
        assertEquals(mapOf("carClock24" to false), Backup.portableValues(portable, Backup.LOOK_KEYS))
    }

    @Test
    fun theLookCodeStaysSmallEnoughForAQrCode() {
        val everyLookKey = Backup.LOOK_KEYS.associateWith { "midnight-and-a-fairly-long-value" }
        assertTrue(Backup.encode(Backup.write(everyLookKey)).length < 2000)
    }
}
