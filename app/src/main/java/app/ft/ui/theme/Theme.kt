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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

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
        typography = rememberCenteredTypography(FTTypography),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

@Composable
fun CarTheme(accent: Color = Mint, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = app.ft.ui.car.carScheme(DarkScheme, accent),
        typography = rememberCenteredTypography(FTTypography),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

@Composable
private fun rememberCenteredTypography(base: Typography): Typography {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    return remember(base, resolver, density) { base.map { it.digitsCentered(resolver, density) } }
}

private fun Typography.map(fix: (TextStyle) -> TextStyle) = copy(
    displayLarge = fix(displayLarge), displayMedium = fix(displayMedium), displaySmall = fix(displaySmall),
    headlineLarge = fix(headlineLarge), headlineMedium = fix(headlineMedium), headlineSmall = fix(headlineSmall),
    titleLarge = fix(titleLarge), titleMedium = fix(titleMedium), titleSmall = fix(titleSmall),
    bodyLarge = fix(bodyLarge), bodyMedium = fix(bodyMedium), bodySmall = fix(bodySmall),
    labelLarge = fix(labelLarge), labelMedium = fix(labelMedium), labelSmall = fix(labelSmall),
    displayLargeEmphasized = fix(displayLargeEmphasized), displayMediumEmphasized = fix(displayMediumEmphasized),
    displaySmallEmphasized = fix(displaySmallEmphasized), headlineLargeEmphasized = fix(headlineLargeEmphasized),
    headlineMediumEmphasized = fix(headlineMediumEmphasized), headlineSmallEmphasized = fix(headlineSmallEmphasized),
    titleLargeEmphasized = fix(titleLargeEmphasized), titleMediumEmphasized = fix(titleMediumEmphasized),
    titleSmallEmphasized = fix(titleSmallEmphasized), bodyLargeEmphasized = fix(bodyLargeEmphasized),
    bodyMediumEmphasized = fix(bodyMediumEmphasized), bodySmallEmphasized = fix(bodySmallEmphasized),
    labelLargeEmphasized = fix(labelLargeEmphasized), labelMediumEmphasized = fix(labelMediumEmphasized),
    labelSmallEmphasized = fix(labelSmallEmphasized)
)

private fun TextStyle.digitsCentered(resolver: FontFamily.Resolver, density: Density): TextStyle {
    if (!fontSize.isSp || !lineHeight.isSp) return this
    val typeface = runCatching {
        resolver.resolve(fontFamily, fontWeight ?: FontWeight.Normal, fontStyle ?: FontStyle.Normal, fontSynthesis ?: FontSynthesis.All).value
    }.getOrNull() as? android.graphics.Typeface ?: return this
    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = with(density) { fontSize.toPx() }
    }
    val metrics = paint.fontMetricsInt
    val ink = android.graphics.RectF()
    android.graphics.Path().also { paint.getTextPath(DIGITS, 0, DIGITS.length, 0f, 0f, it) }.computeBounds(ink, true)
    if (ink.isEmpty) return this
    val line = ceil(with(density) { lineHeight.toPx() }).toInt()
    val spare = line - (metrics.descent - metrics.ascent)
    if (spare <= 0) return this
    val current = lineHeightStyle ?: LineHeightStyle.Default
    if (current.trim == LineHeightStyle.Trim.None) {
        val below = (line / 2f - metrics.descent + ink.centerY()).roundToInt().coerceIn(0, spare)
        return copy(lineHeightStyle = current.copy(alignment = LineHeightStyle.Alignment(below.belowRatio(spare))))
    }
    val low = (-metrics.ascent - metrics.descent) / 2f + ink.centerY()
    val grow = (2 * abs(low)).roundToInt().coerceAtMost(spare)
    if (grow == 0) return this
    return if (low > 0) {
        copy(lineHeightStyle = current.copy(alignment = LineHeightStyle.Alignment(grow.belowRatio(spare)), trim = LineHeightStyle.Trim.FirstLineTop))
    } else {
        copy(lineHeightStyle = current.copy(alignment = LineHeightStyle.Alignment((spare - grow).belowRatio(spare)), trim = LineHeightStyle.Trim.LastLineBottom))
    }
}

private fun Int.belowRatio(spare: Int) = (1f - (this - 0.5f) / spare).coerceIn(0f, 1f)

private const val DIGITS = "0123456789"
