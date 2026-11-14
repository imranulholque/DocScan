package app.docscan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CameraScreen(vm: AppVM) {
    val s = LocalStr.current
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    StatusBarIcons(lightIcons = true)

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val leave = { vm.cancelRetake(); vm.screen = if (vm.pages.any { it.cropped != null }) Screen.Review else Screen.Home }
    BackHandler { leave() }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(cs.background).systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(s.camPermTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(s.camPermText, fontSize = 15.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text(s.grant) }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = { leave() }) { Text(s.back) }
        }
        return
    }
    CameraContent(vm, leave)
}

/** One small upright camera picture (raw RGB) for the live page finder. */
private class Frame(val rgb: ByteArray, val w: Int, val h: Int)

/** CameraX RGBA frame -> small upright RGB picture (nearest-neighbour: fast, and the finder only needs the shape). */
private fun frameToRgb(p: ImageProxy, side: Int): Frame? {
    val plane = p.planes.firstOrNull() ?: return null
    if (plane.pixelStride != 4) return null
    val buf = plane.buffer
    val sw = p.width; val sh = p.height; val rs = plane.rowStride
    val k = side.toFloat() / max(sw, sh)
    val dw = max(8, (sw * k).roundToInt()); val dh = max(8, (sh * k).roundToInt())
    val rot = ((p.imageInfo.rotationDegrees % 360) + 360) % 360
    val turned = rot == 90 || rot == 270
    val ow = if (turned) dh else dw
    val oh = if (turned) dw else dh
    val out = ByteArray(ow * oh * 3)
    var o = 0
    for (y in 0 until oh) {
        for (x in 0 until ow) {
            var sx = x; var sy = y
            when (rot) {
                90 -> { sx = y; sy = dh - 1 - x }
                180 -> { sx = dw - 1 - x; sy = dh - 1 - y }
                270 -> { sx = dw - 1 - y; sy = x }
            }
            val px = (sx * sw / dw).coerceIn(0, sw - 1)
            val py = (sy * sh / dh).coerceIn(0, sh - 1)
            val i = py * rs + px * 4
            out[o++] = buf.get(i); out[o++] = buf.get(i + 1); out[o++] = buf.get(i + 2)
        }
    }
    return Frame(out, ow, oh)
}

private fun polyArea(q: List<Offset>): Float {
    var a = 0f
    for (i in q.indices) { val p = q[i]; val r = q[(i + 1) % q.size]; a += p.x * r.y - r.x * p.y }
    return abs(a) / 2f
}

/**
 * What the live camera knows about the paper: its outline (smoothed), whether the shot is good, and what to tell the person
 * (the same rules as Image Fixer's phone scanner: too close / too far / off-centre / tilted / hold still).
 */
private class LiveGuide {
    var quad by mutableStateOf<List<Offset>?>(null)        // fractions 0..1 of the camera picture: TL, TR, BR, BL
    var aspect by mutableFloatStateOf(0.75f)               // width / height of that picture
    var good by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)
    var sub by mutableStateOf("")
    var kind by mutableIntStateOf(1)                       // 0 = move the phone, 1 = almost, 2 = good
    var last = 0L
    private var stable = 0
    private var miss = 0
    var prev: List<Offset>? = null
    val busy = AtomicBoolean(false)
    @Volatile var on = true

    private fun say(t: String, sb: String, k: Int) { title = t; sub = sb; kind = k }

    fun update(q: List<Offset>?, w: Int, h: Int, s: Str) {
        aspect = w.toFloat() / h
        if (q == null) {
            stable = 0; good = false
            if (++miss > 2) { prev = null; quad = null; say(s.liveLooking, s.liveLookingSub, 1) }
            return
        }
        miss = 0
        val p = prev
        var move = 1f
        var sm = q
        if (p != null) {
            move = (0 until 4).sumOf { hypot(q[it].x - p[it].x, q[it].y - p[it].y).toDouble() }.toFloat() / 4f
            sm = List(4) { Offset((q[it].x + p[it].x) / 2f, (q[it].y + p[it].y) / 2f) }
        }
        prev = sm; quad = sm
        stable = if (move < 0.014f) stable + 1 else 0
        judge(sm, w, h, s)
    }

    private fun judge(q: List<Offset>, w: Int, h: Int, s: Str) {
        good = false
        val area = polyArea(q)
        val cx = q.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = q.sumOf { it.y.toDouble() }.toFloat() / 4f
        val dx = cx - 0.5f; val dy = cy - 0.5f
        val clip = q.any { it.x < 0.015f || it.x > 0.985f || it.y < 0.015f || it.y > 0.985f }
        fun d(a: Offset, b: Offset) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val rt = max(d(q[0], q[1]), d(q[3], q[2])) / max(1f, min(d(q[0], q[1]), d(q[3], q[2])))
        val rl = max(d(q[0], q[3]), d(q[1], q[2])) / max(1f, min(d(q[0], q[3]), d(q[1], q[2])))
        when {
            clip -> say(s.liveBack, s.liveBackSub, 0)
            area < 0.3f -> say(s.liveCloser, s.liveCloserSub, 0)
            abs(dx) > 0.07f || abs(dy) > 0.07f -> {
                val dir = if (abs(dx) >= abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                say(s.liveMove(dir), s.liveCenterSub, 0)
            }
            max(rt, rl) > 1.28f -> say(s.liveFlat, s.liveFlatSub, 0)
            stable >= 4 -> { good = true; say(s.liveGood, s.liveGoodSub, 2) }
            else -> say(s.liveAlmost, s.liveAlmostSub, 1)
        }
    }
}

@Composable
private fun CameraContent(vm: AppVM, leave: () -> Unit) {
    val s = LocalStr.current
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val strNow by androidx.compose.runtime.rememberUpdatedState(s)

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // about 8 MP (well above 1080p) so text stays sharp; never the slow 12-50 MP sensor mode
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(3264, 2448),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    ).build(),
            ).build()
    }
    // a small, light stream of frames for the live page outline (the photo itself is still the big one above)
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    ).build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val guide = remember { LiveGuide() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(ctx).awaitCompat(ctx)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            val sel = CameraSelector.DEFAULT_BACK_CAMERA
            camera = try {
                provider.bindToLifecycle(lifecycleOwner, sel, preview, imageCapture, analysis)
            } catch (e: IllegalArgumentException) {          // an old phone that cannot run three streams: no live outline, the camera still works
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, sel, preview, imageCapture)
            }
        } catch (e: Exception) {
            vm.toast(s.errPrefix + (e.message ?: ""))
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    // live page finder: about 3-6 looks per second, never two at once, the screen never waits for it
    DisposableEffect(Unit) {
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val now = System.currentTimeMillis()
                if (guide.on && !guide.busy.get() && now - guide.last > 120) {
                    val f = frameToRgb(proxy, 320)
                    if (f != null) {
                        guide.last = now
                        guide.busy.set(true)
                        scope.launch {
                            try {
                                val q = Engine.live(f.rgb, f.w, f.h)
                                if (guide.on) guide.update(q, f.w, f.h, strNow)
                            } catch (_: Exception) {
                            } finally { guide.busy.set(false) }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally { proxy.close() }
        }
        onDispose {
            guide.on = false
            try { analysis.clearAnalyzer() } catch (_: Exception) { }
            analysisExecutor.shutdown()
            try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) { }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.importUris(uris) }
    }

    fun shoot() {
        if (shooting || vm.busy != null) return
        shooting = true
        val hint = guide.prev                                   // where the page was seen a moment ago: the crop screen starts from there
        guide.on = false
        scope.launch {
            flash.snapTo(1f)
            atFullSpeed { flash.animateTo(0f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }   // soft "got it" pulse
        }
        val file = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // no "processing" screen here: the picture is prepared natively and the crop screen opens right away
                    scope.launch {
                        try {
                            val id = vm.addPhoto(file)
                            if (id != null) {
                                vm.photoTaken(id, hint)
                                // a retake goes back to the review screen (the new page takes the old one's place); a normal shot goes on shooting
                                vm.queueCrop(listOf(id), if (vm.retakeId != null) Screen.Review else Screen.Camera, fromShutter = true)
                            } else guide.on = true
                        } finally { shooting = false }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    shooting = false
                    guide.on = true
                    vm.toast(s.errPrefix + (e.message ?: ""))
                }
            },
        )
    }

    val count = vm.pages.count { it.cropped != null }
    val clock = rememberClock()
    val shown = remember { FloatArray(9) }                     // the outline as it is drawn (8 numbers) + how visible it is: glides to the newest look

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView }, modifier = Modifier.fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { pos ->
                        val c = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                        c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            clock.value                                         // redraw on every screen frame (smooth gliding)
            val W = size.width; val H = size.height
            val f = flash.value
            val tq = guide.quad
            if (tq != null) {
                if (shown[8] < 0.02f) { for (i in 0 until 4) { shown[2 * i] = tq[i].x; shown[2 * i + 1] = tq[i].y } }
                else for (i in 0 until 8) { val tv = if (i % 2 == 0) tq[i / 2].x else tq[i / 2].y; shown[i] += (tv - shown[i]) * 0.3f }
                shown[8] += (1f - shown[8]) * 0.2f
            } else shown[8] += (0f - shown[8]) * 0.2f
            val va = shown[8]

            // 1) no page seen yet: the plain dashed guide frame. On capture it closes in a little and turns teal, then relaxes back.
            if (va < 0.98f) {
                val mx = W * 0.08f + f * 14f
                val my = H * 0.16f + f * 14f
                val c = androidx.compose.ui.graphics.lerp(Color(0xCCFFFFFF), Color(0xFF5EEAD4), f)
                drawRoundRect(
                    c.copy(alpha = c.alpha * (1f - va)),
                    Offset(mx, my), Size(W - 2 * mx, H - 2 * my - H * 0.08f),
                    CornerRadius(24f, 24f), style = Stroke(width = 3f + 4f * f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f))),
                )
            }
            // 2) the live outline of the page: everything that is not paper is darkened (= what the crop throws away)
            if (va > 0.02f) {
                val ar = guide.aspect
                val dw = if (W / H < ar) W else H * ar
                val dh = if (W / H < ar) W / ar else H
                val ox = (W - dw) / 2f; val oy = (H - dh) / 2f
                val pts = List(4) { Offset(ox + shown[2 * it] * dw, oy + shown[2 * it + 1] * dh) }
                val good = guide.good
                val col = androidx.compose.ui.graphics.lerp(if (good) Color(0xFF3DDC84) else Color(0xFFFFD166), Color(0xFF5EEAD4), f)
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(ox, oy, ox + dw, oy + dh))
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                drawPath(outside, Color.Black.copy(alpha = 0.45f * va))
                val inside = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                drawPath(inside, col.copy(alpha = (if (good) 0.14f else 0.08f) * va))
                drawPath(inside, col.copy(alpha = 0.9f * va), style = Stroke(width = 3.2f, join = StrokeJoin.Round))
                val bs = 9f
                for (i in 0 until 4) {
                    val p0 = pts[i]; val a = pts[(i + 1) % 4]; val b = pts[(i + 3) % 4]
                    val la = hypot(a.x - p0.x, a.y - p0.y).coerceAtLeast(1f)
                    val lb = hypot(b.x - p0.x, b.y - p0.y).coerceAtLeast(1f)
                    val L = min(min(34f * density, 0.22f * la), 0.22f * lb)
                    val pa = Offset(p0.x + (a.x - p0.x) / la * L, p0.y + (a.y - p0.y) / la * L)
                    val pb = Offset(p0.x + (b.x - p0.x) / lb * L, p0.y + (b.y - p0.y) / lb * L)
                    val br = Path().apply { moveTo(pa.x, pa.y); lineTo(p0.x, p0.y); lineTo(pb.x, pb.y) }
                    drawPath(br, col.copy(alpha = va), style = Stroke(width = bs, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (flash.value > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.16f * flash.value)))   // only a faint glow
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = leave) { Icon(Icons.Filled.Close, s.back, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                GlassButton(onClick = { torch = !torch }, active = torch) { FlashIcon(on = torch) }
            }
            // what to do now (too close, move left, hold still ...): changes softly, colour follows how good the shot is
            val msgTitle = guide.title ?: s.camHint
            val msgSub = if (guide.title == null) "" else guide.sub
            val msgBg = animateColorAlways(
                when (if (guide.title == null) 1 else guide.kind) { 2 -> Color(0xDD14532D); 0 -> Color(0xDD78350F); else -> Color(0x99000000) }, 300,
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp), color = msgBg) {
                    FadeSwap(Triple(msgTitle, msgSub, guide.kind), 140) { (t, sb, _) ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            if (sb.isNotEmpty()) Text(sb, color = Color(0xCCFFFFFF), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
            val rid = vm.retakeId
            if (rid != null) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xDD4F46E5)) {
                        Text(
                            s.retaking(vm.pages.indexOfFirst { it.id == rid } + 1), Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GalleryThumb(vm.pages.lastOrNull()) { pick.launch("image/*") }
                }
                Shutter(pop = flash.value, ready = guide.good, onClick = { shoot() })
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (count > 0) {
                        Surface(
                            shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { if (vm.retakeId != null) leave() else { vm.current = (count - 1).coerceAtLeast(0); vm.screen = Screen.Review } },
                        ) {
                            Text(s.finish(count), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassButton(onClick: () -> Unit, size: Int = 44, active: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (active) Color(0x40FBBF24) else Color(0x66000000))
            .then(if (active) Modifier.border(1.5.dp, Color(0xFFFBBF24), CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Shutter(pop: Float, ready: Boolean, onClick: () -> Unit) {
    val ring = animateColorAlways(if (ready) Color(0xFF3DDC84) else Color.White, 250)
    Box(
        Modifier.size(78.dp).graphicsLayer { val k = 1f - 0.12f * pop; scaleX = k; scaleY = k }.border(4.dp, ring, CircleShape).padding(7.dp).clip(CircleShape)
            .background(Color.White).clickable(onClick = onClick),
    )
}

/** Lightning bolt. Off: white outline with a slash through it. On: solid amber. */
@Composable
private fun FlashIcon(on: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val k = size.width / 24f
        fun pt(x: Float, y: Float) = Offset(x * k, y * k)
        val bolt = Path().apply {
            moveTo(13.5f * k, 2f * k); lineTo(5f * k, 13.5f * k); lineTo(11f * k, 13.5f * k)
            lineTo(10f * k, 22f * k); lineTo(19f * k, 10f * k); lineTo(13f * k, 10f * k); close()
        }
        if (on) {
            drawPath(bolt, Color(0xFFFBBF24))
            drawPath(bolt, Color(0xFFFBBF24), style = Stroke(width = 1.6f * k, join = StrokeJoin.Round))
        } else {
            drawPath(bolt, Color.White, style = Stroke(width = 1.8f * k, join = StrokeJoin.Round))
            drawLine(Color(0xFF111111), pt(4f, 3.6f), pt(20f, 20.4f), strokeWidth = 4.4f * k, cap = StrokeCap.Round)
            drawLine(Color.White, pt(4f, 3.6f), pt(20f, 20.4f), strokeWidth = 1.8f * k, cap = StrokeCap.Round)
        }
    }
}

/** Small round gallery button: shows the last page of this scan; before the first page a little picture symbol. Opens the gallery. */
@Composable
private fun GalleryThumb(last: Page?, onClick: () -> Unit) {
    val path = last?.let { it.cropped ?: it.work }
    val bmp by produceState<ImageBitmap?>(path?.let { cachedBitmap(it, 160) }, path) {
        value = path?.let { withContext(Dispatchers.IO) { decodeBitmap(it, 160) } }
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0x66000000))
            .border(2.dp, Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Canvas(Modifier.size(22.dp)) {
                val w = size.width; val h = size.height
                val st = Stroke(width = w * 0.09f, join = StrokeJoin.Round, cap = StrokeCap.Round)
                drawRoundRect(Color.White, Offset(w * 0.04f, h * 0.12f), Size(w * 0.92f, h * 0.76f), CornerRadius(w * 0.14f, w * 0.14f), style = st)
                drawCircle(Color.White, w * 0.09f, Offset(w * 0.32f, h * 0.38f))
                val hill = Path().apply {
                    moveTo(w * 0.08f, h * 0.80f); lineTo(w * 0.38f, h * 0.52f); lineTo(w * 0.56f, h * 0.68f)
                    lineTo(w * 0.72f, h * 0.50f); lineTo(w * 0.92f, h * 0.76f)
                }
                drawPath(hill, Color.White, style = st)
            }
        }
    }
}
