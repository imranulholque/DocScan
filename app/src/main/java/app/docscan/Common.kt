package app.docscan

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Dark screens (camera, crop) need light status-bar icons. */
@Composable
fun StatusBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    DisposableEffect(lightIcons) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = !lightIcons
        onDispose { if (ctl != null && before != null) ctl.isAppearanceLightStatusBars = before }
    }
}

/**
 * The phone's "animator duration scale" (Developer options, "Remove animations", Battery saver) makes every normal Compose
 * animation finish instantly. DocScanner's animations are part of how the app explains what is happening, so they must
 * always play: running an animation inside withContext(FullSpeed) gives it a scale of 1 whatever the phone says.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
object FullSpeed : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** Runs an animation (Animatable.animateTo, animate, animateScrollToPage ...) at normal speed, even with phone animations off. */
suspend fun <T> atFullSpeed(block: suspend () -> T): T = withContext(FullSpeed) { block() }

/** Like animateFloatAsState, but it ignores the phone's animation setting. */
@Composable
fun animateFloatAlways(target: Float, spec: AnimationSpec<Float> = tween(250), label: String = ""): State<Float> {
    val a = remember { Animatable(target) }
    LaunchedEffect(target) { withContext(FullSpeed) { a.animateTo(target, spec) } }
    return a.asState()
}

/** Like animateColorAsState, but it ignores the phone's animation setting. */
@Composable
fun animateColorAlways(target: Color, ms: Int = 400): Color {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != to) {
            from = lerp(from, to, p.value)
            to = target
            p.snapTo(0f)
            withContext(FullSpeed) { p.animateTo(1f, tween(ms)) }
        }
    }
    return lerp(from, to, p.value)
}

/** Fades the old content out, swaps, fades the new content in. Like Crossfade, but it ignores the phone's animation setting. */
@Composable
fun <T> FadeSwap(target: T, ms: Int = 170, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(target) }
    val a = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != shown) {
            withContext(FullSpeed) { a.animateTo(0f, tween(ms)) }
            shown = target
            withContext(FullSpeed) { a.animateTo(1f, tween(ms)) }
        }
    }
    Box(Modifier.graphicsLayer { alpha = a.value }) { content(shown) }
}

/** Shows [content] with a soft fade + pop while [visible]; keeps it on screen until the fade-out is done. Ignores the phone's animation setting. */
@Composable
fun PopVisible(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a by animateFloatAlways(if (visible) 1f else 0f, tween(if (visible) 220 else 180), "pop")
    if (visible || a > 0.01f) {
        Box(modifier.graphicsLayer { alpha = a; val k = 0.88f + 0.12f * a; scaleX = k; scaleY = k }) { content() }
    }
}

/** Opens / closes [content] smoothly (height + fade). Ignores the phone's animation setting. */
@Composable
fun ExpandVisible(visible: Boolean, content: @Composable () -> Unit) {
    val f by animateFloatAlways(if (visible) 1f else 0f, tween(if (visible) 260 else 200, easing = FastOutSlowInEasing), "expand")
    if (visible || f > 0.01f) {
        Box(
            Modifier.clipToBounds()
                .layout { m, c ->
                    val pl = m.measure(c)
                    layout(pl.width, (pl.height * f).roundToInt()) { pl.placeRelative(0, 0) }
                }
                .graphicsLayer { alpha = f },
        ) { content() }
    }
}

/** Full-screen "working" overlay that blocks touches. It fades and pops in / out smoothly. */
@Composable
fun BusyOverlay(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    val a by animateFloatAlways(if (text != null) 1f else 0f, tween(if (text != null) 180 else 220), "busyOverlay")
    if (text != null || a > 0.01f) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Color(0x99000000)).pointerInput(Unit) { },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
                modifier = Modifier.graphicsLayer { val k = 0.92f + 0.08f * a; scaleX = k; scaleY = k },
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScanningDoc(Modifier.size(30.dp, 40.dp), paper = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(last, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * Seconds since the screen appeared, advanced on every screen frame.
 * Unlike rememberInfiniteTransition / animateFloat, this does NOT obey the phone's "animator duration scale"
 * (Developer options, "Remove animations", Battery saver), so the scanning animations always move.
 * Read it only inside draw blocks (clock.value) so a frame redraws the picture and never recomposes the screen.
 */
@Composable
fun rememberClock(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    return t
}

/** A small page with a light line that sweeps over it: "the app is working on this page". */
@Composable
fun ScanningDoc(
    modifier: Modifier = Modifier.size(26.dp, 34.dp),
    color: Color = Color(0xFF14B8A6),
    paper: Color = Color.White,
) {
    val clock = rememberClock()
    Canvas(modifier.clip(RoundedCornerShape(6.dp))) {
        val ph = (clock.value / 1f) % 2f                       // 1 s down, 1 s back up
        val t = if (ph < 1f) ph else 2f - ph
        val w = size.width; val h = size.height
        drawRoundRect(paper, Offset.Zero, Size(w, h), CornerRadius(h * 0.1f, h * 0.1f))
        for (i in 0..3) {
            val y = h * (0.2f + i * 0.17f)
            drawLine(Color(0x66808898), Offset(w * 0.18f, y), Offset(w * (if (i == 3) 0.5f else 0.82f), y), strokeWidth = h * 0.05f, cap = StrokeCap.Round)
        }
        val y = h * (0.06f + 0.88f * t)
        val tail = h * 0.3f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.40f)), startY = y - tail, endY = y),
            Offset(0f, y - tail), Size(w, tail),
        )
        drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
    }
}

/** Selectable "pill" used for formats and sizes. */
@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, cs.primary) else BorderStroke(1.dp, Color.Transparent),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

suspend fun <T> ListenableFuture<T>.awaitCompat(ctx: Context): T = suspendCoroutine { cont ->
    addListener({
        try { cont.resume(get()) } catch (e: Exception) { cont.resumeWithException(e) }
    }, ContextCompat.getMainExecutor(ctx))
}

@Composable
fun gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun FormatPicker(vm: AppVM, showSize: Boolean = true) {
    val s = LocalStr.current
    SectionLabel(s.format)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice("PDF", vm.fmt == "pdf", Modifier.weight(1f)) { vm.chooseFmt("pdf") }
        Choice("JPG", vm.fmt == "jpg", Modifier.weight(1f)) { vm.chooseFmt("jpg") }
        Choice("PNG", vm.fmt == "png", Modifier.weight(1f)) { vm.chooseFmt("png") }
    }
    if (showSize && vm.fmt == "pdf") {
        SectionLabel(s.pdfSize)
        PdfSizeRow(vm)
    }
}

/** Picture size / A4 / A5 / Letter / Legal (scrolls sideways on a narrow phone). */
@Composable
fun PdfSizeRow(vm: AppVM) {
    val s = LocalStr.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sz in Paper.sizes) Choice(Paper.label(sz, s), vm.pdfSize == sz) { vm.choosePdfSize(sz) }
    }
}
