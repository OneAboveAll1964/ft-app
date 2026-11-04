package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import app.ft.projection.VideoPlan
import app.ft.projection.VideoProfile
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class CarProfilesTest {
    @Test fun androidAutoPreparationIsOptInAndBoundToVehicle() {
        val i = Installation()
        val first = i.selected
        assertFalse(first.aaPrepareBeforeConnect)
        first.aaPrepareBeforeConnect = true
        i.create()
        assertFalse(i.selected.aaPrepareBeforeConnect)
        assertFalse(i.selected.aaAutoStart)
        i.selected.aaAutoStart = true
        assertTrue(first.aaPrepareBeforeConnect)
        assertFalse(first.aaAutoStart)
        Backup.restore(i.selected, Backup.write(mapOf("aaPrepareBeforeConnect" to true)))
        assertTrue(i.selected.aaPrepareBeforeConnect)
        assertEquals(true, i.selected.all["aaPrepareBeforeConnect"])
        first.aaPrepareBeforeConnect = false
        assertTrue(i.selected.aaPrepareBeforeConnect)
    }

    private class Store {
        val values = linkedMapOf<String, Any>()
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val shared = mock(SharedPreferences::class.java)

        init {
            `when`(shared.all).thenAnswer { values.toMap() }
            `when`(shared.contains(anyString())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
            `when`(shared.getInt(anyString(), anyInt())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            `when`(shared.getLong(anyString(), anyLong())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
            `when`(shared.getFloat(anyString(), anyFloat())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Float>(1) }
            `when`(shared.getString(anyString(), nullable(String::class.java))).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
            `when`(shared.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            doAnswer { listeners.add(it.getArgument(0)); null }.`when`(shared).registerOnSharedPreferenceChangeListener(any())
            doAnswer { listeners.remove(it.getArgument<SharedPreferences.OnSharedPreferenceChangeListener>(0)); null }.`when`(shared).unregisterOnSharedPreferenceChangeListener(any())
            `when`(shared.edit()).thenAnswer {
                val pending = linkedMapOf<String, Any?>()
                var clear = false
                val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
                doAnswer { pending[it.getArgument(0)] = it.getArgument<String>(1); editor }.`when`(editor).putString(anyString(), anyString())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Int>(1); editor }.`when`(editor).putInt(anyString(), anyInt())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Long>(1); editor }.`when`(editor).putLong(anyString(), anyLong())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Float>(1); editor }.`when`(editor).putFloat(anyString(), anyFloat())
                doAnswer { pending[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }.`when`(editor).putBoolean(anyString(), anyBoolean())
                doAnswer { pending[it.getArgument(0)] = null; editor }.`when`(editor).remove(anyString())
                doAnswer { clear = true; editor }.`when`(editor).clear()
                doAnswer {
                    if (clear) values.clear()
                    val changed = pending.filter { (key, value) -> values[key] != value }.keys
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(shared, key) } }
                    null
                }.`when`(editor).apply()
                editor
            }
        }
    }

    private class Installation {
        val context = mock(Context::class.java)
        val stores = linkedMapOf<String, Store>()
        var idle = true
        var selected: Prefs
        val profiles: CarProfiles

        init {
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer {
                stores.getOrPut(it.getArgument(0)) { Store() }.shared
            }
            selected = Prefs(context)
            profiles = CarProfiles(context, selected, { idle }, onSelected = { selected = it })
        }

        fun create(name: String = "Second car", protocol: CarProtocol = CarProtocol.ICCOA, linkMode: Int = 2) =
            profiles.create(name, CarModel.RAV4_XA50, protocol, linkMode)!!
    }

    @Test fun legacyMigrationKeepsPairingAndEveryExistingSettingInPlace() {
        val i = Installation()
        val legacy = i.selected
        legacy.carWpsPin = "12345670"
        legacy.carProtocol = CarProtocol.ICCOA
        legacy.autoConnect = true
        legacy.videoWidth = 1920
        assertTrue(legacy.confirmCarLifeVehicle("AA:BB", "Existing car", "CC:DD", 0))
        val profiles = CarProfiles(i.context, legacy, { true }, onSelected = {})
        assertEquals(listOf(CarProfiles.LEGACY_ID), profiles.state.value.profiles.map { it.id })
        assertEquals(CarProfiles.DEFAULT_NAME, profiles.state.value.profiles.single().name)
        assertEquals("AA:BB", profiles.prefsFor(CarProfiles.LEGACY_ID)!!.carLifeVehicleAddress)
        assertEquals(1920, Prefs(i.context).videoWidth)
        assertEquals("12345670", i.stores.getValue("ft").values["carWpsPin"])
        assertFalse(i.stores.keys.any { it.startsWith("ft-car-legacy") })
        profiles.close()
    }

    @Test fun newVehicleStartsWithDefaultsAndDoesNotInheritPairingOrVideoCache() {
        val i = Installation()
        i.selected.carWpsPin = "12345670"
        i.selected.carBtName = "First Bluetooth"
        i.selected.autoConnect = true
        i.selected.videoWidth = 1920
        i.selected.saveVideoProfile(
            VideoProfile(VideoPlan(1280, 720, 1280, 720, 30, 4_000_000, 22), i.selected.videoSettingsKey, "first", true, true),
            i.selected.videoProfileGeneration
        )
        assertNotNull(i.selected.savedVideoProfile)
        assertTrue(i.selected.confirmCarLifeVehicle("AA:BB", "First", "CC:DD", 0))
        val id = i.create()
        assertEquals(id, i.selected.profileId)
        assertEquals(CarProtocol.ICCOA, i.selected.carProtocol)
        assertEquals(2, i.selected.linkMode)
        assertEquals("", i.selected.carBtAddress)
        assertEquals("", i.selected.carBtName)
        assertEquals("", i.selected.carLifeVehicleAddress)
        assertEquals("", i.selected.carWpsPin)
        assertFalse(i.selected.autoConnect)
        assertEquals(0, i.selected.videoWidth)
        assertNull(i.selected.savedVideoProfile)
        assertFalse(i.selected.all.containsKey("savedVideoProfile"))
        assertEquals(id, Prefs(i.context).profileId)
    }

    @Test fun settingsAndLateCallbacksRemainBoundToTheirOriginalVehicle() {
        val i = Installation()
        val first = i.selected
        first.carBackground = "ocean"
        first.aaWidth = 1920
        first.guidanceMode = 2
        first.gearReverse = 7
        first.mapsPackage = "first.maps"
        val id = i.create()
        val second = i.selected
        second.carBackground = "forest"
        second.aaWidth = 1280
        assertTrue(first.confirmCarLifeVehicle("AA:BB", "Late first", "CC:DD", 0))
        first.carWpsPin = "late pin"
        assertEquals("", second.carLifeVehicleAddress)
        assertEquals("", second.carWpsPin)
        assertEquals("forest", Prefs(i.context).carBackground)
        assertTrue(i.profiles.select(CarProfiles.LEGACY_ID))
        assertEquals("ocean", i.selected.carBackground)
        assertEquals(1920, i.selected.aaWidth)
        assertEquals(2, i.selected.guidanceMode)
        assertEquals(7, i.selected.gearReverse)
        assertEquals("first.maps", i.selected.mapsPackage)
        assertEquals("AA:BB", i.selected.carLifeVehicleAddress)
        assertTrue(i.profiles.select(id))
        assertEquals("forest", i.selected.carBackground)
    }

    @Test fun lateVideoNegotiationCannotSaveItsCacheIntoTheNewVehicle() {
        val i = Installation()
        val first = i.selected
        val generation = first.videoProfileGeneration
        val negotiated = VideoProfile(
            VideoPlan(1280, 720, 1280, 720, 24, 4_000_000, 22), first.videoSettingsKey, "first", true, true
        )
        i.create()
        first.saveVideoProfile(negotiated, generation)
        assertEquals(negotiated, first.savedVideoProfile)
        assertNull(i.selected.savedVideoProfile)
        i.selected.videoFps = 30
        assertEquals(negotiated, first.savedVideoProfile)
        assertEquals(generation, first.videoProfileGeneration)
    }

    @Test fun globalsRemainSharedAndBothStoresNotifyAndUnregisterListeners() {
        val i = Installation()
        val legacy = i.selected
        legacy.language = "ru"
        legacy.supercastToken = "installation token"
        legacy.lastLatitude = 50f
        i.create()
        assertEquals("ru", i.selected.language)
        assertEquals("installation token", i.selected.supercastToken)
        assertEquals(50f, i.selected.lastLatitude, 0f)
        val events = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> events.add(key) }
        i.selected.registerListener(listener)
        legacy.themeMode = 2
        i.selected.carAccent = 123
        assertEquals(listOf("themeMode", "carAccent"), events)
        i.selected.unregisterListener(listener)
        legacy.themeMode = 1
        i.selected.carAccent = 456
        assertEquals(2, events.size)
        assertEquals(1, i.selected.themeMode)
        assertEquals(1, i.stores.getValue("ft").values["themeMode"])
    }

    @Test fun effectiveBackupCannotLeakLegacySettingsAndRestoreTargetsTheSelectedVehicle() {
        val i = Installation()
        val first = i.selected
        first.carBackground = "legacy only"
        first.carWpsPin = "legacy pin"
        first.language = "ru"
        i.create()
        i.selected.carAccent = 123
        val exported = Backup.parse(Backup.everything(i.selected))!!
        assertEquals("ru", exported["language"])
        assertEquals(123, exported["carAccent"])
        assertFalse(exported.containsKey("carBackground"))
        assertFalse(exported.containsKey("carWpsPin"))
        assertFalse(exported.containsKey("activeId"))
        assertFalse(exported.containsKey("ids"))
        assertEquals(2, Backup.restore(i.selected, Backup.write(mapOf("carBackground" to "restored", "language" to "en"))))
        assertEquals("restored", i.selected.carBackground)
        assertEquals("legacy only", first.carBackground)
        assertEquals("en", first.language)
    }

    @Test fun activeSessionsBlockCreateSelectAndDeleteButAllowMetadataEdits() {
        val i = Installation()
        val id = i.create()
        i.idle = false
        assertNull(i.profiles.create("Third", CarModel.CAMRY, CarProtocol.CARLIFE, 0))
        assertFalse(i.profiles.select(CarProfiles.LEGACY_ID))
        assertFalse(i.profiles.delete(CarProfiles.LEGACY_ID))
        assertTrue(i.profiles.update(id, "Renamed", CarModel.CAMRY))
        assertEquals(id, i.profiles.state.value.activeId)
        assertEquals("Renamed", i.profiles.state.value.profiles.last().name)
        assertEquals(CarModel.CAMRY, i.profiles.state.value.profiles.last().model)
        assertEquals(2, i.profiles.state.value.profiles.size)
    }

    @Test fun deletionRejectsActiveOrMissingVehicleAndPreservesGlobalsWhenDeletingLegacy() {
        val i = Installation()
        assertFalse(i.profiles.delete(CarProfiles.LEGACY_ID))
        i.selected.language = "ru"
        i.selected.carWpsPin = "old pin"
        val id = i.create()
        assertFalse(i.profiles.delete(id))
        assertFalse(i.profiles.delete("missing"))
        assertTrue(i.profiles.delete(CarProfiles.LEGACY_ID))
        assertNull(i.profiles.prefsFor(CarProfiles.LEGACY_ID))
        assertEquals("ru", i.selected.language)
        assertFalse(i.stores.getValue("ft").values.containsKey("carWpsPin"))
        assertEquals(listOf(id), i.profiles.state.value.profiles.map { it.id })
        val reopened = CarProfiles(i.context, Prefs(i.context), { true }, onSelected = {})
        assertEquals(listOf(id), reopened.state.value.profiles.map { it.id })
        reopened.close()
    }

    @Test fun protocolLinkAndAutoconnectSummariesTrackTheBoundPreferences() {
        val i = Installation()
        val legacy = i.selected
        i.create()
        legacy.carProtocol = CarProtocol.ICCOA
        legacy.linkMode = 1
        legacy.autoConnect = true
        val first = i.profiles.state.value.profiles.first()
        assertEquals(CarProtocol.ICCOA, first.protocol)
        assertEquals(1, first.linkMode)
        assertTrue(first.autoConnect)
        i.selected.autoConnect = true
        assertTrue(i.profiles.state.value.profiles.last().autoConnect)
    }

    @Test fun deletingANonLegacyProfileClearsItsSettingsAndCallsCleanupOnlyOnSuccess() {
        val i = Installation()
        val id = i.create("Family car")
        i.selected.carWpsPin = "paired pin"
        assertTrue(i.profiles.select(CarProfiles.LEGACY_ID))
        i.profiles.close()
        val deleted = mutableListOf<String>()
        val profiles = CarProfiles(i.context, i.selected, { true }, onSelected = {}, onDeleted = { deleted.add(it) })
        assertFalse(profiles.delete(CarProfiles.LEGACY_ID))
        assertTrue(profiles.delete(id))
        assertFalse(profiles.delete(id))
        assertEquals(listOf(id), deleted)
        assertTrue(i.stores.getValue(Prefs.profileStoreName(id)).values.isEmpty())
        assertNull(profiles.prefsFor(id))
        i.selected.carProtocol = CarProtocol.ICCOA
        i.selected.autoConnect = true
        assertEquals(CarProtocol.ICCOA, profiles.state.value.profiles.single().protocol)
        assertTrue(profiles.state.value.profiles.single().autoConnect)
        profiles.close()
    }

    @Test fun selectionInstallsApplicationPrefsBeforePublishingNewState() {
        val i = Installation()
        val id = i.create()
        val previousId = i.profiles.state.value.activeId
        var current: Prefs = i.selected
        lateinit var profiles: CarProfiles
        profiles = CarProfiles(i.context, current, { true }, onSelected = { next ->
            assertEquals(previousId, profiles.state.value.activeId)
            current = next
        })
        assertTrue(profiles.select(CarProfiles.LEGACY_ID))
        assertEquals(current.profileId, profiles.state.value.activeId)
        assertEquals(CarProfiles.LEGACY_ID, current.profileId)
        assertNotEquals(id, current.profileId)
        profiles.close()
    }
}
