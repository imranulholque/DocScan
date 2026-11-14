package app.docscan

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun CropScreen(vm: AppVM) {
    StatusBarIcons(lightIcons = true)
    val id = vm.cropQueue.firstOrNull()
    val page = vm.pages.firstOrNull { it.id == id }
    if (page == null) {
        LaunchedEffect(vm.importing, id) { vm.nextCrop() }
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF5EEAD4))
        }
        return
    }
    key(page.id) { CropBody(vm, page) }
}

private fun fullFrame(w: Int, h: Int): List<Offset> {
    val mx = w * 0.01f; val my = h * 0.01f
    return listOf(Offset(mx, my), Offset(w - mx, my), Offset(w - mx, h - my), Offset(mx, h - my))
}

private class DragState {
    var idx = -1                      // 0..3 corner, 4..7 side (between corner k and k+1)
    var a = Offset.Zero
    var b = Offset.Zero
    var acc = Offset.Zero
}

@Composable
private fun CropBody(vm: AppVM, page: Page) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val bmp by produceState<ImageBitmap?>(cachedBitmap(page.work, 1800), page.work) {
        value = withContext(Dispatchers.IO) { decodeBitmap(page.work, 1800) }
    }
    val quad = remember { mutableStateListOf<Offset>() }
    var sure by remember { mutableStateOf<Boolean?>(null) }       // null = still searching
    var touched by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }               // auto crop is switched off: the person places the corners
    var active by remember { mutableIntStateOf(-1) }
    val drag = remember { DragState() }

    // animations: a scanning light while the page is being searched, corners that glide to the page, a pulse when found
    // One frame clock drives every looping effect below. It is read only inside the Canvas draw block, so the screen is never
    // recomposed per frame, and (unlike infinite transitions) it keeps running when the phone's animations are switched off.
    val clock = rememberClock()
    val found = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }          // the whole screen glides in (no hard jump from the camera)
    val picIn = remember { Animatable(0f) }          // the picture fades in as soon as it is decoded
    LaunchedEffect(Unit) { atFullSpeed { enter.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } }
    LaunchedEffect(bmp) { if (bmp != null) atFullSpeed { picIn.animateTo(1f, tween(260)) } }

    suspend fun runDetect() {
        sure = null
        val d = try { Engine.detect(page.work) } catch (e: Exception) { null }
        if (d != null && !touched) {
            // the four corners glide from where they are to the page that was found
            val from = quad.toList()
            if (from.size == 4) {
                atFullSpeed {
                    animate(0f, 1f, animationSpec = tween(700, easing = FastOutSlowInEasing)) { t, _ ->
                        if (!touched) for (i in 0 until 4) quad[i] = lerp(from[i], d.quad[i], t)
                    }
                }
            }
            if (!touched) { quad.clear(); quad.addAll(d.quad) }
        }
        sure = d?.sure ?: false
        if (d != null) { found.snapTo(1f); atFullSpeed { found.animateTo(0f, tween(900)) } }
    }

    LaunchedEffect(page.id) {
        val old = page.quad
        if (old != null) {
            quad.addAll(old); sure = true
        } else if (!vm.autoCrop) {
            quad.addAll(fullFrame(page.w, page.h))
            manual = true; sure = false
        } else {
            // coming from the camera: the corners start where the live outline saw the page, then glide to the exact page
            val hint = vm.takeLiveHint(page.id)
            if (hint != null) quad.addAll(hint.map { Offset(it.x * page.w, it.y * page.h) }) else quad.addAll(fullFrame(page.w, page.h))
            runDetect()
        }
    }

    // right after the shutter: tell the person the picture is safe, so the phone can be moved away at once
    val fromShutter = remember { vm.cropFromShutter }
    val born = remember { System.currentTimeMillis() }
    var holdCard by remember { mutableStateOf(fromShutter) }
    LaunchedEffect(sure, manual) {
        if (holdCard && (sure != null || manual)) {
            val wait = 1500L - (System.currentTimeMillis() - born)
            if (wait > 0) delay(wait)
            holdCard = false
        }
    }
    val searching = sure == null && !manual

    val leave = { vm.nextCrop(discard = true) }
    BackHandler { leave() }

    val total = vm.cropQueue.size
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0D12)).systemBarsPadding()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 36f },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { leave() }) { Text(s.discard, color = Color(0xFFFCA5A5)) }
            Spacer(Modifier.weight(1f))
            Text(s.cropTitle + if (total > 1) "  ($total)" else "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        // status banner: stays invisible while the page is being searched (the "Auto cropping" card sits on the picture), then fades in
        val bannerColor = animateColorAlways(
            when (sure) { null -> Color(0xFF1F2937); true -> Color(0xFF064E3B); false -> Color(0xFF78350F) }, 450,
        )
        val bannerAlpha by animateFloatAlways(if (sure == null) 0f else 1f, tween(320), "bannerAlpha")
        Surface(
            shape = RoundedCornerShape(14.dp), color = bannerColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 58.dp).graphicsLayer { alpha = bannerAlpha },
        ) {
            FadeSwap(sure, 200) { st ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (st) { null -> ""; true -> s.cropFound; false -> if (manual) s.cropManual else s.cropCheck },
                        color = Color.White, fontSize = 13.sp,
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val scale = min(wPx / page.w, hPx / page.h)
            val ox = (wPx - page.w * scale) / 2f
            val oy = (hPx - page.h * scale) / 2f
            val hit = with(density) { 44.dp.toPx() }
            val cr = with(density) { 11.dp.toPx() }
            val sq = with(density) { 8.dp.toPx() }
            val stroke = with(density) { 2.5.dp.toPx() }
            val gridStep = with(density) { 26.dp.toPx() }

            fun toScreen(p: Offset) = Offset(ox + p.x * scale, oy + p.y * scale)
            fun mid(i: Int) = (quad[i] + quad[(i + 1) % 4]) / 2f

            Canvas(
                Modifier.fillMaxSize().pointerInput(page.id, scale) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            drag.idx = -1
                            if (quad.size == 4) {
                                var best = -1; var bd = Float.MAX_VALUE
                                for (i in 0 until 4) {
                                    val d = (toScreen(quad[i]) - pos).getDistance()
                                    if (d < bd) { bd = d; best = i }
                                }
                                if (bd > hit) {
                                    best = -1; bd = Float.MAX_VALUE
                                    for (k in 0 until 4) {
                                        val d = (toScreen(mid(k)) - pos).getDistance()
                                        if (d < bd) { bd = d; best = 4 + k }
                                    }
                                    if (bd > hit) best = -1
                                }
                                drag.idx = best
                                if (best >= 4) {
                                    val k = best - 4
                                    drag.a = quad[k]; drag.b = quad[(k + 1) % 4]; drag.acc = Offset.Zero
                                }
                                if (best >= 0) touched = true
                            }
                            active = drag.idx
                        },
                        onDragEnd = { drag.idx = -1; active = -1 },
                        onDragCancel = { drag.idx = -1; active = -1 },
                        onDrag = { change, amount ->
                            val i = drag.idx
                            if (i >= 0) {
                                change.consume()
                                val d = Offset(amount.x / scale, amount.y / scale)
                                if (i < 4) {
                                    val o = quad[i]
                                    quad[i] = Offset((o.x + d.x).coerceIn(0f, page.w - 1f), (o.y + d.y).coerceIn(0f, page.h - 1f))
                                } else {
                                    // the whole side moves sideways (along its normal); its two corners follow
                                    drag.acc += d
                                    val k = i - 4
                                    val ux = drag.b.x - drag.a.x; val uy = drag.b.y - drag.a.y
                                    val len = sqrt(ux * ux + uy * uy).coerceAtLeast(1f)
                                    val nx = -uy / len; val ny = ux / len
                                    val t = drag.acc.x * nx + drag.acc.y * ny
                                    quad[k] = Offset((drag.a.x + nx * t).coerceIn(0f, page.w - 1f), (drag.a.y + ny * t).coerceIn(0f, page.h - 1f))
                                    quad[(k + 1) % 4] = Offset((drag.b.x + nx * t).coerceIn(0f, page.w - 1f), (drag.b.y + ny * t).coerceIn(0f, page.h - 1f))
                                }
                            }
                        },
                    )
                },
            ) {
                bmp?.let {
                    drawImage(
                        it, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((page.w * scale).roundToInt(), (page.h * scale).roundToInt()),
                        alpha = picIn.value,
                    )
                }
                val clk = clock.value
                val ants = (clk * 52f) % 36f
                val searching = sure == null
                val teal = Color(0xFF5EEAD4)
                if (searching && bmp != null) {
                    val iw = page.w * scale; val ih = page.h * scale
                    val ph = (clk / 2.8f) % 1f                                   // one full trip (down, then back up) every 2.8 s
                    val scan = 0.5f - 0.5f * cos(ph * 2f * PI.toFloat())         // 0 -> 1 -> 0, easing softly at both ends
                    val down = ph < 0.5f
                    clipRect(left = ox, top = oy, right = ox + iw, bottom = oy + ih) {
                        drawRect(Color(0x33000000), Offset(ox, oy), Size(iw, ih))           // light dimming so the beam stands out
                        val y = oy + ih * scan
                        val trail = ih * 0.30f
                        val y0 = if (down) y - trail else y
                        val y1 = if (down) y else y + trail
                        // soft light left behind the beam (on the side it just came from)
                        val glow = Color(0x445EEAD4)
                        val cols = if (down) listOf(Color.Transparent, glow) else listOf(glow, Color.Transparent)
                        drawRect(Brush.verticalGradient(cols, startY = y0, endY = y1), Offset(ox, y0), Size(iw, trail))
                        // the beam: wide halo, bright band, white core; every layer fades out toward the left and right edge
                        fun layer(h: Float, c: Color) = drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 0.10f to c, 0.90f to c, 1f to Color.Transparent, startX = ox, endX = ox + iw),
                            Offset(ox, y - h / 2f), Size(iw, h),
                        )
                        layer(stroke * 10f, teal.copy(alpha = 0.16f))
                        layer(stroke * 4.5f, teal.copy(alpha = 0.40f))
                        layer(stroke * 1.4f, Color.White)
                    }
                    // four corner brackets that gently breathe
                    val breathe = 0.65f + 0.35f * sin(clk * 3.7f)
                    val gap: Float = min(iw, ih) * 0.05f * breathe
                    val bx0: Float = ox + gap
                    val by0: Float = oy + gap
                    val bx1: Float = ox + iw - gap
                    val by1: Float = oy + ih - gap
                    val bl = min(iw, ih) * 0.11f
                    val bc = Color.White.copy(alpha = 0.92f)
                    val bs = stroke * 2.4f
                    val cs4 = listOf(
                        Offset(bx0, by0) to (1f to 1f), Offset(bx1, by0) to (-1f to 1f),
                        Offset(bx1, by1) to (-1f to -1f), Offset(bx0, by1) to (1f to -1f),
                    )
                    for ((p0, dd) in cs4) {
                        drawLine(bc, p0, Offset(p0.x + dd.first * bl, p0.y), strokeWidth = bs, cap = StrokeCap.Round)
                        drawLine(bc, p0, Offset(p0.x, p0.y + dd.second * bl), strokeWidth = bs, cap = StrokeCap.Round)
                    }
                }
                if (quad.size == 4) {
                    val pts = quad.map { toScreen(it) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    if (searching && !touched) {
                        // still searching: marching dashes around the whole picture
                        drawPath(path, teal.copy(alpha = 0.55f), style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), ants)))
                    } else {
                        drawPath(path, Color(0x334F46E5))
                        if (found.value > 0.01f) drawPath(path, teal.copy(alpha = 0.30f * found.value))
                        drawPath(path, teal, style = Stroke(width = stroke))
                        if (found.value > 0.01f) {          // the page was found: one glowing pulse around it
                            drawPath(path, teal.copy(alpha = 0.55f * found.value), style = Stroke(width = stroke * (1f + 7f * found.value)))
                        }
                        for (k in 0 until 4) {
                            val m = toScreen(mid(k))
                            val r = if (active == 4 + k) sq * 1.5f else sq
                            drawRect(Color.White, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r))
                            drawRect(Color(0xFF4F46E5), Offset(m.x - r, m.y - r), Size(2 * r, 2 * r), style = Stroke(width = stroke * 0.8f))
                        }
                        for (i in 0 until 4) {
                            val r = (if (active == i) cr * 1.5f else cr) * (1f + 0.35f * found.value)
                            drawCircle(Color.White, r, pts[i])
                            drawCircle(Color(0xFF4F46E5), r, pts[i], style = Stroke(width = stroke))
                        }
                    }
                }
            }
            // "Auto cropping..." (+ "photo taken") floats in the middle of the picture while the page is searched
            PopVisible(
                visible = if (fromShutter) holdCard else searching,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xE6111827), shadowElevation = 8.dp) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (fromShutter) {
                            Text("✓  " + s.camTaken, color = Color(0xFF5EEAD4), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(s.camMoveAway, color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        if (searching) {
                            Row(Modifier.padding(top = if (fromShutter) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                                ScanningDoc(Modifier.size(20.dp, 28.dp), paper = Color(0xFF374151))
                                Spacer(Modifier.width(12.dp))
                                Text(s.cropSearching, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { touched = false; manual = false; scope.launch { runDetect() } },
                enabled = sure != null,
            ) { Text(s.auto, color = Color.White) }
            OutlinedButton(onClick = {
                touched = true
                quad.clear(); quad.addAll(fullFrame(page.w, page.h))
            }) { Text(s.whole, color = Color.White) }
            Button(
                onClick = {
                    scope.launch {
                        vm.busy = s.working
                        try {
                            vm.applyCrop(page.id, quad.toList())
                            vm.nextCrop()
                        } catch (e: Exception) {
                            vm.toast(s.errPrefix + (e.message ?: ""))
                        } finally { vm.busy = null }
                    }
                },
                enabled = quad.size == 4 && sure != null,
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text(s.cropApply, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
