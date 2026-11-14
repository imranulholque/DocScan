package app.docscan

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
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

/** Full-screen "working" overlay that blocks touches. */
@Composable
fun BusyOverlay(text: String?) {
    if (text == null) return
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput(Unit) { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
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
        Choice("PDF", vm.fmt == "pdf", Modifier.weight(1f)) { vm.setFmt("pdf") }
        Choice("JPG", vm.fmt == "jpg", Modifier.weight(1f)) { vm.setFmt("jpg") }
        Choice("PNG", vm.fmt == "png", Modifier.weight(1f)) { vm.setFmt("png") }
    }
    if (showSize && vm.fmt == "pdf") {
        SectionLabel(s.pdfSize)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(s.sizeFit, vm.pdfSize == "fit", Modifier.weight(1f)) { vm.setPdfSize("fit") }
            Choice(s.sizeA4, vm.pdfSize == "a4", Modifier.weight(1f)) { vm.setPdfSize("a4") }
        }
    }
}
