package app.ft.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ft.FTApp
import app.ft.R
import app.ft.aa.AaInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

@Composable
fun rememberAaServerOn(): Boolean? {
    val port = FTApp.instance.prefs.aaSelfPort
    var on by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(port) {
        val job = scope.launch {
            while (isActive) {
                on = withContext(Dispatchers.IO) { AaInstaller.serverRunning(port) }
                delay(2000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    return on
}

@Composable
fun AaStart(serverOn: Boolean?, ownCopy: Boolean, framed: Boolean = true) {
    val context = LocalContext.current
    var help by remember { mutableStateOf(false) }
    if (ownCopy) {
        OptionCard(stringResource(R.string.aa_own_copy), active = true, status = stringResource(R.string.state_ready), framed = framed) {
            Note(stringResource(R.string.aa_own_copy_body))
            FilledTonalButton(onClick = { AaInstaller.openStore(context) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(stringResource(R.string.aa_open_store), maxLines = 1)
            }
        }
        return
    }
    val on = serverOn == true
    OptionCard(stringResource(R.string.aa_server_option), active = on, status = stringResource(when (serverOn) { true -> R.string.state_on; false -> R.string.state_off; null -> R.string.state_checking }), framed = framed) {
        Note(stringResource(R.string.aa_server_option_body))
        if (!on) ButtonPair(stringResource(R.string.aa_show_me_how), { help = true }, stringResource(R.string.aa_open_aa), { AaInstaller.openSettings(context) })
    }
    if (help) ServerHelpSheet(serverOn) { help = false }
}

@Composable
fun AaMissing(onPickFile: () -> Unit, framed: Boolean = true) {
    val context = LocalContext.current
    OptionCard(stringResource(R.string.aa_missing_title), active = false, status = null, framed = framed) {
        Note(stringResource(R.string.aa_missing_body))
        ButtonPair(stringResource(R.string.aa_open_store), { AaInstaller.openStore(context) }, stringResource(R.string.aa_action_pick_file), onPickFile)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ButtonPair(first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val side = 16.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val widest = remember(first, second, style, density) {
            with(density) { maxOf(measurer.measure(first, style).size.width, measurer.measure(second, style).size.width).toDp() }
        }
        val pad = PaddingValues(horizontal = side, vertical = 10.dp)
        if (widest + side * 2 + 2.dp <= (maxWidth - 8.dp) / 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFirst, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(first, maxLines = 1) }
                OutlinedButton(onClick = onSecond, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(second, maxLines = 1) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFirst, contentPadding = pad, modifier = Modifier.fillMaxWidth()) { Text(first, maxLines = 1) }
                OutlinedButton(onClick = onSecond, contentPadding = pad, modifier = Modifier.fillMaxWidth()) { Text(second, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    active: Boolean,
    status: String?,
    framed: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val inside: @Composable () -> Unit = {
        Column(if (framed) Modifier.padding(horizontal = 16.dp, vertical = 14.dp) else Modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = if (framed) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (status != null) StatusChip(status, active)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
    if (!framed) {
        inside()
        return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) { inside() }
}

@Composable
private fun StatusChip(status: String, active: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) scheme.primary else scheme.surfaceContainerHighest,
        contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant
    ) {
        Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun ServerHelpSheet(serverOn: Boolean?, onDismiss: () -> Unit) {
    MaterialTheme(motionScheme = SheetMotion) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val pager = rememberPagerState { HELP_PAGES }
        val scope = rememberCoroutineScope()
        val icon = rememberAaIcon()
        val tall = LocalContext.current.resources.displayMetrics.heightPixels
        val shotMax = minOf(200.dp, with(LocalDensity.current) { (tall * 0.3f).toDp() })
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.aa_help_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (serverOn == true) StatusChip(stringResource(R.string.state_on), active = true)
                }
                Spacer(Modifier.height(18.dp))
                TallestPage(HELP_PAGES, { HelpPage(it, icon, shotMax) }) { height ->
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxWidth().height(height),
                        beyondViewportPageCount = HELP_PAGES - 1,
                        verticalAlignment = Alignment.Top,
                        overscrollEffect = null
                    ) { HelpPage(it, icon, shotMax) }
                }
                Spacer(Modifier.height(14.dp))
                val page = pager.currentPage
                val done = serverOn == true || page == HELP_PAGES - 1
                Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)) {
                    if (page > 0) {
                        TextButton(
                            onClick = { scope.launch { pager.animateScrollToPage((pager.targetPage - 1).coerceAtLeast(0)) } },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) { Text(stringResource(R.string.action_back)) }
                    }
                    Dots(HELP_PAGES, page, Modifier.align(Alignment.Center))
                    Button(
                        onClick = {
                            if (done) scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
                            else scope.launch { pager.animateScrollToPage((pager.targetPage + 1).coerceAtMost(HELP_PAGES - 1)) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) { Text(stringResource(if (done) R.string.state_done else R.string.action_next)) }
                }
            }
        }
    }
}

private const val HELP_PAGES = 4

private object SheetMotion : MotionScheme by MotionScheme.standard() {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(250, easing = LinearOutSlowInEasing)
}

@Composable
private fun HelpPage(page: Int, icon: ImageBitmap?, shotMax: Dp) {
    when (page) {
        0 -> HelpStep(1, stringResource(R.string.aa_help_1), stringResource(R.string.aa_help_1_body)) {
            val context = LocalContext.current
            Box(
                Modifier
                    .heightIn(max = shotMax)
                    .aspectRatio(720f / 520f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp)) {
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(onClick = { AaInstaller.openSettings(context) }) { Text(stringResource(R.string.aa_open_aa), maxLines = 1) }
                }
            }
        }
        1 -> HelpStep(2, stringResource(R.string.aa_help_2), stringResource(R.string.aa_help_2_body)) {
            Shot(R.drawable.aa_server_version, shotMax)
        }
        2 -> HelpStep(3, stringResource(R.string.aa_help_3)) {
            Shot(R.drawable.aa_server_menu, shotMax)
        }
        else -> HelpStep(4, stringResource(R.string.aa_help_4), stringResource(R.string.aa_help_4_body)) {
            Shot(R.drawable.aa_server_start, shotMax)
        }
    }
}

@Composable
private fun TallestPage(count: Int, page: @Composable (Int) -> Unit, content: @Composable (Dp) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var tallest = 0
        for (i in 0 until count) {
            subcompose(i) { page(i) }.forEach { tallest = maxOf(tallest, it.measure(loose).height) }
        }
        val shown = subcompose(count) { content(tallest.toDp()) }.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, shown.maxOfOrNull { it.height } ?: 0) { shown.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 18.dp else 6.dp, label = "dot")
            Box(
                Modifier
                    .size(width, 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) scheme.primary else scheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun rememberAaIcon(): ImageBitmap? {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 56.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, size) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(AaInstaller.GEARHEAD).toBitmap(size, size).asImageBitmap() }.getOrNull()
        }
    }
    return icon
}

@Composable
private fun HelpStep(number: Int, title: String, detail: String? = null, visual: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepMark(number, StepState.NOW, working = false)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(14.dp))
        visual()
    }
}

@Composable
private fun Shot(@DrawableRes id: Int, max: Dp) {
    val painter = painterResource(id)
    Image(
        painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .heightIn(max = max)
            .aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    )
}
