package app.ft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun CornerPicker(selected: Int, carWidth: Int, carHeight: Int, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (carHeight > 0) (carWidth.toFloat() / carHeight).coerceIn(1.6f, 3f) else 16f / 9f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(shape)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHighest)
            .border(2.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Text(
            "Car screen",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
        listOf(0 to Alignment.TopStart, 1 to Alignment.TopEnd, 2 to Alignment.BottomStart, 3 to Alignment.BottomEnd).forEach { (value, align) ->
            val chosen = selected == value
            Box(
                Modifier
                    .align(align)
                    .padding(8.dp)
                    .size(width = 46.dp, height = 26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) scheme.primary else scheme.surfaceContainer)
                    .border(1.dp, if (chosen) scheme.primary else scheme.outline, RoundedCornerShape(50))
                    .clickable { onPick(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "FT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = if (chosen) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}
