package app.ft.carlife

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/** Small, locally drawn PNGs for CarLife versions which render the supplied turn icon. */
object NavigationIcons {
    private val cache = HashMap<Pair<Int, Int>, ByteArray>()

    @Synchronized fun png(maneuver: Int, exit: Int): ByteArray = cache.getOrPut(maneuver to exit) {
        val turn = NavigationRelay.turnCode(maneuver)
        val bitmap = createBitmap(128, 128)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = 12f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val path = Path()
        fun arrow(x: Float, y: Float, dx: Float, dy: Float) {
            path.moveTo(x - dx * 20 - dy * 18, y - dy * 20 + dx * 18)
            path.lineTo(x, y)
            path.lineTo(x - dx * 20 + dy * 18, y - dy * 20 - dx * 18)
        }
        when (turn) {
            2, 3, 4, 6, 7, 8 -> {
                val left = turn in listOf(6, 7, 8)
                val sign = if (left) -1f else 1f
                path.moveTo(64f, 110f); path.lineTo(64f, 68f)
                val x = 64f + sign * 40
                val y = when (turn) { 2, 8 -> 28f; 4, 6 -> 94f; else -> 68f }
                path.lineTo(x, y)
                val dy = (y - 68f) / 40f
                val length = kotlin.math.sqrt(1f + dy * dy)
                arrow(x, y, sign / length, dy / length)
            }
            5 -> {
                if (maneuver == 12 || maneuver == 20) canvas.scale(-1f, 1f, 64f, 64f)
                path.moveTo(94f, 110f); path.lineTo(94f, 50f)
                path.cubicTo(94f, 10f, 34f, 10f, 34f, 50f)
                path.lineTo(34f, 80f); arrow(34f, 80f, 0f, 1f)
            }
            9, 10 -> {
                canvas.drawCircle(64f, 62f, 28f, paint)
                path.moveTo(64f, 110f); path.lineTo(64f, 90f)
                // Exit number does not specify an angle. Avoid inventing an exit direction.
                if (exit > 0) {
                    val text = Paint(paint).apply {
                        style = Paint.Style.FILL; textSize = 28f; textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(exit.toString(), 64f, 62f - (text.ascent() + text.descent()) / 2f, text)
                }
            }
            24 -> {
                path.moveTo(38f, 110f); path.lineTo(38f, 20f)
                path.lineTo(100f, 20f); path.lineTo(86f, 42f)
                path.lineTo(100f, 64f); path.lineTo(38f, 64f)
            }
            else -> {
                path.moveTo(64f, 110f); path.lineTo(64f, 20f)
                arrow(64f, 20f, 0f, -1f)
            }
        }
        canvas.drawPath(path, paint)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
