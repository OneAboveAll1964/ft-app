package app.ft.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DuplicateAppHelperTest {

    @Test
    fun knownVariantsContainsStandardPackages() {
        assertTrue(DuplicateAppHelper.KNOWN_VARIANTS.contains("com.shkomaghdid.ft"))
        assertTrue(DuplicateAppHelper.KNOWN_VARIANTS.contains("app.ft"))
    }

    @Test
    fun detectsConflictingPlayStoreVersionWhenRunningAsCustom() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageName).thenReturn("app.ft")
        `when`(context.packageManager).thenReturn(pm)

        val info = PackageInfo().apply { packageName = "com.shkomaghdid.ft" }
        `when`(pm.getPackageInfo("com.shkomaghdid.ft", 0)).thenReturn(info)

        val conflict = DuplicateAppHelper.findConflictingPackage(context)
        assertEquals("com.shkomaghdid.ft", conflict)
    }

    @Test
    fun returnsNullWhenNoConflictInstalled() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageName).thenReturn("app.ft")
        `when`(context.packageManager).thenReturn(pm)

        `when`(pm.getPackageInfo("com.shkomaghdid.ft", 0))
            .thenThrow(PackageManager.NameNotFoundException())

        val conflict = DuplicateAppHelper.findConflictingPackage(context)
        assertNull(conflict)
    }

    @Test
    fun detectsCustomVersionWhenRunningAsPlayStore() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageName).thenReturn("com.shkomaghdid.ft")
        `when`(context.packageManager).thenReturn(pm)

        val info = PackageInfo().apply { packageName = "app.ft" }
        `when`(pm.getPackageInfo("app.ft", 0)).thenReturn(info)

        val conflict = DuplicateAppHelper.findConflictingPackage(context)
        assertEquals("app.ft", conflict)
    }
}
