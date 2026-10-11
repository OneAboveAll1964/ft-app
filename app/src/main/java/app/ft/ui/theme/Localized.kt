package app.ft.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import app.ft.core.Lang

@Composable
fun Localized(content: @Composable () -> Unit) {
    val language by Lang.current.collectAsState()
    val base = LocalContext.current
    val config = LocalConfiguration.current
    val context = remember(language, base, config) { Lang.wrap(base, language, config) }
    val direction = if (Lang.rtl(language)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides context.resources.configuration,
        LocalLayoutDirection provides direction
    ) { content() }
}

@Composable
fun LeftToRight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content() }
}

fun TextStyle.ownDirection(): TextStyle = copy(textDirection = TextDirection.Content)
