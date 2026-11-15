package app.docscan

import androidx.compose.foundation.gestures.detectTransformGestures

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
import androidx.compose.foundation.layout.width
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

    val leave = {
        vm.cancelRetake()
        vm.cancelIdCard()
        if (vm.camForSig) { vm.camForSig = false; vm.screen = if (vm.pages.isEmpty()) Screen.Home else Screen.Review }
        else vm.screen = if (vm.pages.any { it.cropped != null }) Screen.Review else Screen.Home
    }
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

/** A tiny fingerprint of a camera frame (12 x 12 brightness cells, overall brightness removed). Tells if the phone moved since the page was locked. */
private fun frameSig(rgb: ByteArray, w: Int, h: Int): IntArray {
    val out = IntArray(144)
    var sum = 0
    for (cy in 0 until 12) for (cx in 0 until 12) {
        val x = (((cx + 0.5f) / 12f) * w).toInt().coerceIn(1, w - 2)
        val y = (((cy + 0.5f) / 12f) * h).toInt().coerceIn(1, h - 2)
        var a = 0
        for (dy in -1..1) for (dx in -1..1) {
            val o = ((y + dy) * w + x + dx) * 3
            a += ((rgb[o].toInt() and 255) * 77 + (rgb[o + 1].toInt() and 255) * 150 + (rgb[o + 2].toInt() and 255) * 29) shr 8
        }
        out[cy * 12 + cx] = a / 9
        sum += a / 9
    }
    val m = sum / 144
    for (i in 0 until 144) out[i] -= m
    return out
}

private fun sigDiff(a: IntArray, b: IntArray): Float {
    var s = 0
    for (i in a.indices) s += abs(a[i] - b[i])
    return s / a.size.toFloat()
}

private fun quadDist(a: List<Offset>, b: List<Offset>): Float = (0 until 4).sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y).toDouble() }.toFloat() / 4f

private fun quadBlend(a: List<Offset>, b: List<Offset>, t: Float): List<Offset> =
    List(4) { Offset(a[it].x + (b[it].x - a[it].x) * t, a[it].y + (b[it].y - a[it].y) * t) }

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
    var focusTick by mutableIntStateOf(0)                  // +1 every time a page gets locked: the camera focuses on it at once
    var last = 0L
    private var stable = 0
    private var lastLight = 0
    private var lightCount = 0
    private var miss = 0
    var prev: List<Offset>? = null
    val busy = AtomicBoolean(false)

    // LOCK: the page is found ONCE (two looks in a row agree), then it is held. While the picture does not move nothing is searched
    // again (so no flicker, no wrong re-detection); a new search starts only when the camera really moved.
    @Volatile private var lockSig: IntArray? = null
    private var lockQ: List<Offset>? = null
    private var cand: List<Offset>? = null
    private var candN = 0
    private var lockMiss = 0
    val isLocked: Boolean get() = lockSig != null

    @Volatile var on = true
        set(v) { field = v; if (v) clearLock() }

    private fun clearLock() { lockSig = null; lockQ = null; cand = null; candN = 0; lockMiss = 0; stable = 0; good = false }

    private fun lock(q: List<Offset>, sig: IntArray) {
        lockQ = q; lockSig = sig; cand = null; candN = 0; lockMiss = 0
        quad = q; prev = q; stable = 3                      // one more steady look and the shot is "good"
        focusTick++                                         // focus on the page right now
    }

    /** Called on every frame (cheap). true = the page finder must run; false = nothing changed, keep the lock. */
    fun decide(sig: IntArray): Boolean {
        val ls = lockSig ?: return true
        return sigDiff(ls, sig) >= 4.5f
    }

    /** The picture did not move: keep the locked outline, only re-judge (steady, light, ...). */
    fun hold(w: Int, h: Int, s: Str, rgb: ByteArray) {
        val lq = lockQ ?: return
        aspect = w.toFloat() / h
        stable++
        quad = lq; prev = lq
        judge(lq, w, h, s, rgb)
    }

    private fun say(t: String, sb: String, k: Int) { title = t; sub = sb; kind = k }

    fun update(q: List<Offset>?, w: Int, h: Int, s: Str, rgb: ByteArray, sig: IntArray) {
        aspect = w.toFloat() / h
        val ls = lockSig
        if (ls != null && sigDiff(ls, sig) >= 11f) clearLock()          // the phone moved a lot: the old lock is not the page any more
        val lk = lockQ
        if (q == null) {
            stable = 0; good = false
            if (lk != null && ++lockMiss <= 4) return                   // a locked page is not dropped because of one empty look
            if (lk != null) clearLock()
            cand = null; candN = 0
            if (++miss > 1) { prev = null; quad = null; say(s.liveLooking, s.liveLookingSub, 1) }
            return
        }
        miss = 0; lockMiss = 0
        if (lk != null) {
            if (quadDist(q, lk) < 0.04f) {                              // same page: refine a little, keep it locked
                val b = quadBlend(lk, q, 0.3f)
                lockQ = b; lockSig = sig; cand = null; candN = 0
                quad = b; prev = b; stable++
                judge(b, w, h, s, rgb)
                return
            }
            val c = cand                                                // a different outline: it must show up twice before it replaces the lock
            candN = if (c != null && quadDist(c, q) < 0.03f) candN + 1 else 1
            cand = q
            if (candN >= 2) { lock(if (c != null) quadBlend(c, q, 0.5f) else q, sig); judge(lockQ ?: q, w, h, s, rgb) }
            else { stable = 0; judge(lk, w, h, s, rgb) }
            return
        }
        // looking for the page: show the first look at once, lock when two looks in a row agree
        val c = cand
        candN = if (c != null && quadDist(c, q) < 0.03f) candN + 1 else 1
        cand = q
        if (candN >= 2) {
            lock(if (c != null) quadBlend(c, q, 0.5f) else q, sig)
            judge(lockQ ?: q, w, h, s, rgb)
            return
        }
        val p = prev
        val sm = if (p != null) quadBlend(p, q, 0.5f) else q
        prev = sm; quad = sm; stable = 0
        judge(sm, w, h, s, rgb)
    }

    /** 0 = fine, 1 = too dark, 2 = shadow across the paper, 3 = glare. Looks at the paper only (inside the outline's box), a few hundred samples. */
    private fun light(q: List<Offset>, w: Int, h: Int, rgb: ByteArray): Int {
        val x0 = (q.minOf { it.x } * w).toInt().coerceIn(0, w - 1); val x1 = (q.maxOf { it.x } * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (q.minOf { it.y } * h).toInt().coerceIn(0, h - 1); val y1 = (q.maxOf { it.y } * h).toInt().coerceIn(y0 + 1, h)
        val gx = 4; val gy = 5
        val cell = FloatArray(gx * gy)
        var glare = 0; var n = 0
        val tmp = IntArray(36)
        for (cy in 0 until gy) for (cx in 0 until gx) {
            var k = 0
            for (j in 0 until 6) for (i in 0 until 6) {
                val px = (x0 + ((cx + (i + 0.5f) / 6f) / gx * (x1 - x0))).toInt().coerceIn(0, w - 1)
                val py = (y0 + ((cy + (j + 0.5f) / 6f) / gy * (y1 - y0))).toInt().coerceIn(0, h - 1)
                val o = (py * w + px) * 3
                val r = rgb[o].toInt() and 255; val g = rgb[o + 1].toInt() and 255; val b = rgb[o + 2].toInt() and 255
                val l = (r * 77 + g * 150 + b * 29) shr 8
                tmp[k++] = l
                if (l >= 251) glare++
                n++
            }
            tmp.sort()
            cell[cy * gx + cx] = tmp[27].toFloat()              // the paper brightness of this part (text is darker, so not the average)
        }
        val mx = cell.max(); val mn = cell.min()
        val med = cell.sorted()[cell.size / 2]
        return when {
            med < 95f -> 1
            mx > 60f && mn / mx < 0.62f -> 2
            glare.toFloat() / n > 0.07f -> 3
            else -> 0
        }
    }

    private fun judge(q: List<Offset>, w: Int, h: Int, s: Str, rgb: ByteArray?) {
        good = false
        val area = polyArea(q)
        val cx = q.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = q.sumOf { it.y.toDouble() }.toFloat() / 4f
        val dx = cx - 0.5f; val dy = cy - 0.5f
        val clip = q.any { it.x < 0.015f || it.x > 0.985f || it.y < 0.015f || it.y > 0.985f }
        fun d(a: Offset, b: Offset) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val rt = max(d(q[0], q[1]), d(q[3], q[2])) / max(1f, min(d(q[0], q[1]), d(q[3], q[2])))
        val rl = max(d(q[0], q[3]), d(q[1], q[2])) / max(1f, min(d(q[0], q[3]), d(q[1], q[2])))
        // light is judged on 3 frames in a row, so one flicker does not change the message
        val li = if (rgb != null) light(q, w, h, rgb) else 0
        lightCount = if (li != 0 && li == lastLight) lightCount + 1 else if (li != 0) 1 else 0
        lastLight = li
        val lightIssue = if (lightCount >= 3) li else 0
        when {
            clip -> say(s.liveBack, s.liveBackSub, 0)
            area < 0.3f -> say(s.liveCloser, s.liveCloserSub, 0)
            abs(dx) > 0.07f || abs(dy) > 0.07f -> {
                val dir = if (abs(dx) >= abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                say(s.liveMove(dir), s.liveCenterSub, 0)
            }
            max(rt, rl) > 1.28f -> say(s.liveFlat, s.liveFlatSub, 0)
            lightIssue == 1 -> say(s.liveDark, s.liveDarkSub, 0)
            lightIssue == 2 -> say(s.liveShadow, s.liveShadowSub, 0)
            lightIssue == 3 -> say(s.liveGlare, s.liveGlareSub, 0)
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
    var camZoom by remember { mutableFloatStateOf(1f) }
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
                // looking: about 15 looks per second; locked: only a cheap check whether the picture moved
                if (guide.on && !guide.busy.get() && now - guide.last > (if (guide.isLocked) 90 else 60)) {
                    val f = frameToRgb(proxy, 320)
                    if (f != null) {
                        guide.last = now
                        val sig = frameSig(f.rgb, f.w, f.h)
                        val search = guide.decide(sig)
                        guide.busy.set(true)
                        scope.launch {
                            try {
                                if (guide.on) {
                                    if (search) {
                                        val q = Engine.live(f.rgb, f.w, f.h)
                                        if (guide.on) guide.update(q, f.w, f.h, strNow, f.rgb, sig)
                                    } else guide.hold(f.w, f.h, strNow, f.rgb)
                                }
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
        if (uris.isNotEmpty()) scope.launch { if (vm.camForSig) vm.importSignature(uris.first()) else vm.importUris(uris) }
    }
    // tips: shown by themselves the first time, and whenever the Tips button is pressed
    var tips by remember { mutableStateOf(if (vm.camForSig) !vm.sigTipsSeen else !vm.camTipsSeen) }
    // the moment a page is locked: focus (and meter light / white balance) right ON THE PAGE, not on the middle of the screen
    LaunchedEffect(guide.focusTick) {
        if (guide.focusTick == 0) return@LaunchedEffect
        val c = camera ?: return@LaunchedEffect
        val q = guide.prev ?: return@LaunchedEffect
        try {
            val W = previewView.width.toFloat(); val H = previewView.height.toFloat()
            if (W <= 0f || H <= 0f) return@LaunchedEffect
            val ar = guide.aspect
            val dw = if (W / H < ar) W else H * ar
            val dh = if (W / H < ar) W / ar else H
            val ox = (W - dw) / 2f; val oy = (H - dh) / 2f
            val cx = q.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = q.sumOf { it.y.toDouble() }.toFloat() / 4f
            val p = previewView.meteringPointFactory.createPoint(ox + cx * dw, oy + cy * dh)
            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
        } catch (_: Exception) { }
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
                            if (vm.camForSig) {
                                vm.startSignature(file)
                                guide.on = true
                                return@launch
                            }
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
                }
                .pointerInput(camera) {
                    // pinch with two fingers = camera zoom (the preview, the live outline and the photo all zoom together)
                    detectTransformGestures { _, _, z, _ ->
                        val c = camera ?: return@detectTransformGestures
                        if (z != 1f) {
                            val zs = c.cameraInfo.zoomState.value
                            val r = (camZoom * z).coerceIn(zs?.minZoomRatio ?: 1f, zs?.maxZoomRatio ?: 5f)
                            c.cameraControl.setZoomRatio(r)
                            camZoom = r
                        }
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

            // where the camera picture really is on the screen (it is shown whole, centred: black bars above / below)
            val ar = guide.aspect
            val dw = if (W / H < ar) W else H * ar
            val dh = if (W / H < ar) W / ar else H
            val ox = (W - dw) / 2f; val oy = (H - dh) / 2f

            // 1) no page seen yet: the plain dashed guide frame, INSIDE the camera picture. On capture it closes in a little and turns teal, then relaxes back.
            if (va < 0.98f) {
                val mx = dw * 0.07f + f * 14f
                val my = dh * 0.07f + f * 14f
                val c = androidx.compose.ui.graphics.lerp(Color(0xCCFFFFFF), Color(0xFF5EEAD4), f)
                drawRoundRect(
                    c.copy(alpha = c.alpha * (1f - va)),
                    Offset(ox + mx, oy + my), Size(dw - 2 * mx, dh - 2 * my),
                    CornerRadius(24f, 24f), style = Stroke(width = 3f + 4f * f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f))),
                )
            }
            // 2) the live outline of the page: everything that is not paper is darkened (= what the crop throws away)
            if (va > 0.02f) {
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
        if (tips) CamTips(vm.camForSig) { tips = false; if (vm.camForSig) vm.markSigTipsSeen() else vm.markCamTipsSeen() }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = leave) { Icon(Icons.Filled.Close, s.back, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                GlassButton(onClick = { tips = true }) { Text("?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                GlassButton(onClick = { torch = !torch }, active = torch) { FlashIcon(on = torch) }
            }
            // what to do now (too close, move left, hold still ...): changes softly, colour follows how good the shot is
            val msgTitle = guide.title ?: (if (vm.camForSig) s.sigCamHint else s.camHint)
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
            if (camZoom > 1.05f) {                                     // tap the chip: back to 1x
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0x99000000), modifier = Modifier.clickable { camera?.cameraControl?.setZoomRatio(1f); camZoom = 1f }) {
                        Text(String.format("%.1f\u00D7", camZoom), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            if (vm.idStep > 0) {                                       // ID card: which side is wanted now
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xDD0F766E)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (vm.idStep == 1) s.idFront else s.idBack, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(s.idHint, color = Color(0xCCFFFFFF), fontSize = 11.sp)
                        }
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
                    if (count > 0 && vm.idStep == 0) {
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

/** The best way to take the photo, in a few short lines (for a page, or for a signature). */
@Composable
private fun CamTips(forSig: Boolean, onClose: () -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val lines = if (forSig) listOf(s.sigTip1, s.sigTip2, s.sigTip3, s.sigTip4, s.sigTip5)
    else listOf(s.camTip1, s.camTip2, s.camTip3, s.camTip4, s.camTip5, s.camTip6)
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(if (forSig) s.sigTipsTitle else s.camTipsTitle, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(t, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)) { Text(s.gotIt, fontWeight = FontWeight.Bold) }
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
