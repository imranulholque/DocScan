package app.docscan

import android.app.Activity
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.clip
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

/** Full-screen "working" overlay that blocks touches. It fades and pops in / out smoothly. */
@Composable
fun BusyOverlay(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
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
