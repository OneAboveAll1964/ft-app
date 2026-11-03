package app.ft.iccoa

import android.content.Context
import android.content.SharedPreferences
import app.ft.core.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class IccoaVehicleStoresTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"

    @Test fun existingCarRetainsAllLegacyPairingFiles() {
        listOf("iccoa-remembered-car", "iccoa-trusted-quick-v1", "iccoa-trusted-reconnect-v1", "ft-iccoa-touch").forEach {
            assertEquals(it, IccoaVehicleStores.name(it, Prefs.LEGACY_ID))
            assertNotEquals(IccoaVehicleStores.name(it, first), IccoaVehicleStores.name(it, second))
        }
    }

    @Test fun deletionClearsOnlyTheRequestedVehiclesPairingAndCalibrationStores() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        IccoaVehicleStores.clear(context, second)
        verify(context).getSharedPreferences("iccoa-remembered-car-$second", Context.MODE_PRIVATE)
        verify(context).getSharedPreferences("iccoa-trusted-quick-v1-$second", Context.MODE_PRIVATE)
        verify(context).getSharedPreferences("iccoa-trusted-reconnect-v1-$second", Context.MODE_PRIVATE)
        verify(context).getSharedPreferences("ft-iccoa-touch-$second", Context.MODE_PRIVATE)
        verifyNoMoreInteractions(context)
        verify(editor, times(4)).clear()
        verify(editor, times(4)).apply()
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidNamespaceCannotSelectArbitraryStore() {
        IccoaVehicleStores.name("iccoa-remembered-car", "../ft")
    }
}
