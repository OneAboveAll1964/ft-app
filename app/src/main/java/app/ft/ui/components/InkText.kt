package app.ft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

@Composable
fun InkText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val measurer = rememberTextMeasurer()
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val layout = remember(text, style, tint, direction, density) {
        measurer.measure(
            AnnotatedString(text),
            style.copy(color = tint),
            softWrap = false,
            maxLines = 1,
            layoutDirection = direction,
            density = density
        )
    }
    val ink = remember(layout) { layout.getPathForRange(0, text.length).getBounds() }
    val size = with(density) { androidx.compose.ui.unit.DpSize(layout.size.width.toDp(), layout.size.height.toDp()) }
    Canvas(modifier.size(size).semantics { contentDescription = text }) {
        val shift = if (ink.isEmpty) 0f else this.size.height / 2f - ink.center.y
        drawText(layout, topLeft = Offset(0f, shift))
    }
}
