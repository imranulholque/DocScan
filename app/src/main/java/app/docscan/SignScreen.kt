package app.docscan

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Make a new signature: 1) put a box around the writing, 2) the background is taken away (Image Fixer's remover) and the result is shown on a checkerboard. */
@Composable
fun SignScreen(vm: AppVM) {
    StatusBarIcons(lightIcons = true)
    val src = vm.sigSrc
    if (src == null) {
        LaunchedEffect(Unit) { vm.closeSign() }
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)))
        return
    }
    key(src.path) { SignBody(vm, src) }
}

@Composable
private fun SignBody(vm: AppVM, src: SigSrc) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }                 // 0 = crop, 1 = clean
    val box = remember { RectBox(0f, 0f, src.w.toFloat(), src.h.toFloat()) }
    var guessing by remember { mutableStateOf(true) }
    var remove by remember { mutableStateOf(true) }
    var strength by remember { mutableIntStateOf(50) }
    var uneven by remember { mutableStateOf(false) }
    var bold by remember { mutableIntStateOf(0) }
    var tint by remember { mutableStateOf("orig") }
    var prev by remember { mutableStateOf<ImageBitmap?>(null) }
    var making by remember { mutableStateOf(false) }
    var showOrig by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }

    val bmp by produceState<ImageBitmap?>(cachedBitmap(src.path, 1800), src.path) {
        value = withContext(Dispatchers.IO) { decodeBitmap(src.path, 1800) }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { atFullSpeed { enter.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } }

    // the box glides to where the writing is
    suspend fun fit() {
        guessing = true
        val g = try { Engine.sigGuess(src.path) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (g != null && g.size == 4 && !touched) {
            val from = box.list()
            atFullSpeed {
                animate(0f, 1f, animationSpec = tween(650, easing = FastOutSlowInEasing)) { t, _ ->
                    if (!touched) box.set(from[0] + (g[0] - from[0]) * t, from[1] + (g[1] - from[1]) * t, from[2] + (g[2] - from[2]) * t, from[3] + (g[3] - from[3]) * t)
                }
            }
        }
        guessing = false
    }
    LaunchedEffect(src.path) { fit() }

    // step 2: live preview (a small copy; the final one is made at full size when the signature is saved)
    val prevFile = remember { File(ctx.cacheDir, "sigprev_${System.nanoTime()}.png") }
    val boxKey = if (step == 1) box.list().joinToString(",") { it.roundToInt().toString() } else ""
    LaunchedEffect(step, remove, strength, uneven, tint, bold, boxKey) {
        if (step != 1) return@LaunchedEffect
        making = true
        delay(180)                                                  // a slider drag does not start a job for every pixel
        try {
            Engine.sigMake(src.path, prevFile.absolutePath, box.list(), strength, uneven, tint, remove, bold, 900, trim = false)
            prev = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(prevFile.absolutePath)?.asImageBitmap() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.toast(s.errPrefix + (e.message ?: ""))
        }
        making = false
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { prevFile.delete() } }

    fun back() { if (step == 1) step = 0 else vm.closeSign(1) }
    BackHandler { back() }

    fun finish() {
        scope.launch {
            vm.busy = s.sigMaking
            try {
                val tmp = File(ctx.cacheDir, "sigfinal_${System.nanoTime()}.png")
                val (w, h) = Engine.sigMake(src.path, tmp.absolutePath, box.list(), strength, uneven, tint, remove, bold, 1400)
                val sg = vm.addSignature(tmp, w, h)
                val pid = vm.pages.getOrNull(vm.current)?.id
                vm.closeSign(1)
                if (pid != null) vm.placeSig(pid, sg.id)
            } catch (e: Exception) {
                vm.toast(s.errPrefix + (e.message ?: ""))
            } finally { vm.busy = null }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0D12)).systemBarsPadding()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 36f },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text(if (step == 1) s.back else s.cancel, color = Color(0xFFFCA5A5)) }
            Spacer(Modifier.weight(1f))
            Text(s.sigCropTitle.takeIf { step == 0 } ?: s.sigStep2, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        StepPills(step, s.sigStep1, s.sigStep2)

        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            if (step == 0) {
                RectCropper(bmp, src.w, src.h, box, Modifier.fillMaxSize(), onTouch = { touched = true })
                PopVisible(visible = guessing, modifier = Modifier.align(Alignment.Center)) {
                    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xE6111827), shadowElevation = 8.dp) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScanningDoc(Modifier.size(20.dp, 28.dp), paper = Color(0xFF374151))
                            Spacer(Modifier.width(12.dp))
                            Text(s.sigFinding, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                CleanStage(bmp, src.w, box, prev, showOrig, making, onHold = { showOrig = it })
                Text(
                    s.sigCompare, color = Color(0xCCFFFFFF), fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }

        if (step == 0) {
            Text(
                s.sigCropHint, color = Color(0xB3FFFFFF), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { touched = false; scope.launch { fit() } }, enabled = !guessing) { Text(s.sigAutoFit, color = Color.White) }
                OutlinedButton(onClick = { touched = true; box.set(0f, 0f, src.w.toFloat(), src.h.toFloat()) }) { Text(s.whole, color = Color.White) }
                Button(onClick = { step = 1 }, enabled = !guessing, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text(s.sigNext, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Surface(color = cs.surface, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), shadowElevation = 10.dp) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.sigRemoveBg, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Switch(checked = remove, onCheckedChange = { remove = it })
                        }
                        if (remove) {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.sigStrength, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("$strength%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                            }
                            FancySlider(strength.toFloat(), { strength = it.roundToInt() }, range = 0f..100f)
                            Text(s.sigStrengthHint, fontSize = 11.sp, color = cs.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.sigUneven, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(s.sigUnevenHint, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                Switch(checked = uneven, onCheckedChange = { uneven = it })
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.sigBold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("$bold%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                            }
                            FancySlider(bold.toFloat(), { bold = it.roundToInt() }, range = 0f..100f)
                            Text(s.sigInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Choice(s.sigInkOrig, tint == "orig", Modifier.weight(1f)) { tint = "orig" }
                                Choice(s.sigInkBlack, tint == "black", Modifier.weight(1f)) { tint = "black" }
                                Choice(s.sigInkBlue, tint == "blue", Modifier.weight(1f)) { tint = "blue" }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { step = 0 }) { Text(s.sigStep1) }
                        Button(onClick = { finish() }, enabled = !making, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text(s.sigUse, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPills(step: Int, a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for ((i, label) in listOf(a, b).withIndex()) {
            val on = i == step
            val bg = animateColorAlways(if (on) Color(0xFF4F46E5) else Color(0xFF1F2430), 220)
            Surface(shape = RoundedCornerShape(14.dp), color = bg) {
                Text(
                    "${i + 1}  $label", color = if (on) Color.White else Color(0x99FFFFFF), fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
            if (i == 0) Box(Modifier.padding(horizontal = 8.dp).width(22.dp).height(2.dp).background(Color(0xFF353B4D)))
        }
    }
}

/** The result on a checkerboard (= see-through). Press and hold: the original photo of the same box. */
@Composable
private fun CleanStage(src: ImageBitmap?, photoW: Int, box: RectBox, prev: ImageBitmap?, showOrig: Boolean, making: Boolean, onHold: (Boolean) -> Unit) {
    val clock = rememberClock()
    val busyA by animateFloatAlways(if (making) 1f else 0f, tween(220), "making")
    ZoomBox(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onPress = { onHold(true); tryAwaitRelease(); onHold(false) }) }) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
            val bw = (box.r - box.l).coerceAtLeast(1f); val bh = (box.b - box.t).coerceAtLeast(1f)
            val sc = min(size.width / bw, size.height / bh)
            val dw = bw * sc; val dh = bh * sc
            val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
            drawRect(Color(0xFF151923), Offset.Zero, size)
            drawChecker(Offset(ox, oy), Size(dw, dh), 12.dp.toPx())
            if (showOrig) {
                src?.let {
                    val k = it.width.toFloat() / photoW.coerceAtLeast(1)        // picture pixels -> pixels of the decoded photo
                    val l = (box.l * k).roundToInt().coerceIn(0, it.width - 1)
                    val t = (box.t * k).roundToInt().coerceIn(0, it.height - 1)
                    val r = (box.r * k).roundToInt().coerceIn(l + 1, it.width)
                    val bt = (box.b * k).roundToInt().coerceIn(t + 1, it.height)
                    drawImage(it, srcOffset = IntOffset(l, t), srcSize = IntSize(r - l, bt - t),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)))
                }
            } else {
                prev?.let {
                    drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)))
                }
            }
            if (busyA > 0.01f) {
                val sweep = (clock.value / 1.3f) % 1f
                val w = dw * 0.38f
                val cx = ox - w + (dw + 2f * w) * sweep
                clipRect(ox, oy, ox + dw, oy + dh) {
                    drawRect(Color.Black.copy(alpha = 0.10f * busyA), Offset(ox, oy), Size(dw, dh))
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.40f * busyA), Color.Transparent), startX = cx - w / 2f, endX = cx + w / 2f),
                        Offset(cx - w / 2f, oy), Size(w, dh),
                    )
                }
            }
        }
        if (prev == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF5EEAD4))
    }
}
