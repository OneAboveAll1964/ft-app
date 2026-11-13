package app.ft.projection

import android.content.Context
import android.content.SharedPreferences
import app.ft.core.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*

class TouchCalibrationProfilesTest {
    private val profile = "00000000-0000-0000-0000-000000000001"
    private val context = mock(Context::class.java)
    private val stores = mutableMapOf<String, SharedPreferences>()

    init {
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer { invocation ->
            stores.getOrPut(invocation.getArgument(0)) {
                mock(SharedPreferences::class.java).also { preferences ->
                    `when`(preferences.getFloat(anyString(), anyFloat())).thenAnswer { it.getArgument<Float>(1) }
                }
            }
        }
    }

    private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After fun resetSessions() {
        CarTouchCalibration.cancel()
        MirrorTouchCalibration.cancel()
    }

    @Test fun legacyTouchScaleDoesNotApplyToAnotherVehicleWithTheSameResolution() {
        val legacy = preferences("ft-iccoa-touch")
        `when`(legacy.getFloat("1000x600_gain", 1f)).thenReturn(1.2f)
        `when`(legacy.getFloat("1000x600_offset", 0f)).thenReturn(-20f)

        assertEquals(220 to 100, CarTouchCalibration.correct(context, 200, 100, 1000, 600, Prefs.LEGACY_ID))
        assertEquals(200 to 100, CarTouchCalibration.correct(context, 200, 100, 1000, 600, profile))
        verify(context).getSharedPreferences("ft-iccoa-touch-$profile", Context.MODE_PRIVATE)
    }

    @Test fun measuredTouchQuadIsWrittenOnlyToTheCapturedVehicle() {
        val preferences = preferences("ft-iccoa-touch-$profile")
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(preferences.edit()).thenReturn(editor)

        CarTouchCalibration.begin(1000, 600)
        listOf(150 to 90, 850 to 90, 150 to 510, 850 to 510).forEach { (x, y) ->
            assertTrue(CarTouchCalibration.consume(context, CarTouch.DOWN, x, y, profile))
        }

        verify(editor).putString(eq("1000x600_quad4"), anyString())
        verify(editor).apply()
        assertFalse(stores.containsKey("ft-iccoa-touch"))
    }

    @Test fun mirrorCalibrationKeepsVehiclesSeparate() {
        val bounds = MirrorTouchBounds(0, 0, 1000, 600, 1000, 600)
        val legacy = preferences("ft-iccoa-touch")
        val quad = TouchQuad.fromSamples(listOf(
            TouchPoint(100.0, 150.0), TouchPoint(800.0, 150.0),
            TouchPoint(100.0, 850.0), TouchPoint(800.0, 850.0)
        ), 1000, 1000)!!
        `when`(legacy.getString("1000x600_mirror_landscape_quad4", null)).thenReturn(quad.serialize())

        assertEquals(150 to 500, MirrorTouchCalibration.map(context, bounds, 100, 300, 1000, 600, Prefs.LEGACY_ID))
        assertEquals(100 to 500, MirrorTouchCalibration.map(context, bounds, 100, 300, 1000, 600, profile))
    }
}
