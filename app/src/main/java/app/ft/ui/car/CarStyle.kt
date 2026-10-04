package app.ft.ui.car

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import app.ft.FTApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class CarStyle(
    val background: String = DEFAULT_BACKGROUND,
    val accent: Int = DEFAULT_ACCENT,
    val tiles: List<String> = parseTiles(DEFAULT_TILES),
    val clock24: Boolean = true,
    val showLink: Boolean = false,
    val corner: Int = 0,
    val photoStamp: Long = 0
) {
    val accentColor: Color get() = Color(accent)

    companion object {
        const val PHOTO = "photo"
        const val DEFAULT_BACKGROUND = "deep"
        val DEFAULT_ACCENT = 0xFF5FE3C0.toInt()
        const val DEFAULT_TILES = "music,videos,youtube,maps,browser,apps"
        const val MAX_TILES = 6

        val BACKGROUNDS: List<Pair<String, List<Color>>> = listOf(
            "deep" to listOf(Color(0xFF0B1D2A), Color(0xFF0F2634)),
            "midnight" to listOf(Color(0xFF0A0F1F), Color(0xFF1C2541)),
            "ocean" to listOf(Color(0xFF022A3A), Color(0xFF075E7A)),
            "forest" to listOf(Color(0xFF0B2016), Color(0xFF1F4B2E)),
            "sunset" to listOf(Color(0xFF26102E), Color(0xFF7A3414)),
            "rose" to listOf(Color(0xFF2A0F1C), Color(0xFF6B1F3A)),
            "graphite" to listOf(Color(0xFF111111), Color(0xFF2B2B2B)),
            "black" to listOf(Color(0xFF000000), Color(0xFF000000))
        )

        val ACCENTS: List<Int> = listOf(
            0xFF5FE3C0, 0xFF6EA8FE, 0xFFB69CFF, 0xFFFF8AC0, 0xFFFFB066,
            0xFFFFD54F, 0xFF8FE388, 0xFFFF6B6B, 0xFFE6E6E6
        ).map { it.toInt() }

        val BUILT_IN_TILES = listOf("music", "videos", "youtube", "browser", "maps", "apps", "aa", "phone")

        fun parseTiles(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && (it in BUILT_IN_TILES || it.startsWith("app:")) }
                .take(MAX_TILES)

        fun columns(count: Int): Int = if (count <= 3) count.coerceAtLeast(1) else (count + 1) / 2

        fun gradient(name: String): List<Color> =
            BACKGROUNDS.firstOrNull { it.first == name }?.second ?: BACKGROUNDS.first().second
    }
}

object CarStyles {
    private val _current = MutableStateFlow(CarStyle())
    val current: StateFlow<CarStyle> = _current

    fun photoFile(context: Context) = File(context.filesDir, "car_background.jpg")

    fun reload() {
        val p = FTApp.instance.prefs
        val photo = photoFile(FTApp.instance)
        _current.value = CarStyle(
            background = p.carBackground,
            accent = p.carAccent,
            tiles = CarStyle.parseTiles(p.carTiles).ifEmpty { CarStyle.parseTiles(CarStyle.DEFAULT_TILES) },
            clock24 = p.carClock24,
            showLink = p.carShowLink,
            corner = p.aaCorner.coerceIn(0, 3),
            photoStamp = if (photo.exists()) photo.lastModified() else 0
        )
    }

    fun savePhoto(context: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        photoFile(context).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        true
    }.getOrDefault(false)
}

fun carScheme(base: ColorScheme, accent: Color): ColorScheme = base.copy(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.45f) Color(0xFF0B1D2A) else Color.White,
    primaryContainer = lerp(accent, Color.Black, 0.62f),
    onPrimaryContainer = lerp(accent, Color.White, 0.7f),
    secondary = lerp(accent, Color.White, 0.25f),
    onSecondary = Color(0xFF0B1D2A),
    secondaryContainer = lerp(accent, Color.Black, 0.72f),
    onSecondaryContainer = lerp(accent, Color.White, 0.75f),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun CarBackground(style: CarStyle, modifier: Modifier = Modifier) {
    if (style.background == CarStyle.PHOTO && style.photoStamp > 0) {
        val photo by produceState<ImageBitmap?>(null, style.photoStamp) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(CarStyles.photoFile(FTApp.instance).path)?.asImageBitmap()
                }.getOrNull()
            }
        }
        Box(modifier.fillMaxSize().background(Color.Black)) {
            photo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.65f)))))
        }
        return
    }
    Box(modifier.fillMaxSize().background(Brush.linearGradient(CarStyle.gradient(style.background))))
}
