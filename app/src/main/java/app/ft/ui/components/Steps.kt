package app.ft.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StepItem(
    val title: String,
    val done: Boolean,
    val hint: String? = null,
    val action: String? = null,
    val onAction: (() -> Unit)? = null
)

enum class StepState { DONE, NOW, LATER }

@Composable
fun Stepper(steps: List<StepItem>, running: Boolean) {
    val reached = steps.indexOfLast { it.done }
    val done = steps.mapIndexed { i, s -> s.done || i < reached }
    val now = done.indexOfFirst { !it }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        steps.forEachIndexed { i, step ->
            val state = when {
                done[i] -> StepState.DONE
                i == now -> StepState.NOW
                else -> StepState.LATER
            }
            StepRow(i + 1, step, state, last = i == steps.lastIndex, working = running && state == StepState.NOW && step.action == null)
        }
    }
}

@Composable
private fun StepRow(number: Int, step: StepItem, state: StepState, last: Boolean, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMark(number, state, working)
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (state == StepState.DONE) scheme.primary else scheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
            Box(Modifier.heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (state == StepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state == StepState.LATER) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state == StepState.NOW && step.hint != null) {
                Text(step.hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (state == StepState.NOW && step.action != null && step.onAction != null) {
                FilledTonalButton(onClick = step.onAction, modifier = Modifier.padding(top = 8.dp)) { Text(step.action) }
            }
        }
    }
}

@Composable
fun StepMark(number: Int, state: StepState, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when {
            state == StepState.DONE -> Box(
                Modifier.fillMaxSize().clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            working -> CircularProgressIndicator(Modifier.fillMaxSize().padding(2.dp), strokeWidth = 3.dp, color = scheme.primary)
            state == StepState.NOW -> Box(
                Modifier.fillMaxSize().border(2.dp, scheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                StepNumber(number, scheme.primary, bold = true)
            }
            else -> Box(
                Modifier.fillMaxSize().border(1.5.dp, scheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (number > 0) StepNumber(number, scheme.onSurfaceVariant, bold = false)
            }
        }
    }
}

@Composable
fun StepNumber(number: Int, color: Color, bold: Boolean) {
    val text = number.toString()
    val textSize = with(LocalDensity.current) { 14.sp.toPx() }
    val paint = remember(color, bold, textSize) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color.toArgb()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bold) 700 else 500, false)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val ink = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f - ink.exactCenterX(), size.height / 2f - ink.exactCenterY(), paint)
    }
}
