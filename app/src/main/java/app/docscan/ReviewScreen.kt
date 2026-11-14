package app.docscan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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

    var tab by remember { mutableIntStateOf(0) }            // 0 = effect, 1 = page size, 2 = page number
    var exportMode by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }

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
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) }
            Text(s.pageOf(pager.currentPage + 1, pages.size), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.undo() }, enabled = vm.undoCount > 0) { Text("↶ " + s.undo, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { askReset = true }) { Text(s.resetAll, fontWeight = FontWeight.Bold) }
        }

        // page thumbnails
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                val sel = i == pager.currentPage
                val bmp by produceState<ImageBitmap?>(p.cropped?.let { cachedBitmap(it, 160) }, p.cropped) {
                    value = p.cropped?.let { withContext(Dispatchers.IO) { decodeBitmap(it, 160) } }
                }
                Box(
                    Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
                        .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(8.dp))
                        .clickable { scope.launch { pager.animateScrollToPage(i) } },
                ) {
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(
                        "${i + 1}", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // what the saved page will look like (on the page size and page number tabs)
        if (tab != 0) {
            val paperNow = if (vm.fmt == "pdf") vm.pdfSize else "fit"
            Text(
                s.previewCaption + " · " + Paper.label(paperNow, s) + (Paper.mm(paperNow)?.let { " ($it)" } ?: ""),
                fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // the page itself
        HorizontalPager(
            pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) { i ->
            pages.getOrNull(i)?.let { pg ->
                if (tab == 0) EffectPage(pg)
                else {
                    val bmp by rememberPageBitmap(pg)
                    PaperPreview(
                        bmp, if (vm.fmt == "pdf") vm.pdfSize else "fit", vm.numPos, vm.numSize, i + 1,
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // controls
        Surface(color = cs.surface, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), shadowElevation = 10.dp) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
                if (cur != null) {
                    // two tabs: effect | page size & page number (both show a live preview above)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabPill(s.tabEffect, tab == 0, Modifier.weight(1f)) { tab = 0 }
                        TabPill(s.tabSize, tab == 1, Modifier.weight(1f)) { tab = 1 }
                        TabPill(s.pageNumber, tab == 2, Modifier.weight(1f)) { tab = 2 }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        if (tab == 0) {
                            // looks: small previews of YOUR page
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LookTile(cur, "orig", s.fOrig, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "orig") }
                                LookTile(cur, "color", s.fColor, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "color") }
                                LookTile(cur, "vivid", s.fVivid, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "vivid") }
                                LookTile(cur, "gray", s.fGray, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "gray") }
                                LookTile(cur, "bw", s.fBw, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "bw") }
                            }
                            // the ONE slider (works for every look, "Original" too)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (cur.mode == "orig") s.clarity else s.strength, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("${cur.eff}%", fontSize = 15.sp, color = cs.primary, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                if (pages.size > 1) TextButton(onClick = { vm.applyLookToAll(cur) }) { Text(s.applyAll, fontSize = 13.sp, maxLines = 1) }
                            }
                            Slider(
                                value = cur.eff.toFloat(), onValueChange = { vm.setLook(cur.id, clarity = it.roundToInt()) },
                                valueRange = 0f..100f,
                            )
                            Text(
                                when (cur.mode) { "orig" -> s.clarityHintOrig; "vivid" -> s.vividHint; else -> s.clarityHint },
                                fontSize = 12.sp, color = cs.onSurfaceVariant,
                            )
                        } else {
                            if (tab == 1) SizeControls(vm) else NumberControls(vm)
                        }
                    }

                    // page actions
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        ActionBtn(Icons.Filled.Refresh, s.rotate) { scope.launch { vm.rotate(cur.id) } }
                        ActionBtn(Icons.Filled.Edit, s.recrop) { vm.recrop(cur.id) }
                        ActionBtn(Icons.AutoMirrored.Filled.ArrowBack, s.moveLeft, pager.currentPage > 0) { vm.move(pager.currentPage, -1) }
                        ActionBtn(Icons.AutoMirrored.Filled.ArrowForward, s.moveRight, pager.currentPage < pages.size - 1) { vm.move(pager.currentPage, 1) }
                        ActionBtn(Icons.Filled.Add, s.add) { vm.screen = Screen.Camera }
                        ActionBtn(Icons.Filled.Delete, s.delete, tint = cs.error) { askDelete = true }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { exportMode = "save" }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(s.save, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(onClick = { exportMode = "share" }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(s.share, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    exportMode?.let { mode ->
        ExportDialog(vm, mode, onDismiss = { exportMode = null }, onGo = { exportMode = null; go(mode) })
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

/** The picture of a page (the filtered preview when there is one). Keeps the old picture while a new one loads. */
@Composable
private fun rememberPageBitmap(pg: Page): State<ImageBitmap?> {
    val path = (if (pg.needsPrev) pg.prev else null) ?: pg.cropped
    return produceState<ImageBitmap?>(path?.let { cachedBitmap(it, 1400) }, path) {
        if (path != null) value = withContext(Dispatchers.IO) { decodeBitmap(path, 1400) }
    }
}

/**
 * The page with its effect. While a new look is being made a light sweeps over the page and a label says what happens;
 * when it is ready, a bright line wipes across the page and reveals the new look over the old one.
 */
@Composable
private fun EffectPage(pg: Page) {
    val s = LocalStr.current
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
            wipe.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
            old = null
        }
    }
    val clock = rememberClock()
    val busyA by animateFloatAsState(if (working) 1f else 0f, tween(250), label = "busy")
    val label = if (pg.prevKey.substringBefore(':') != pg.mode) s.applyingEffect else s.enhancing

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
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
        AnimatedVisibility(
            visible = working,
            enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        ) {
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
    AnimatedVisibility(visible = on, enter = fadeIn(tween(200)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
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
    AnimatedVisibility(visible = on, enter = fadeIn(tween(200)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
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
            val bg by androidx.compose.animation.animateColorAsState(if (sel) cs.primary else Color.Transparent, tween(180), label = "szBg")
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
    val bmp by produceState<ImageBitmap?>(p.thumbs?.let { cachedBitmap("${it}_$mode.jpg", 200) }, p.thumbs, mode) {
        value = p.thumbs?.let { withContext(Dispatchers.IO) { decodeBitmap("${it}_$mode.jpg", 200) } }
    }
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant)
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
private fun ActionBtn(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = if (!enabled) cs.outline else if (tint != Color.Unspecified) tint else cs.onSurfaceVariant
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = c, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = c, maxLines = 1)
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
