package app.ft.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Mint = Color(0xFF5FE3C0)
private val Deep = Color(0xFF0B1D2A)
private val Sea = Color(0xFF123A55)
private val Ember = Color(0xFFFFB86B)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Deep,
    primaryContainer = Color(0xFF1E5C4C),
    onPrimaryContainer = Color(0xFFBFF5E5),
    secondary = Ember,
    onSecondary = Deep,
    secondaryContainer = Color(0xFF5A3A16),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFF9CC7FF),
    background = Deep,
    onBackground = Color(0xFFE4F1F6),
    surface = Color(0xFF0F2634),
    onSurface = Color(0xFFE4F1F6),
    surfaceVariant = Sea,
    onSurfaceVariant = Color(0xFFB6CAD6),
    outline = Color(0xFF4F6B7B)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F1E0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF8A4F00),
    secondaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFFF4FAFB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDDE9EE)
)

val FTTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp),
    headlineLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
)

@Composable
fun FTTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = FTTypography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

@Composable
fun CarTheme(accent: Color = Mint, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = app.ft.ui.car.carScheme(DarkScheme, accent),
        typography = FTTypography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
