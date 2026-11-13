package app.ft.carlife

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Bound both decoder allocation and packet size; a missing cover must not hide the track. */
object SongArtwork {
    fun jpeg(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) return null
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            try {
                for (quality in intArrayOf(80, 60, 40)) {
                    val out = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) && out.size() <= 12_000) return out.toByteArray()
                }
                null
            } finally { bitmap.recycle() }
        } catch (_: Exception) { null }
    }
}
