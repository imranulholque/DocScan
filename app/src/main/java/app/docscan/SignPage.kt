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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()

/**
 * The page with its signatures, ready to be arranged: tap a signature to select it, drag it to move it, pinch with two fingers to
 * make it bigger / smaller and turn it, or drag the round handle at its corner (size and direction in one move).
 * Tap on the empty page to let go. Positions are kept as fractions of the page, so what is seen here is what is saved.
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

    Canvas(
        modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).pointerInput(Unit) {
            awaitEachGesture {
                val pic = picNow
                val down = awaitFirstDown(requireUnconsumed = false)
                if (pic == null) return@awaitEachGesture
                val sc = min(size.width.toFloat() / pic.width, size.height.toFloat() / pic.height)
                val dw = pic.width * sc; val dh = pic.height * sc
                val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
                val pos = down.position

                fun geo(pl: SigPlace): Triple<Offset, Float, Float> {          // centre (screen), width, height (screen px)
                    val b = bmpsNow[pl.sigId]
                    val wpx = pl.w * dw
                    val hpx = if (b != null) wpx * b.height / b.width else wpx * 0.4f
                    return Triple(Offset(ox + pl.cx * dw, oy + pl.cy * dh), wpx, hpx)
                }

                // 1) the round handle of the selected signature
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
                    // empty page: a tap lets go of the selection
                    var moved = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.any { (it.position - pos).getDistance() > 12.dp.toPx() }) moved = true
                    } while (ev.changes.any { it.pressed })
                    if (!moved) vm.selSig = null
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
                        val pan = ev.calculatePan()
                        val zoom = if (pressed.size >= 2) ev.calculateZoom() else 1f
                        val turn = if (pressed.size >= 2) ev.calculateRotation() else 0f
                        if (pan != Offset.Zero || zoom != 1f || turn != 0f) {
                            vm.updateSig(pageId, id) {
                                it.copy(
                                    cx = (it.cx + pan.x / dw).coerceIn(0.02f, 0.98f), cy = (it.cy + pan.y / dh).coerceIn(0.02f, 0.98f),
                                    w = (it.w * zoom).coerceIn(0.04f, 1.2f), rot = (it.rot + turn + 360f) % 360f,
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
        val sc = min(size.width / pic.width, size.height / pic.height)
        val dw = pic.width * sc; val dh = pic.height * sc
        val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
        drawImage(
            pic, srcOffset = IntOffset.Zero, srcSize = IntSize(pic.width, pic.height),
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
        )
        drawSigs(pg.sigs, sigBmps, ox, oy, dw, dh)
        // the selected one: dashed frame + the round handle
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
        // a small double arrow on the handle: "bigger / turn"
        // red delete cross at the top-left corner
        val vx = -(wpx / 2f + padPx); val vy = -(hpx / 2f + padPx)
        val dp0 = Offset(c.x + vx * cos(a) - vy * sin(a), c.y + vx * sin(a) + vy * cos(a))
        drawCircle(Color(0x44000000), 15.dp.toPx(), Offset(dp0.x, dp0.y + 1.5.dp.toPx()))
        drawCircle(Color(0xFFDC2626), 13.dp.toPx(), dp0)
        drawCircle(Color.White, 13.dp.toPx(), dp0, style = Stroke(width = 2.dp.toPx()))
        val xr = 4.5.dp.toPx()
        drawLine(Color.White, Offset(dp0.x - xr, dp0.y - xr), Offset(dp0.x + xr, dp0.y + xr), strokeWidth = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, Offset(dp0.x - xr, dp0.y + xr), Offset(dp0.x + xr, dp0.y - xr), strokeWidth = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val ar = 6.dp.toPx()
        val head = 3.5.dp.toPx()
        val lw = 2.dp.toPx()
        val u = Offset(cos(a + 0.785f), sin(a + 0.785f))
        val nrm = Offset(-u.y, u.x)
        drawLine(accent, h - u * ar, h + u * ar, strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        for (sgn in listOf(1f, -1f)) {
            val tip = h + u * (ar * sgn)
            val back = tip - u * (head * sgn)
            drawLine(accent, tip, back + nrm * head, strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(accent, tip, back - nrm * head, strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
