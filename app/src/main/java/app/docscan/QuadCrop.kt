package app.docscan

import androidx.compose.ui.draw.clipToBounds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private fun fitQ(cw: Float, ch: Float, iw: Int, ih: Int): Triple<Float, Float, Float> {
    val sc = min(cw / iw, ch / ih)
    return Triple((cw - iw * sc) / 2f, (ch - ih * sc) / 2f, sc)
}

/**
 * Perspective crop with handles: a round dot on every corner (move a corner to match the page corner) and a square in the middle of
 * every side (move the whole side straight, the two corners on it follow). A magnifier shows the exact spot under the finger.
 * [quad] = the four corners (top-left, top-right, bottom-right, bottom-left) in picture pixels.
 */
@Composable
fun QuadCropper(
    bmp: ImageBitmap?, iw: Int, ih: Int, quad: SnapshotStateList<Offset>, modifier: Modifier = Modifier,
    accent: Color = Color(0xFF5EEAD4), onTouch: () -> Unit = {},
) {
    var active by remember { mutableIntStateOf(-1) }            // 0..3 corner, 4..7 side (top, right, bottom, left)
    var focus by remember { mutableStateOf<Offset?>(null) }
    val zp = remember { ZoomPan() }
    /** where the picture sits now (zoom and pan included): left, top, screen pixels per picture pixel */
    fun vfit(cw: Float, ch: Float): Triple<Float, Float, Float> {
        val (_, _, s0) = fitQ(cw, ch, iw, ih)
        val s2 = s0 * zp.zoom
        return Triple((cw - iw * s2) / 2f + zp.pan.x, (ch - ih * s2) / 2f + zp.pan.y, s2)
    }

    Canvas(
        modifier.fillMaxSize().clipToBounds().pointerInput(iw, ih) {
            val hit = 44.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (quad.size != 4) return@awaitEachGesture
                val (ox, oy, sc) = vfit(size.width.toFloat(), size.height.toFloat())
                fun scr(p: Offset) = Offset(ox + p.x * sc, oy + p.y * sc)
                val pos = down.position
                var best = -1; var bd = Float.MAX_VALUE
                for (i in 0 until 4) { val d = (scr(quad[i]) - pos).getDistance(); if (d < bd) { bd = d; best = i } }
                if (bd > hit) {
                    best = -1; bd = Float.MAX_VALUE
                    for (k in 0 until 4) {
                        val d = (scr((quad[k] + quad[(k + 1) % 4]) / 2f) - pos).getDistance()
                        if (d < bd) { bd = d; best = 4 + k }
                    }
                    if (bd > hit) best = -1
                }
                if (best < 0) {
                    if (zp.zoomed) drag(down.id) { change ->       // zoomed in: dragging on empty space moves the picture
                        change.consume()
                        zp.panBy(change.position - change.previousPosition, size.width.toFloat(), size.height.toFloat())
                    }
                    return@awaitEachGesture
                }
                val grab = if (best < 4) pos - scr(quad[best]) else Offset.Zero
                val k0 = best - 4
                val a0 = if (best >= 4) quad[k0] else Offset.Zero
                val b0 = if (best >= 4) quad[(k0 + 1) % 4] else Offset.Zero
                // the side moves along its own normal (so it stays parallel to where it was)
                val edge = b0 - a0
                val len = edge.getDistance().coerceAtLeast(1f)
                val nrm = Offset(-edge.y / len, edge.x / len)
                var began = false
                drag(down.id) { change ->
                    change.consume()
                    val p = change.position
                    if (!began) {
                        if ((p - pos).getDistance() < 2f) return@drag
                        began = true; active = best; onTouch()
                    }
                    if (best < 4) {
                        val x = ((p.x - grab.x - ox) / sc).coerceIn(0f, iw.toFloat())
                        val y = ((p.y - grab.y - oy) / sc).coerceIn(0f, ih.toFloat())
                        quad[best] = Offset(x, y)
                        focus = quad[best]
                    } else {
                        val d = ((p - pos) / sc)
                        val t = d.x * nrm.x + d.y * nrm.y
                        fun clampP(o: Offset) = Offset(o.x.coerceIn(0f, iw.toFloat()), o.y.coerceIn(0f, ih.toFloat()))
                        quad[k0] = clampP(a0 + nrm * t)
                        quad[(k0 + 1) % 4] = clampP(b0 + nrm * t)
                        focus = (quad[k0] + quad[(k0 + 1) % 4]) / 2f
                    }
                }
                active = -1; focus = null
            }
        }.zoomGestures(zp, onePan = false),
    ) {
        val (ox, oy, sc) = vfit(size.width, size.height)
        val dw = iw * sc; val dh = ih * sc
        bmp?.let {
            drawImage(
                it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height),
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
            )
        }
        if (quad.size != 4) return@Canvas
        val pts = quad.map { Offset(ox + it.x * sc, oy + it.y * sc) }
        // dim everything outside the quad
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(ox, oy, ox + dw, oy + dh))
            moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); lineTo(pts[3].x, pts[3].y); close()
        }
        drawPath(dim, Color(0xAA000000))
        val outline = Path().apply { moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); lineTo(pts[3].x, pts[3].y); close() }
        val sw = 2.dp.toPx()
        drawPath(outline, accent, style = Stroke(width = sw, join = StrokeJoin.Round))
        val ind = Color(0xFF4F46E5)
        val cr = 11.dp.toPx(); val sqr = 8.dp.toPx()
        for (k in 0 until 4) {
            val m = (pts[k] + pts[(k + 1) % 4]) / 2f
            val r = if (active == 4 + k) sqr * 1.5f else sqr
            drawRect(Color.White, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r))
            drawRect(ind, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r), style = Stroke(width = sw * 0.8f))
        }
        for (i in 0 until 4) {
            val r = if (active == i) cr * 1.5f else cr
            drawCircle(Color.White, r, pts[i])
            drawCircle(ind, r, pts[i], style = Stroke(width = sw))
        }
        val f = focus
        if (f != null && bmp != null && active >= 0) drawLoupe(bmp, iw, f, Offset(ox + f.x * sc, oy + f.y * sc), sc, size, accent)
    }
}
