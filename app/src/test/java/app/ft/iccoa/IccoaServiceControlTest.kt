package app.ft.iccoa

import android.app.KeyguardManager
import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class IccoaServiceControlTest {
    private val service = mock(IccoaService::class.java, CALLS_REAL_METHODS)

    @Test fun voiceButtonReachesProjectionOnlyOnControlCategory() {
        val projection = mock(IccoaProjection::class.java)
        IccoaService::class.java.getDeclaredField("projection").apply { isAccessible = true }
            .set(service, projection)
        val control = IccoaService::class.java.getDeclaredMethod("control",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }
        control.invoke(service, 4, 16, byteArrayOf())
        verifyNoInteractions(projection)
        control.invoke(service, 1, 16, byteArrayOf())
        verify(projection).key(84)
    }

    @Test fun voiceStateIncludesRealPhoneFlagsAndOmitsExperimentalCallState() {
        val keyguard = mock(KeyguardManager::class.java)
        val battery = mock(BatteryManager::class.java)
        val power = mock(PowerManager::class.java)
        doReturn(keyguard).`when`(service).getSystemService(KeyguardManager::class.java)
        doReturn(battery).`when`(service).getSystemService(BatteryManager::class.java)
        doReturn(power).`when`(service).getSystemService(PowerManager::class.java)
        `when`(keyguard.isKeyguardLocked).thenReturn(true)
        `when`(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).thenReturn(12)
        `when`(power.currentThermalStatus).thenReturn(PowerManager.THERMAL_STATUS_SEVERE)
        val snapshot = IccoaService::class.java.getDeclaredMethod("phoneState",
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun fields(voice: Boolean) = IccoaControl.Fields(snapshot.invoke(service, voice) as ByteArray)
        val active = fields(true)
        assertEquals(-1L, active.number(1, -1))
        for (field in listOf(2, 4, 5, 6)) assertEquals(1L, active.number(field))
        `when`(battery.isCharging).thenReturn(true)
        `when`(keyguard.isKeyguardLocked).thenReturn(false)
        `when`(power.currentThermalStatus).thenReturn(PowerManager.THERMAL_STATUS_NONE)
        val stopped = fields(false)
        for (field in listOf(2, 4, 5, 6)) assertEquals(0L, stopped.number(field, -1))
        `when`(battery.isCharging).thenReturn(false)
        `when`(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).thenReturn(-1)
        assertEquals(0L, fields(false).number(5, -1))
    }
}
