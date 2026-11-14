package app.docscan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch
import java.io.File

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

    val leave = { vm.screen = if (vm.pages.any { it.cropped != null }) Screen.Review else Screen.Home }
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

@Composable
private fun CameraContent(vm: AppVM, leave: () -> Unit) {
    val s = LocalStr.current
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(ctx).awaitCompat(ctx)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            vm.toast(s.errPrefix + (e.message ?: ""))
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(Unit) {
        onDispose { try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) { } }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.importUris(uris) }
    }

    fun shoot() {
        if (shooting || vm.busy != null) return
        shooting = true
        scope.launch { flash.snapTo(1f); flash.animateTo(0f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }   // soft "got it" pulse
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
                            if (id != null) vm.queueCrop(listOf(id), Screen.Camera)
                        } finally { shooting = false }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    shooting = false
                    vm.toast(s.errPrefix + (e.message ?: ""))
                }
            },
        )
    }

    val count = vm.pages.count { it.cropped != null }

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
        // guide frame
        Canvas(Modifier.fillMaxSize()) {
            // on capture the frame closes in a little and turns teal, then relaxes back: a smooth "caught the page"
            val f = flash.value
            val mx = size.width * 0.08f + f * 14f
            val my = size.height * 0.16f + f * 14f
            drawRoundRect(
                androidx.compose.ui.graphics.lerp(Color(0xCCFFFFFF), Color(0xFF5EEAD4), f),
                Offset(mx, my), Size(size.width - 2 * mx, size.height - 2 * my - size.height * 0.08f),
                CornerRadius(24f, 24f), style = Stroke(width = 3f + 4f * f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f))),
            )
        }
        if (flash.value > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.16f * flash.value)))   // only a faint glow
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = leave) { Icon(Icons.Filled.Close, s.back, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0x66000000)) {
                    Text(s.camHint, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                GlassButton(onClick = { torch = !torch }, active = torch) {
                    Text("💡", fontSize = 20.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GlassButton(onClick = { pick.launch("image/*") }, size = 52) { Icon(Icons.Filled.Add, s.gallery, tint = Color.White) }
                }
                Shutter(pop = flash.value, onClick = { shoot() })
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (count > 0) {
                        Surface(
                            shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { vm.current = (count - 1).coerceAtLeast(0); vm.screen = Screen.Review },
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
            .background(if (active) Color(0xAAFBBF24) else Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Shutter(pop: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).graphicsLayer { val k = 1f - 0.12f * pop; scaleX = k; scaleY = k }.border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
            .background(Color.White).clickable(onClick = onClick),
    )
}
