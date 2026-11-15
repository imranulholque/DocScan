package app.docscan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()

/**
 * The page with its signatures, ready to be arranged.
 * On a signature: tap = select, drag = move, pinch with two fingers = bigger / smaller + turn, round handle = size and direction.
 * On the empty page: pinch with two fingers = zoom the page, drag (when zoomed) = move the page, double tap = zoom in / out,
 * tap = let go of the selection. The + / - buttons zoom too. Positions are kept as fractions of the page, so what is seen here is what is saved.
 */
@Composable
fun SignPage(vm: AppVM, pg: Page, bmp: ImageBitmap?, sigBmps: Map<String, ImageBitmap>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pageId by rememberUpdatedState(pg.id)
    val sigsNow by rememberUpdatedState(pg.sigs)
    val bmpsNow by rememberUpdatedState(sigBmps)
    val picNow by rememberUpdatedState(bmp)
    val selNow by rememberUpdatedState(vm.selSig)
    val accent = cs.primary
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }           // where the zoomed page is moved to (pixels, from the centred place)
    var area by remember { mutableStateOf(IntSize.Zero) }
    val lastTap = remember { longArrayOf(0L, 0L, 0L) }            // time, x, y of the last tap on the empty page (for double tap)

    /** Zoom to [nz] around [focus] (screen px) and move the page by [by]; the page always keeps covering the middle of the area. */
    fun applyZoom(nz0: Float, focus: Offset, by: Offset, w: Float, h: Float, pic: ImageBitmap) {
        val sc0 = min(w / pic.width, h / pic.height)
        val nz = nz0.coerceIn(1f, 6f)
        val dw0 = pic.width * sc0 * zoom; val dh0 = pic.height * sc0 * zoom
        val org0 = Offset((w - dw0) / 2f + pan.x, (h - dh0) / 2f + pan.y)
        val u = Offset((focus.x - org0.x) / dw0, (focus.y - org0.y) / dh0)          // the page point under the fingers
        val dw1 = pic.width * sc0 * nz; val dh1 = pic.height * sc0 * nz
        val org1 = focus + by - Offset(u.x * dw1, u.y * dh1)
        val mx = max(0f, (dw1 - w) / 2f); val my = max(0f, (dh1 - h) / 2f)
        zoom = nz
        pan = if (nz <= 1.01f) Offset.Zero else Offset((org1.x - (w - dw1) / 2f).coerceIn(-mx, mx), (org1.y - (h - dh1) / 2f).coerceIn(-my, my))
    }

    Box(modifier.fillMaxSize().onSizeChanged { area = it }) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).pointerInput(Unit) {
                awaitEachGesture {
                    val pic = picNow
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (pic == null) return@awaitEachGesture
                    val cw = size.width.toFloat(); val ch = size.height.toFloat()
                    val sc = min(cw / pic.width, ch / pic.height) * zoom
                    val dw = pic.width * sc; val dh = pic.height * sc
                    val ox = (cw - dw) / 2f + pan.x; val oy = (ch - dh) / 2f + pan.y
                    val pos = down.position

                    fun geo(pl: SigPlace): Triple<Offset, Float, Float> {          // centre (screen), width, height (screen px)
                        val b = bmpsNow[pl.sigId]
                        val wpx = pl.w * dw
                        val hpx = if (b != null) wpx * b.height / b.width else wpx * 0.4f
                        return Triple(Offset(ox + pl.cx * dw, oy + pl.cy * dh), wpx, hpx)
                    }

                    // 1) the round handle / red cross of the selected signature
                    val sel = sigsNow.firstOrNull { it.id == selNow }
                    var mode = 0                        // 0 nothing, 1 move / pinch, 2 handle
                    var targetId: String? = null
                    var centre = Offset.Zero
                    if (sel != null) {
                        val (c, wpx, hpx) = geo(sel)
                        val a = rad(sel.rot)
                        val hx = (wpx / 2f) * cos(a) - (hpx / 2f) * sin(a)
                        val hy = (wpx / 2f) * sin(a) + (hpx / 2f) * cos(a)
                        if ((pos - Offset(c.x + hx, c.y + hy)).getDistance() <= 30.dp.toPx()) {
                            mode = 2; targetId = sel.id; centre = c
                        }
                        // the red cross at the top-left corner: tap it to delete this signature
                        val pd = 6.dp.toPx()
                        val vx = -(wpx / 2f + pd); val vy = -(hpx / 2f + pd)
                        val dxp = c.x + vx * cos(a) - vy * sin(a)
                        val dyp = c.y + vx * sin(a) + vy * cos(a)
                        if (mode == 0 && (pos - Offset(dxp, dyp)).getDistance() <= 26.dp.toPx()) {
                            var moved = false
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.any { (it.position - pos).getDistance() > 16.dp.toPx() }) moved = true
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                            if (!moved) vm.removeSig(pageId, sel.id)
                            return@awaitEachGesture
                        }
                    }
                    // 2) the topmost signature under the finger
                    if (mode == 0) {
                        val pad = 12.dp.toPx()
                        for (pl in sigsNow.asReversed()) {
                            val (c, wpx, hpx) = geo(pl)
                            val a = -rad(pl.rot)
                            val dx = pos.x - c.x; val dy = pos.y - c.y
                            val lx = dx * cos(a) - dy * sin(a)
                            val ly = dx * sin(a) + dy * cos(a)
                            if (abs(lx) <= wpx / 2f + pad && abs(ly) <= hpx / 2f + pad) { mode = 1; targetId = pl.id; break }
                        }
                    }
                    if (mode == 0 || targetId == null) {
                        // the empty page: two fingers zoom it, one finger moves it (when zoomed), a tap lets go, a double tap zooms
                        var moved = false
                        do {
                            val ev = awaitPointerEvent()
                            val pr = ev.changes.filter { it.pressed }
                            if (pr.isEmpty()) break
                            if (pr.size >= 2 || zoom > 1.01f) {
                                val z = if (pr.size >= 2) ev.calculateZoom() else 1f
                                val p = ev.calculatePan()
                                if (z != 1f || p != Offset.Zero) {
                                    applyZoom(zoom * z, ev.calculateCentroid(useCurrent = false), p, cw, ch, pic)
                                    pr.forEach { it.consume() }
                                }
                            }
                            if (pr.size >= 2 || (pr[0].position - pos).getDistance() > 12.dp.toPx()) moved = true
                        } while (ev.changes.any { it.pressed })
                        if (!moved) {
                            val now = down.uptimeMillis
                            val near = (Offset(lastTap[1].toFloat(), lastTap[2].toFloat()) - pos).getDistance() < 48.dp.toPx()
                            if (lastTap[0] > 0L && now - lastTap[0] < 320L && near) {
                                lastTap[0] = 0L
                                if (zoom > 1.01f) applyZoom(1f, pos, Offset.Zero, cw, ch, pic) else applyZoom(2.5f, pos, Offset.Zero, cw, ch, pic)
                            } else {
                                lastTap[0] = now; lastTap[1] = pos.x.toLong(); lastTap[2] = pos.y.toLong()
                                vm.selSig = null
                            }
                        }
                        return@awaitEachGesture
                    }
                    val id: String = targetId
                    vm.selSig = id
                    var prev = pos
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (mode == 2) {
                            val p = pressed[0].position
                            val v0 = prev - centre; val v1 = p - centre
                            val l0 = v0.getDistance(); val l1 = v1.getDistance()
                            if (l0 > 4f && l1 > 4f) {
                                val k = l1 / l0
                                val da = (atan2(v1.y, v1.x) - atan2(v0.y, v0.x)) * 180f / PI.toFloat()
                                val dd = if (da > 180f) da - 360f else if (da < -180f) da + 360f else da
                                vm.updateSig(pageId, id) { it.copy(w = (it.w * k).coerceIn(0.04f, 1.2f), rot = (it.rot + dd + 360f) % 360f) }
                            }
                            prev = p
                            pressed.forEach { it.consume() }
                        } else {
                            val pan1 = ev.calculatePan()
                            val zoom1 = if (pressed.size >= 2) ev.calculateZoom() else 1f
                            val turn = if (pressed.size >= 2) ev.calculateRotation() else 0f
                            if (pan1 != Offset.Zero || zoom1 != 1f || turn != 0f) {
                                vm.updateSig(pageId, id) {
                                    it.copy(
                                        cx = (it.cx + pan1.x / dw).coerceIn(0.02f, 0.98f), cy = (it.cy + pan1.y / dh).coerceIn(0.02f, 0.98f),
                                        w = (it.w * zoom1).coerceIn(0.04f, 1.2f), rot = (it.rot + turn + 360f) % 360f,
                                    )
                                }
                                pressed.forEach { it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        ) {
            val pic = bmp ?: return@Canvas
            val sc = min(size.width / pic.width, size.height / pic.height) * zoom
            val dw = pic.width * sc; val dh = pic.height * sc
            val ox = (size.width - dw) / 2f + pan.x; val oy = (size.height - dh) / 2f + pan.y
            drawImage(
                pic, srcOffset = IntOffset.Zero, srcSize = IntSize(pic.width, pic.height),
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
            )
            drawSigs(pg.sigs, sigBmps, ox, oy, dw, dh)
            // the selected one: dashed frame + the round handle + the red cross
            val sel = pg.sigs.firstOrNull { it.id == vm.selSig } ?: return@Canvas
            val b = sigBmps[sel.sigId]
            val wpx = sel.w * dw
            val hpx = if (b != null) wpx * b.height / b.width else wpx * 0.4f
            val c = Offset(ox + sel.cx * dw, oy + sel.cy * dh)
            val padPx = 6.dp.toPx()
            rotate(sel.rot, c) {
                drawRect(
                    Color.White.copy(alpha = 0.9f), Offset(c.x - wpx / 2f - padPx, c.y - hpx / 2f - padPx), Size(wpx + 2 * padPx, hpx + 2 * padPx),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawRect(
                    accent, Offset(c.x - wpx / 2f - padPx, c.y - hpx / 2f - padPx), Size(wpx + 2 * padPx, hpx + 2 * padPx),
                    style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))),
                )
            }
            val a = rad(sel.rot)
            val hx = (wpx / 2f + padPx) * cos(a) - (hpx / 2f + padPx) * sin(a)
            val hy = (wpx / 2f + padPx) * sin(a) + (hpx / 2f + padPx) * cos(a)
            val h = Offset(c.x + hx, c.y + hy)
            drawCircle(Color(0x44000000), 15.dp.toPx(), Offset(h.x, h.y + 1.5.dp.toPx()))
            drawCircle(Color.White, 13.dp.toPx(), h)
            drawCircle(accent, 13.dp.toPx(), h, style = Stroke(width = 2.5.dp.toPx()))
            // red delete cross at the top-left corner
            val vx = -(wpx / 2f + padPx); val vy = -(hpx / 2f + padPx)
            val dp0 = Offset(c.x + vx * cos(a) - vy * sin(a), c.y + vx * sin(a) + vy * cos(a))
            drawCircle(Color(0x44000000), 15.dp.toPx(), Offset(dp0.x, dp0.y + 1.5.dp.toPx()))
            drawCircle(Color(0xFFDC2626), 13.dp.toPx(), dp0)
            drawCircle(Color.White, 13.dp.toPx(), dp0, style = Stroke(width = 2.dp.toPx()))
            val xr = 4.5.dp.toPx()
            drawLine(Color.White, Offset(dp0.x - xr, dp0.y - xr), Offset(dp0.x + xr, dp0.y + xr), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White, Offset(dp0.x - xr, dp0.y + xr), Offset(dp0.x + xr, dp0.y - xr), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
            // a small double arrow on the handle: "bigger / turn"
            val ar = 6.dp.toPx()
            val head = 3.5.dp.toPx()
            val lw = 2.dp.toPx()
            val u = Offset(cos(a + 0.785f), sin(a + 0.785f))
            val nrm = Offset(-u.y, u.x)
            drawLine(accent, h - u * ar, h + u * ar, strokeWidth = lw, cap = StrokeCap.Round)
            for (sgn in listOf(1f, -1f)) {
                val tip = h + u * (ar * sgn)
                val back = tip - u * (head * sgn)
                drawLine(accent, tip, back + nrm * head, strokeWidth = lw, cap = StrokeCap.Round)
                drawLine(accent, tip, back - nrm * head, strokeWidth = lw, cap = StrokeCap.Round)
            }
        }
        // zoom buttons
        if (bmp != null) {
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZoomButton("+") { applyZoom(zoom * 1.6f, Offset(area.width / 2f, area.height / 2f), Offset.Zero, area.width.toFloat(), area.height.toFloat(), bmp) }
                if (zoom > 1.01f) ZoomButton("\u2212") { applyZoom(zoom / 1.6f, Offset(area.width / 2f, area.height / 2f), Offset.Zero, area.width.toFloat(), area.height.toFloat(), bmp) }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color(0xB3111827), modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}
