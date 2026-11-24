package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class BackupAllCarsTest {

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> anyMap(): Map<K, V> {
        any<Map<K, V>>()
        return emptyMap()
    }

    @Test
    fun isAllCarsDetectsNewFormatAndRejectsLegacy() {
        val legacy = """{"ft":1,"values":{"carTiles":"music"}}"""
        val allCars = """{"ft":2,"type":"all_cars","global":{},"profiles":[]}"""

        assertFalse(Backup.isAllCars(legacy))
        assertTrue(Backup.isAllCars(allCars))
        assertFalse(Backup.isAllCars("not json"))
    }

    @Test
    fun allCarsFormatRestoresBothGlobalAndProfiles() {
        val json = """
            {
                "ft": 2,
                "type": "all_cars",
                "timestamp": 1732442400000,
                "activeId": "car-1",
                "global": {
                    "autoConnect": true,
                    "modernPhoneUi": true,
                    "autoBackupInterval": 1
                },
                "profiles": [
                    {
                        "id": "car-1",
                        "name": "RAV4 XA50",
                        "model": "RAV4_XA50",
                        "protocol": "CARLIFE",
                        "linkMode": 0,
                        "autoConnect": true,
                        "values": {
                            "carTiles": "music,maps",
                            "carAccent": 12345
                        }
                    },
                    {
                        "id": "car-2",
                        "name": "Corolla",
                        "model": "COROLLA",
                        "protocol": "ICCOA",
                        "linkMode": 1,
                        "autoConnect": false,
                        "values": {
                            "carTiles": "phone,aa",
                            "carAccent": 67890
                        }
                    }
                ]
            }
        """.trimIndent()

        val prefs = mock(Prefs::class.java)
        var restoredCount = 0
        `when`(prefs.restoreBackup(anyMap())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val map = invocation.arguments[0] as Map<String, Any>
            restoredCount += map.size
            Unit
        }

        val restored = Backup.restore(prefs, json)
        assertTrue("Expected restored settings count > 0", restored > 0)
    }

    @Test
    fun legacyFormatStillRestoresCorrectly() {
        val legacy = """
            {
                "ft": 1,
                "values": {
                    "carTiles": "music",
                    "carAccent": 999
                }
            }
        """.trimIndent()

        val prefs = mock(Prefs::class.java)
        val restored = Backup.restore(prefs, legacy)
        assertEquals(2, restored)
    }

    @Test
    fun autoBackupInfoParsesMetadataCorrectly() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "ft_test_backup_${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val file = File(tmpDir, "ft-backup-latest.json")
            val content = """
                {
                    "ft": 2,
                    "type": "all_cars",
                    "timestamp": 1732442400000,
                    "profiles": [
                        {"id": "1", "name": "Car 1", "values": {}},
                        {"id": "2", "name": "Car 2", "values": {}}
                    ]
                }
            """.trimIndent()
            file.writeText(content)

            val context = mock(Context::class.java)
            `when`(context.getExternalFilesDir(anyString())).thenReturn(tmpDir)

            val info = AutoBackup.info(context, file)
            assertNotNull(info)
            assertEquals(1732442400000L, info!!.timestamp)
            assertEquals(2, info.carCount)
            assertEquals(file.length(), info.sizeBytes)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
