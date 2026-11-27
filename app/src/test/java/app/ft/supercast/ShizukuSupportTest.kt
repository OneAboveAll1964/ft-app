package app.ft.supercast

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ShizukuSupportTest {

    @Test
    fun supercastWayConstantsAreDistinct() {
        assertEquals(0, SuperCast.WAY_NONE)
        assertEquals(1, SuperCast.WAY_ROOT)
        assertEquals(2, SuperCast.WAY_BRIDGE)
        assertEquals(3, SuperCast.WAY_SHIZUKU)
    }

    @Test
    fun isInstalledDetectsPackageCorrectly() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)

        `when`(pm.getPackageInfo(eq("moe.shizuku.privileged.api"), anyInt()))
            .thenReturn(PackageInfo())

        assertTrue(ShizukuSupport.isInstalled(context))

        `when`(pm.getPackageInfo(eq("moe.shizuku.privileged.api"), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException())

        assertFalse(ShizukuSupport.isInstalled(context))
    }

    @Test
    fun isRunningReturnsFalseWhenShizukuNotActiveInTest() {
        // In unit test environment, Shizuku binder is not running
        assertFalse(ShizukuSupport.isRunning())
        assertFalse(ShizukuSupport.hasPermission())
    }
}
