package app.docscan

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    LaunchedEffect(cur?.id, cur?.mode, cur?.clarity, cur?.cropped) {
        val c = cur ?: return@LaunchedEffect
        if (c.prev != null && c.prevKey != "${c.mode}:${c.clarity}") delay(300)
        vm.ensurePreview(c.id)
    }
    LaunchedEffect(cur?.id, cur?.cropped, cur?.thumbs) {
        val c = cur ?: return@LaunchedEffect
        if (c.thumbs == null) vm.ensureThumbs(c.id)
    }

    var exportMode by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }

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
        }

        // page thumbnails
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                val sel = i == pager.currentPage
                val bmp by produceState<ImageBitmap?>(null, p.cropped) {
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

        // the page itself
        HorizontalPager(
            pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) { i ->
            pages.getOrNull(i)?.let { PagePreview(it) }
        }

        // controls
        Surface(color = cs.surface, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), shadowElevation = 10.dp) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
                if (cur != null) {
                    // looks: small previews of YOUR page
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LookTile(cur, "orig", s.fOrig, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "orig") }
                        LookTile(cur, "color", s.fColor, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "color") }
                        LookTile(cur, "gray", s.fGray, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "gray") }
                        LookTile(cur, "bw", s.fBw, Modifier.weight(1f)) { vm.setLook(cur.id, mode = "bw") }
                    }
                    // the ONE slider
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.clarity, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cur.mode == "orig") "—" else "${cur.clarity}%",
                            fontSize = 15.sp, color = cs.primary, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        if (pages.size > 1) TextButton(onClick = { vm.applyLookToAll(cur) }) { Text(s.applyAll, fontSize = 13.sp) }
                    }
                    Slider(
                        value = cur.clarity.toFloat(), onValueChange = { vm.setLook(cur.id, clarity = it.roundToInt()) },
                        valueRange = 0f..100f, enabled = cur.mode != "orig",
                    )
                    Text(
                        if (cur.mode == "orig") s.clarityOff else s.clarityHint,
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )

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

@Composable
private fun PagePreview(pg: Page) {
    val path = pg.prev ?: pg.cropped
    val bmp by produceState<ImageBitmap?>(null, path) {
        if (path != null) value = withContext(Dispatchers.IO) { decodeBitmap(path, 1400) }
    }
    val stale = pg.prevKey != "${pg.mode}:${pg.clarity}"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(it, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
        }
        if (stale) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xAA000000), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp, color = Color.White)
            }
        }
    }
}

@Composable
private fun LookTile(p: Page, mode: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sel = p.mode == mode
    val bmp by produceState<ImageBitmap?>(null, p.thumbs, mode) {
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
            label, fontSize = 12.sp, maxLines = 1,
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
                FormatPicker(vm)
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
