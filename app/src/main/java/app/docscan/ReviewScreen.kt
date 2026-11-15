package app.docscan

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.width as widthDp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = vm.pages

    BackHandler { vm.screen = Screen.Home }
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { vm.screen = Screen.Home }
        return
    }
    val pager = rememberPagerState(initialPage = vm.current.coerceIn(0, pages.size - 1)) { pages.size }
    LaunchedEffect(pager.currentPage) { vm.current = pager.currentPage }
    // the list changed (page moved / deleted): the pager follows
    LaunchedEffect(vm.current, pages.size) {
        if (vm.current in pages.indices && vm.current != pager.currentPage) pager.scrollToPage(vm.current)
    }

    val cur = pages.getOrNull(pager.currentPage)
    LaunchedEffect(cur?.id, cur?.look, cur?.cropped) {
        val c = cur ?: return@LaunchedEffect
        if (!c.needsPrev) return@LaunchedEffect              // Original: nothing to make, the page is shown as it is
        if (c.prev != null && c.prevKey != c.look) delay(100)
        vm.ensurePreview(c.id, 640)                          // a quick small preview first: moving the slider feels instant
        delay(450)
        vm.ensurePreview(c.id, 1400)                         // the sharp one when the finger rests
    }
    LaunchedEffect(cur?.id, cur?.cropped, cur?.thumbs) {
        val c = cur ?: return@LaunchedEffect
        if (c.thumbs == null) vm.ensureThumbs(c.id)
    }

    val tab = vm.reviewTab                                   // 0 = effect, 1 = signature, 2 = page size, 3 = page number
    LaunchedEffect(pager.currentPage, tab) { vm.selSig = null }
    var collapsed by remember { mutableStateOf(false) }      // the control panel is folded down: the page gets all the room
    var exportMode by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var addSig by remember { mutableStateOf(false) }
    var askSigDelete by remember { mutableStateOf<String?>(null) }
    val sigBmps by rememberSigBitmaps(vm)
    val pickSig = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { vm.importSignature(uri) }
    }

    fun go(mode: String) {
        scope.launch {
            vm.busy = s.processing
            try {
                val files = vm.export(vm.fmt, vm.pdfSize)
                if (mode == "share") {
                    Store.share(ctx, files, s.shareVia)
                } else {
                    val place = withContext(Dispatchers.IO) { Store.save(ctx, files, vm.treeUri) }
                    vm.saved = Saved(place, files)
                }
            } catch (e: Exception) {
                vm.toast(s.errPrefix + (e.message ?: ""))
            } finally {
                vm.busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        // top bar: back, page, undo, more (reset), save, share
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) }
            Text(s.pageOf(pager.currentPage + 1, pages.size), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.undo() }, enabled = vm.undoCount > 0) {
                Text("↶", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (vm.undoCount > 0) cs.onSurface else cs.outline)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, s.resetAll) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(s.resetAll) }, onClick = { menuOpen = false; askReset = true })
                    if (pages.size > 1 && cur != null) DropdownMenuItem(text = { Text(s.applyAll) }, onClick = { menuOpen = false; vm.applyLookToAll(cur) })
                }
            }
            Button(
                onClick = { exportMode = "save" }, modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) { Text(s.save, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            IconButton(onClick = { exportMode = "share" }) { Icon(Icons.Filled.Share, s.share, tint = cs.primary) }
        }

        // page thumbnails: tap = open the page, press and hold + drag left / right = change the order
        ThumbStrip(vm, pager.currentPage) { i -> scope.launch { atFullSpeed { pager.animateScrollToPage(i) } } }

        // what the saved page will look like (on the page size and page number tabs)
        if (tab >= 2) {
            val paperNow = if (vm.fmt == "pdf") vm.pdfSize else "fit"
            Text(
                s.previewCaption + " · " + Paper.label(paperNow, s) + (Paper.mm(paperNow)?.let { " ($it)" } ?: ""),
                fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // the page itself
        var zoomed by remember { mutableStateOf(false) }      // a page is zoomed in: one finger moves the picture, not the pager
        HorizontalPager(
            pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            userScrollEnabled = !zoomed && tab != 1,
        ) { i ->
            pages.getOrNull(i)?.let { pg ->
                when (tab) {
                    0 -> EffectPage(pg, sigBmps) { zoomed = it }
                    1 -> {
                        val bmp by rememberPageBitmap(pg)
                        SignPage(vm, pg, bmp, sigBmps)
                    }
                    else -> {
                        val bmp by rememberPageBitmap(pg)
                        ZoomBox(Modifier.fillMaxSize(), onZoomed = { zoomed = it }) {
                            PaperPreview(
                                bmp, if (vm.fmt == "pdf") vm.pdfSize else "fit", vm.numPos, vm.numSize, i + 1,
                                Modifier.fillMaxSize(), pg.sigs, sigBmps,
                            )
                        }
                    }
                }
            }
        }

        // controls
        Surface(color = cs.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 14.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                Box(Modifier.fillMaxWidth().height(22.dp).clickable { collapsed = !collapsed }, contentAlignment = Alignment.Center) {
                    val tilt by animateFloatAlways(if (collapsed) 1f else 0f, tween(220), "handle")
                    Canvas(Modifier.size(36.dp, 10.dp)) {
                        val w = size.width; val h = size.height
                        val st = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val endY = h * (0.3f + 0.4f * tilt); val midY = h * (0.7f - 0.4f * tilt)
                        val p = Path().apply { moveTo(w * 0.08f, endY); lineTo(w * 0.5f, midY); lineTo(w * 0.92f, endY) }
                        drawPath(p, cs.outline, style = st)
                    }
                }
                if (cur != null) {
                    ReviewTabs(tab) { i -> vm.reviewTab = i; collapsed = false }
                    ExpandVisible(visible = !collapsed) {
                        Column(Modifier.fillMaxWidth().height(206.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                            when (tab) {
                                0 -> EffectControls(vm, cur)
                                1 -> SignControls(vm, cur, sigBmps, onAdd = { addSig = true }, onDelete = { askSigDelete = it })
                                2 -> SizeControls(vm)
                                else -> NumberControls(vm)
                            }
                        }
                    }
                    // page actions
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        ActionBtn(s.rotate, onClick = { scope.launch { vm.rotate(cur.id) } }) { c -> Icon(Icons.Filled.Refresh, s.rotate, tint = c, modifier = Modifier.size(22.dp)) }
                        ActionBtn(s.recrop, onClick = { vm.recrop(cur.id) }) { c -> Icon(Icons.Filled.Edit, s.recrop, tint = c, modifier = Modifier.size(22.dp)) }
                        ActionBtn(s.manualCrop, onClick = { scope.launch { vm.busy = s.working; try { vm.openTrim(cur.id) } finally { vm.busy = null } } }) { c -> CropGlyph(c) }
                        ActionBtn(s.retake, onClick = { vm.startRetake(cur.id) }) { c -> CameraGlyph(c) }
                        ActionBtn(s.add, onClick = { vm.screen = Screen.Camera }) { c -> Icon(Icons.Filled.Add, s.add, tint = c, modifier = Modifier.size(22.dp)) }
                        ActionBtn(s.delete, tint = cs.error, onClick = { askDelete = true }) { c -> Icon(Icons.Filled.Delete, s.delete, tint = c, modifier = Modifier.size(22.dp)) }
                    }
                }
            }
        }
    }

    exportMode?.let { mode ->
        ExportDialog(vm, mode, onDismiss = { exportMode = null }, onGo = { exportMode = null; go(mode) })
    }
    if (addSig) {
        SigAddDialog(
            onDismiss = { addSig = false },
            onCamera = { addSig = false; vm.camForSig = true; vm.screen = Screen.Camera },
            onGallery = { addSig = false; pickSig.launch("image/*") },
        )
    }
    askSigDelete?.let { sid ->
        AlertDialog(
            onDismissRequest = { askSigDelete = null },
            text = { Text(s.sigDeleteAsk, fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { askSigDelete = null; vm.deleteSignature(sid) }) { Text(s.delete, color = cs.error) } },
            dismissButton = { TextButton(onClick = { askSigDelete = null }) { Text(s.cancel) } },
        )
    }
    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            text = { Text(s.resetAsk, fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { askReset = false; vm.resetAll(); vm.toast(s.resetDone) }) { Text(s.resetAll, color = cs.error) } },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text(s.cancel) } },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            text = { Text(s.deleteQ, fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { askDelete = false; vm.delete(pager.currentPage) }) { Text(s.yes, color = cs.error) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(s.cancel) } },
        )
    }
    vm.saved?.let { sv ->
        Dialog(onDismissRequest = { vm.saved = null }) {
            Surface(shape = RoundedCornerShape(28.dp), color = cs.surface) {
                Column(Modifier.padding(22.dp)) {
                    Text(s.savedTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.secondary)
                    Spacer(Modifier.height(8.dp))
                    Text(s.savedTo(sv.place), fontSize = 14.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { Store.share(ctx, sv.files, s.shareVia) }, modifier = Modifier.weight(1f)) { Text(s.share) }
                        Button(onClick = { vm.clearAll() }, modifier = Modifier.weight(1f)) { Text(s.newScan, textAlign = TextAlign.Center) }
                    }
                    TextButton(onClick = { vm.saved = null }, modifier = Modifier.align(Alignment.End)) { Text(s.ok) }
                }
            }
        }
    }
}

/** A crop symbol: two corner brackets that overlap. */
@Composable
private fun CropGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val st = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(w * 0.28f, w * 0.06f); lineTo(w * 0.28f, w * 0.72f); lineTo(w * 0.94f, w * 0.72f) }, color, style = st)
        drawPath(Path().apply { moveTo(w * 0.06f, w * 0.28f); lineTo(w * 0.72f, w * 0.28f); lineTo(w * 0.72f, w * 0.94f) }, color, style = st)
    }
}

/** Four tabs with a coloured pill that slides from one tab to the next. */
@Composable
private fun ReviewTabs(selected: Int, onPick: (Int) -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val labels = listOf(s.tabEffect, s.tabSign, s.tabSize, s.pageNumber)
    val pos by animateFloatAlways(selected.toFloat(), tween(300, easing = FastOutSlowInEasing), "tabpos")
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceVariant)) {
        val tabW = maxWidth / labels.size
        Box(
            Modifier.offset { IntOffset((pos * tabW.toPx()).roundToInt(), 0) }.width(tabW).fillMaxHeight().padding(4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(cs.primary, androidx.compose.ui.graphics.lerp(cs.primary, cs.secondary, 0.4f)))),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                val c = androidx.compose.ui.graphics.lerp(cs.onSurfaceVariant, cs.onPrimary, near)
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable { onPick(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    TabGlyph(i, c, Modifier.size(20.dp))
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Tab "Effect": looks (small previews of the page) and the two sliders. */
@Composable
private fun EffectControls(vm: AppVM, cur: Page) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    // all the looks in one row that scrolls sideways (the first five are the classics, the rest are made for one purpose each)
    val looks = listOf(
        "orig" to s.fOrig, "color" to s.fColor, "vivid" to s.fVivid, "gray" to s.fGray, "bw" to s.fBw,
        "whiten" to s.fWhiten, "noshadow" to s.fNoShadow, "lighten" to s.fLighten, "whiteboard" to s.fWhiteboard,
    )
    val rowScroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rowScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((m, label) in looks) key(m) { LookTile(cur, m, label, Modifier.width(66.dp)) { vm.setLook(cur.id, mode = m) } }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (cur.mode == "orig") s.clarity else s.strength, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        ValueChip("${cur.eff}%")
    }
    FancySlider(cur.eff.toFloat(), { vm.setLook(cur.id, clarity = it.roundToInt()) }, range = 0f..100f)
    Text(
        when (cur.mode) {
            "orig" -> s.clarityHintOrig; "vivid" -> s.vividHint
            "whiten", "noshadow", "lighten", "whiteboard" -> s.extraHint(cur.mode)
            else -> s.clarityHint
        },
        fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant, maxLines = 2,
    )
    // text clarity: its own slider for every look except "Original" (there the slider above already is the text clarity)
    if (cur.mode != "orig") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.clarity, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ValueChip("${cur.oClarity}%")
        }
        FancySlider(cur.oClarity.toFloat(), { vm.setLook(cur.id, text = it.roundToInt()) }, range = 0f..100f)
        Text(s.textHint, fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun ValueChip(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = cs.primaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
    }
}

/** Tab "Signature": the collection (tap = put on this page), and size / delete of the one that is selected on the page. */
@Composable
private fun SignControls(vm: AppVM, cur: Page, bmps: Map<String, ImageBitmap>, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NewSigTile(onAdd)
        for (sg in vm.signatures) {
            key(sg.id) { SigTile(bmps[sg.id], onClick = { vm.placeSig(cur.id, sg.id) }, onLong = { onDelete(sg.id) }) }
        }
    }
    Text(
        if (vm.signatures.isEmpty()) s.sigEmptyHint else s.sigLibHint,
        fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
    )
    val sel = cur.sigs.firstOrNull { it.id == vm.selSig }
    if (sel != null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.sigSize, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ValueChip("${(sel.w * 100f).roundToInt()}%")
        }
        FancySlider(sel.w, { v -> vm.updateSig(cur.id, sel.id) { it.copy(w = v) } }, range = 0.05f..1f)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.pages.size > 1) FilledTonalButton(onClick = { vm.copySigToAll(cur.id, sel.id) }, modifier = Modifier.weight(1f)) { Text(s.sigAllPages, fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(onClick = { vm.removeSig(cur.id, sel.id) }, modifier = Modifier.weight(1f)) { Text(s.delete, fontSize = 13.sp, color = cs.error, maxLines = 1) }
        }
    } else if (cur.sigs.isNotEmpty()) {
        Text(s.sigPlaced, fontSize = 11.sp, lineHeight = 14.sp, color = cs.primary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NewSigTile(onClick: () -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(84.dp, 58.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Add, s.sigNew, tint = cs.onPrimaryContainer, modifier = Modifier.size(24.dp))
            Text(s.sigNew, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun SigTile(bmp: ImageBitmap?, onClick: () -> Unit, onLong: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(84.dp, 58.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, cs.outline, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawChecker(Offset.Zero, size, 8.dp.toPx())
            val b = bmp ?: return@Canvas
            val pad = 6.dp.toPx()
            val k = min((size.width - 2 * pad) / b.width, (size.height - 2 * pad) / b.height)
            val w = b.width * k; val h = b.height * k
            drawImage(
                b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

/** "Add a signature": camera or gallery, and a short list of how to get the cleanest result. */
@Composable
private fun SigAddDialog(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(s.sigAddTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(s.sigAddSub, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCamera, modifier = Modifier.weight(1f).height(52.dp)) { Text(s.sigCamera, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    FilledTonalButton(onClick = onGallery, modifier = Modifier.weight(1f).height(52.dp)) { Text(s.sigGallery, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.sigTipsTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                        for ((i, t) in listOf(s.sigTip1, s.sigTip2, s.sigTip3, s.sigTip4, s.sigTip5).withIndex()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.width(18.dp))
                                Text(t, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(s.cancel) }
            }
        }
    }
}

/** The picture of a page (the filtered preview when there is one). Keeps the old picture while a new one loads. */
@Composable
private fun rememberPageBitmap(pg: Page): State<ImageBitmap?> {
    val path = (if (pg.needsPrev) pg.prev else null) ?: pg.cropped
    val raw by produceState<ImageBitmap?>(path?.let { cachedBitmap(it, 1400) }, path) {
        if (path != null) value = withContext(Dispatchers.IO) { decodeBitmap(path, 1400) }
    }
    val rot = pg.rot
    // Rotate = instant: the picture that is already on the screen is simply turned (nothing is re-made, no waiting)
    return rememberUpdatedState(remember(raw, rot) { raw?.let { turned(it, rot) } })
}

/** A picture turned clockwise by quarter turns (a 1400 px picture takes a few milliseconds). */
private fun turned(b: ImageBitmap, turns: Int): ImageBitmap {
    val t = ((turns % 4) + 4) % 4
    if (t == 0) return b
    val src = b.asAndroidBitmap()
    val m = android.graphics.Matrix().apply { postRotate(90f * t) }
    return android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).asImageBitmap()
}

/**
 * The page with its effect. While a new look is being made a light sweeps over the page and a label says what happens;
 * when it is ready, a bright line wipes across the page and reveals the new look over the old one.
 */
@Composable
private fun EffectPage(pg: Page, sigBmps: Map<String, ImageBitmap> = emptyMap(), onZoomed: (Boolean) -> Unit = {}) {
    val s = LocalStr.current
    // pinch with two fingers to zoom, drag to move (when zoomed), double tap to zoom in / back
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val zoomedNow = zoom > 1.01f
    LaunchedEffect(zoomedNow) { onZoomed(zoomedNow) }
    DisposableEffect(Unit) { onDispose { onZoomed(false) } }
    val bmp by rememberPageBitmap(pg)
    val working = pg.needsPrev && pg.prevKey != pg.look
    var shownKey by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var old by remember { mutableStateOf<ImageBitmap?>(null) }
    val wipe = remember { Animatable(1f) }
    LaunchedEffect(bmp) {
        val b = bmp ?: return@LaunchedEffect
        val cur = shown
        val k = if (pg.needsPrev && pg.prev != null) pg.prevKey else pg.look
        if (cur == null) {
            shown = b; shownKey = k; wipe.snapTo(1f)
        } else if (b !== cur && k == shownKey) {
            shown = b                                         // same look, sharper picture: swap quietly, no wipe
        } else if (b !== cur) {
            shownKey = k
            old = cur; shown = b
            wipe.snapTo(0f)
            atFullSpeed { wipe.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
            old = null
        }
    }
    val clock = rememberClock()
    val busyA by animateFloatAlways(if (working) 1f else 0f, tween(250), "busy")
    val label = if (pg.prevKey.substringBefore(':') != pg.mode) s.applyingEffect else s.enhancing

    // press and hold the page: the straightened page WITHOUT any effect is shown; let go and the effect is back
    var holding by remember { mutableStateOf(false) }
    val origRaw by produceState<ImageBitmap?>(pg.cropped?.let { cachedBitmap(it, 1400) }, pg.cropped, holding) {
        val path = pg.cropped
        if (holding && path != null && value == null) value = withContext(Dispatchers.IO) { decodeBitmap(path, 1400) }
    }
    val orig = remember(origRaw, pg.rot) { origRaw?.let { turned(it, pg.rot) } }
    val origA by animateFloatAlways(if (holding && orig != null) 1f else 0f, tween(80), "orig")

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    if (zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
                    else {
                        val nz = 2.5f
                        val t = (c - tap) * (nz - 1f)
                        zoom = nz
                        pan = Offset(t.x.coerceIn(-size.width * (nz - 1f) / 2f, size.width * (nz - 1f) / 2f),
                            t.y.coerceIn(-size.height * (nz - 1f) / 2f, size.height * (nz - 1f) / 2f))
                    }
                })
            }
            .pointerInput(Unit) {
                // a finger that rests on the page (does not move, no second finger) for a moment = "show me the original"
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop * 1.5f
                    val moved = withTimeoutOrNull(350L) {
                        var ok = true
                        while (ok) {
                            val ev = awaitPointerEvent()
                            val pr = ev.changes.filter { it.pressed }
                            if (pr.isEmpty() || pr.size >= 2 || (pr[0].position - down.position).getDistance() > slop) ok = false
                        }
                        true
                    }
                    if (moved == null) {
                        holding = true
                        try {
                            var go = true
                            while (go) {
                                val ev = awaitPointerEvent()
                                val pr = ev.changes.filter { it.pressed }
                                // finger lifted (or a second finger / a big move = a zoom or a swipe): the effect comes back at once
                                go = pr.size == 1 && (pr[0].position - down.position).getDistance() <= slop * 3f
                            }
                        } finally {
                            holding = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val two = ev.changes.count { it.pressed } >= 2
                        // one finger on a page that is not zoomed stays free for the pager (swipe to the next page)
                        if (two || zoom > 1.01f) {
                            val z = ev.calculateZoom()
                            val p = ev.calculatePan()
                            if (z != 1f || p != Offset.Zero) {
                                val z0 = zoom
                                val nz = (z0 * z).coerceIn(1f, 6f)
                                if (nz <= 1.01f) { zoom = 1f; pan = Offset.Zero }
                                else {
                                    val c = Offset(size.width / 2f, size.height / 2f)
                                    val f = ev.calculateCentroid(useCurrent = false)
                                    val raw = f + p - c - (f - c - pan) * (nz / z0)      // the point under the fingers stays under the fingers
                                    val mx = size.width * (nz - 1f) / 2f
                                    val my = size.height * (nz - 1f) / 2f
                                    zoom = nz
                                    pan = Offset(raw.x.coerceIn(-mx, mx), raw.y.coerceIn(-my, my))
                                }
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                .clip(RoundedCornerShape(10.dp)),
        ) {
            val cur = shown ?: return@Canvas
            val sc = min(this.size.width / cur.width, this.size.height / cur.height)
            val dw = cur.width * sc; val dh = cur.height * sc
            val ox = (this.size.width - dw) / 2f; val oy = (this.size.height - dh) / 2f
            val off = IntOffset(ox.roundToInt(), oy.roundToInt())
            val dst = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1))
            val p = wipe.value
            val o = old
            if (o != null && p < 1f) {
                drawImage(o, dstOffset = off, dstSize = dst)
                val cx = ox + dw * p
                clipRect(left = ox, top = oy, right = cx, bottom = oy + dh) { drawImage(cur, dstOffset = off, dstSize = dst) }
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0x995EEAD4), Color.Transparent), startX = cx - 46f, endX = cx + 46f),
                    Offset(cx - 46f, oy), Size(92f, dh),
                )
                drawLine(Color.White, Offset(cx, oy), Offset(cx, oy + dh), strokeWidth = 3f)
            } else {
                drawImage(cur, dstOffset = off, dstSize = dst)
            }
            val ob = orig
            if (origA > 0.01f && ob != null) drawImage(ob, dstOffset = off, dstSize = dst, alpha = origA)
            drawSigs(pg.sigs, sigBmps, ox, oy, dw, dh)
            if (busyA > 0.01f) {
                val sweep = (clock.value / 1.3f) % 1f
                drawRect(Color.Black.copy(alpha = 0.10f * busyA), Offset(ox, oy), Size(dw, dh))
                val bw = dw * 0.38f
                val cx = ox - bw + (dw + 2f * bw) * sweep
                clipRect(left = ox, top = oy, right = ox + dw, bottom = oy + dh) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.42f * busyA), Color.Transparent),
                            startX = cx - bw / 2f, endX = cx + bw / 2f,
                        ),
                        Offset(cx - bw / 2f, oy), Size(bw, dh),
                    )
                }
            }
        }
        PopVisible(visible = holding && orig != null, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xCC111827)) {
                Text(s.showingOrig, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        PopVisible(visible = working, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xCC111827)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScanningDoc(Modifier.size(16.dp, 22.dp), paper = Color(0xFF374151))
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(12.dp),
        color = if (selected) cs.primary else cs.surfaceVariant,
    ) {
        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            )
        }
    }
}

/** Tab "Page size": output format and PDF paper size. The page above changes live. */
@Composable
private fun SizeControls(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("PDF", vm.fmt == "pdf", Modifier.weight(1f)) { vm.chooseFmt("pdf") }
        Choice("JPG", vm.fmt == "jpg", Modifier.weight(1f)) { vm.chooseFmt("jpg") }
        Choice("PNG", vm.fmt == "png", Modifier.weight(1f)) { vm.chooseFmt("png") }
    }
    if (vm.fmt == "pdf") {
        Text(s.pdfSize, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        PdfSizeRow(vm)
    } else {
        Text(s.sizeFitHint, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Tab "Page number": one switch; when on, a small page to tap the place + a size stepper. Compact on purpose. */
@Composable
private fun NumberControls(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val on = vm.numPos != "off"
    var last by remember { mutableStateOf("bc") }
    if (on) last = vm.numPos
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.numOnTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(if (on) s.numOnHint else s.numOffHint, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { vm.chooseNumPos(if (it) last else "off") })
    }
    ExpandVisible(visible = on) {
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberSpotPicker(vm.numPos, { vm.chooseNumPos(it) })
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.numWhere, fontSize = 11.sp, color = cs.onSurfaceVariant)
                    Text(s.posName(vm.numPos), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
        }
    }
    ExpandVisible(visible = on) {
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.numSizeLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(10.dp), color = cs.primaryContainer) {
                        Text(
                            "${vm.numSize} pt", Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                NumberSizePicker(Paper.numSizes, vm.numSize) { vm.chooseNumSize(it) }
            }
        }
    }
}

/** Size chips: each number is drawn in its own (growing) size, so the choice can be seen, not just read. */
@Composable
private fun NumberSizePicker(sizes: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sizes.forEach { sz ->
            val sel = sz == selected
            val bg = animateColorAlways(if (sel) cs.primary else Color.Transparent, 180)
            Box(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable { onPick(sz) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$sz", fontSize = (10f + sz * 0.42f).sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.Bold, color = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LookTile(p: Page, mode: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sel = p.mode == mode
    val raw by produceState<ImageBitmap?>(p.thumbs?.let { cachedBitmap("${it}_$mode.jpg", 200) }, p.thumbs, mode) {
        value = p.thumbs?.let { withContext(Dispatchers.IO) { decodeBitmap("${it}_$mode.jpg", 200) } }
    }
    val bmp = remember(raw, p.rot) { raw?.let { turned(it, p.rot) } }
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant)
                .border(if (sel) 3.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(
            label, fontSize = 11.sp, maxLines = 1,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
            color = if (sel) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ActionBtn(label: String, enabled: Boolean = true, tint: Color = Color.Unspecified, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = if (!enabled) cs.outline else if (tint != Color.Unspecified) tint else cs.onSurfaceVariant
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(c)
        Text(label, fontSize = 10.5.sp, color = c, maxLines = 1)
    }
}

/** A small camera (there is no camera icon in the basic icon set). */
@Composable
private fun CameraGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val st = Stroke(width = w * 0.085f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.30f); lineTo(w * 0.30f, h * 0.30f); lineTo(w * 0.38f, h * 0.18f); lineTo(w * 0.62f, h * 0.18f)
            lineTo(w * 0.70f, h * 0.30f); lineTo(w * 0.92f, h * 0.30f); lineTo(w * 0.92f, h * 0.82f); lineTo(w * 0.08f, h * 0.82f); close()
        }
        drawPath(body, color, style = st)
        drawCircle(color, w * 0.17f, Offset(w * 0.5f, h * 0.56f), style = st)
    }
}

/**
 * The page thumbnails. Tap = open that page. Press and hold a thumbnail, then drag it left or right: the other pages slide
 * aside and it drops into the new place (near the edge the strip scrolls by itself). A small line below says that this works.
 */
@Composable
private fun ThumbStrip(vm: AppVM, selected: Int, onSelect: (Int) -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val pages = vm.pages
    val itemW = 44.dp
    val stepPx = with(density) { (itemW + 8.dp).toPx() }
    val padPx = with(density) { 14.dp.toPx() }
    val scroll = rememberScrollState()
    var viewW by remember { mutableIntStateOf(0) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val n by rememberUpdatedState(pages.size)
    val totalDp = with(density) { (padPx * 2 + stepPx * pages.size).toDp() }

    // the selected page stays in view
    LaunchedEffect(selected, viewW) {
        if (dragId == null && viewW > 0) {
            val target = (padPx + selected * stepPx - (viewW - stepPx) / 2f).roundToInt().coerceAtLeast(0)
            atFullSpeed { scroll.animateScrollTo(target, tween(300)) }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(60.dp).onSizeChanged { viewW = it.width }.horizontalScroll(scroll, enabled = dragId == null)) {
            Box(Modifier.widthDp(totalDp).height(60.dp).padding(top = 2.dp)) {
                pages.forEachIndexed { i, p ->
                    key(p.id) {
                        val sel = i == selected
                        val isDrag = dragId == p.id
                        val slot = padPx + i * stepPx
                        val x = remember { androidx.compose.animation.core.Animatable(slot) }
                        LaunchedEffect(i, isDrag, stepPx) {
                            if (!isDrag) atFullSpeed { x.animateTo(slot, tween(230, easing = FastOutSlowInEasing)) }
                        }
                        val lift by animateFloatAlways(if (isDrag) 1.14f else 1f, tween(160), "lift")
                        val raw by produceState<ImageBitmap?>(p.cropped?.let { cachedBitmap(it, 160) }, p.cropped) {
                            value = p.cropped?.let { withContext(Dispatchers.IO) { decodeBitmap(it, 160) } }
                        }
                        val bmp = remember(raw, p.rot) { raw?.let { turned(it, p.rot) } }
                        Box(
                            Modifier.size(itemW, 56.dp).zIndex(if (isDrag) 1f else 0f)
                                .graphicsLayer {
                                    translationX = x.value
                                    scaleX = lift; scaleY = lift
                                    shadowElevation = if (isDrag) 22f else 0f
                                    shape = RoundedCornerShape(8.dp)
                                }
                                .clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(8.dp))
                                .clickable { onSelect(vm.pages.indexOfFirst { it.id == p.id }) }
                                .pointerInput(p.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragId = p.id; dragPos = x.value
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSelect(vm.pages.indexOfFirst { it.id == p.id })
                                        },
                                        onDragEnd = { dragId = null },
                                        onDragCancel = { dragId = null },
                                        onDrag = { change, amt ->
                                            change.consume()
                                            val lo = padPx; val hi = padPx + stepPx * (n - 1)
                                            dragPos = (dragPos + amt.x).coerceIn(lo, hi)
                                            scope.launch { x.snapTo(dragPos) }
                                            val to = ((dragPos - padPx) / stepPx).roundToInt().coerceIn(0, n - 1)
                                            val from = vm.pages.indexOfFirst { it.id == p.id }
                                            if (from >= 0 && to != from) vm.moveTo(from, to)
                                            // near the edge of the strip: scroll by itself
                                            val sx = dragPos - scroll.value
                                            val dir = if (sx < stepPx * 0.4f) -1 else if (sx > viewW - stepPx * 1.4f) 1 else 0
                                            if (dir != 0) scope.launch {
                                                val c = scroll.scrollBy(dir * 22f)
                                                if (c != 0f) { dragPos = (dragPos + c).coerceIn(lo, hi); x.snapTo(dragPos) }
                                            }
                                        },
                                    )
                                },
                        ) {
                            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Text(
                                "${i + 1}", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (pages.size > 1) {
            Box(Modifier.fillMaxWidth().padding(bottom = 2.dp), contentAlignment = Alignment.Center) {
                FadeSwap(dragId != null, 150) { dragging ->
                    Surface(shape = RoundedCornerShape(10.dp), color = if (dragging) cs.primaryContainer else Color.Transparent) {
                        Text(
                            (if (dragging) "↔  " + s.reorderDrop else "⇄  " + s.reorderHint), fontSize = 11.sp,
                            color = if (dragging) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(vm: AppVM, mode: String, onDismiss: () -> Unit, onGo: () -> Unit) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
                Text(if (mode == "save") s.saveTitle else s.shareTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                FormatPicker(vm, showSize = false)
                Text(
                    (if (vm.fmt == "pdf") Paper.label(vm.pdfSize, s) + " · " else "") +
                        s.pageNumber + ": " + s.posName(vm.numPos) + (if (vm.numPos != "off") " (${vm.numSize})" else ""),
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                if (mode == "save") LocationPicker(vm)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(s.cancel) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onGo, modifier = Modifier.height(48.dp)) {
                        Text(if (mode == "save") s.save else s.share, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
