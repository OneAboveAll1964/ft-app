package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class CarLifeVehiclePrefsTest {
    private class Store {
        val values = mutableMapOf<String, Any>()
        val context = mock(Context::class.java)
        private val shared = mock(SharedPreferences::class.java)
        val prefs: Prefs
        init {
            `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(shared)
            `when`(shared.all).thenAnswer { values.toMap() }
            `when`(shared.getInt(anyString(), anyInt())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            `when`(shared.getString(anyString(), anyString())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String>(1) }
            `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            `when`(shared.edit()).thenAnswer {
                val pending = mutableMapOf<String, Any?>()
                val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
                doAnswer { pending[it.getArgument(0)] = it.getArgument<String>(1); editor }.`when`(editor).putString(anyString(), anyString())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Int>(1); editor }.`when`(editor).putInt(anyString(), anyInt())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }.`when`(editor).putBoolean(anyString(), anyBoolean())
                doAnswer { pending[it.getArgument(0)] = null; editor }.`when`(editor).remove(anyString())
                doAnswer {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    null
                }.`when`(editor).apply()
                editor
            }
            prefs = Prefs(context)
        }
    }

    @Test fun legacyBluetoothAddressRemainsAnUnconfirmedCandidate() {
        val store = Store()
        store.values["carBtAddress"] = "AA:BB:CC:DD:EE:FF"
        assertEquals("", store.prefs.carLifeVehicleAddress)
        assertFalse(store.prefs.showOtherDevicesInLogs)
    }

    @Test fun successfulCarCanRefreshWifiButAnotherCarCannotReplaceIt() {
        val store = Store()
        val p = store.prefs
        assertTrue(p.confirmCarLifeVehicle("AA:BB:CC:DD:EE:FF", "Toyota", "10:20:30:40:50:60", 0))
        assertEquals("Toyota", p.carLifeVehicleName)
        assertEquals("AA:BB:CC:DD:EE:FF", p.carBtAddress)
        assertTrue(p.confirmCarLifeVehicle("aa:bb:cc:dd:ee:ff", "Toyota", "10:20:30:40:50:61", 0))
        assertEquals("10:20:30:40:50:61", p.carLifeWifiAddress)
        assertFalse(p.confirmCarLifeVehicle("AA:BB:CC:DD:EE:00", "Other", "10:20:30:40:50:62", 0))
        assertEquals("Toyota", p.carLifeVehicleName)
        assertEquals("10:20:30:40:50:61", p.carLifeWifiAddress)
    }

    @Test fun changingCarInvalidatesCallbacksFromAnOlderPrefsInstance() {
        val store = Store()
        val oldServicePrefs = Prefs(store.context)
        assertTrue(oldServicePrefs.confirmCarLifeVehicle("AA:BB:CC:DD:EE:FF", "Toyota", "10:20:30:40:50:60", 0))
        store.values["carP2pName"] = "Toyota WiFi"
        store.prefs.forgetCarLifeVehicle()
        assertEquals("", store.prefs.carLifeVehicleAddress)
        assertEquals("", store.prefs.carBtAddress)
        assertEquals("", store.prefs.carP2pName)
        assertFalse(oldServicePrefs.confirmCarLifeVehicle("AA:BB:CC:DD:EE:FF", "Toyota", "10:20:30:40:50:60", 0))
        assertTrue(oldServicePrefs.confirmCarLifeVehicle("AA:BB:CC:DD:EE:00", "New car", "10:20:30:40:50:61", 1))
    }

    @Test fun missingTransportIdentityCannotCreateABinding() {
        val p = Store().prefs
        assertFalse(p.confirmCarLifeVehicle("", "Toyota", "10:20:30:40:50:60", 0))
        assertFalse(p.confirmCarLifeVehicle("AA:BB:CC:DD:EE:FF", "Toyota", " ", 0))
        assertEquals("", p.carLifeVehicleAddress)
    }

    @Test fun backupExcludesIdentityAndRestoreCannotReplaceLocalBinding() {
        val store = Store()
        val p = store.prefs
        assertTrue(p.confirmCarLifeVehicle("AA:BB:CC:DD:EE:FF", "Toyota", "10:20:30:40:50:60", 0))
        p.showOtherDevicesInLogs = true
        val exported = Backup.parse(Backup.everything(p))!!
        assertTrue(exported.keys.none { it.startsWith("carLife") || it == "carBtAddress" })
        assertEquals(true, exported["showOtherDevicesInLogs"])
        val replacement = Backup.write(mapOf(
            "carLifeVehicleAddress" to "AA:BB:CC:DD:EE:00",
            "carLifeVehicleName" to "Other",
            "carLifeWifiAddress" to "10:20:30:40:50:61",
            "carLifeVehicleRevision" to 999,
            "carBtAddress" to "AA:BB:CC:DD:EE:00",
            "showOtherDevicesInLogs" to false
        ))
        assertEquals(1, Backup.restore(p, replacement))
        assertEquals("Toyota", p.carLifeVehicleName)
        assertEquals("AA:BB:CC:DD:EE:FF", p.carLifeVehicleAddress)
        assertEquals(0, p.carLifeVehicleRevision)
        assertFalse(p.showOtherDevicesInLogs)
    }
}
