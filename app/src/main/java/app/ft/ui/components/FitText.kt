package app.ft.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    smallest: TextUnit = 10.sp,
    color: Color = Color.Unspecified,
    align: TextAlign? = null
) {
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val largest = if (style.fontSize.isSpecified) style.fontSize else 14.sp
    BasicText(
        text,
        modifier,
        style = style.copy(color = tint, textAlign = align ?: style.textAlign),
        maxLines = maxLines,
        autoSize = TextAutoSize.StepBased(minFontSize = smallest, maxFontSize = largest, stepSize = 0.5.sp)
    )
}
