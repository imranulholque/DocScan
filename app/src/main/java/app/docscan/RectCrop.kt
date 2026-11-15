package app.docscan

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A straight box on a picture, in picture pixels. Its four numbers are Compose state: drawing it follows every change. */
class RectBox(l0: Float = 0f, t0: Float = 0f, r0: Float = 0f, b0: Float = 0f) {
    var l by mutableFloatStateOf(l0)
    var t by mutableFloatStateOf(t0)
    var r by mutableFloatStateOf(r0)
    var b by mutableFloatStateOf(b0)

    fun set(l1: Float, t1: Float, r1: Float, b1: Float) { l = l1; t = t1; r = r1; b = b1 }
    fun corners(): List<Offset> = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
    fun list(): List<Float> = listOf(l, t, r, b)

    /** The smallest straight box that holds all the points (a little inside the picture edge when it would touch it). */
    fun fromPoints(p: List<Offset>, w: Int, h: Int) {
        if (p.isEmpty()) { set(0f, 0f, w.toFloat(), h.toFloat()); return }
        set(
            p.minOf { it.x }.coerceIn(0f, w - 2f), p.minOf { it.y }.coerceIn(0f, h - 2f),
            p.maxOf { it.x }.coerceIn(2f, w.toFloat()), p.maxOf { it.y }.coerceIn(2f, h.toFloat()),
        )
    }
}

private class RectDrag {
    var id = -1                      // 0..3 corner (TL TR BR BL), 4..7 side (top right bottom left), 8 move the box, 9 draw a new box
    var grab = Offset.Zero           // finger minus handle (screen px): the handle does not jump under the finger
    var start = Offset.Zero          // finger at the start (screen px), for "move" and "new box"
    var l0 = 0f; var t0 = 0f; var r0 = 0f; var b0 = 0f
    var began = false
}

/** Where a picture of iw x ih sits in a canvas of cw x ch (centred, whole): left, top, screen pixels per picture pixel. */
private fun fitOf(cw: Float, ch: Float, iw: Int, ih: Int): Triple<Float, Float, Float> {
    val sc = min(cw / iw, ch / ih)
    return Triple((cw - iw * sc) / 2f, (ch - ih * sc) / 2f, sc)
}

/**
 * A straight (rectangle) cropper. Drag a corner or a side to resize the box, drag inside it to move it, drag on the outside to
 * draw a new box. A round magnifier shows the exact spot under a handle. Everything is in picture pixels (box).
 */
@Composable
fun RectCropper(
    bmp: ImageBitmap?, iw: Int, ih: Int, box: RectBox, modifier: Modifier = Modifier,
    accent: Color = Color(0xFF5EEAD4), minSize: Float = 24f, onTouch: () -> Unit = {},
) {
    var active by remember { mutableIntStateOf(-1) }
    var focus by remember { mutableStateOf<Offset?>(null) }       // the picture point under the handle that is dragged (for the magnifier)
    val st = remember { RectDrag() }
    val gridA by animateFloatAlways(if (active >= 0) 1f else 0f, tween(200), "grid")

    Canvas(
        modifier.fillMaxSize().pointerInput(iw, ih) {
            val hit = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val (ox, oy, sc) = fitOf(size.width.toFloat(), size.height.toFloat(), iw, ih)
                fun sx(x: Float) = ox + x * sc
                fun sy(y: Float) = oy + y * sc
                val pos = down.position
                val L = sx(box.l); val T = sy(box.t); val R = sx(box.r); val B = sy(box.b)
                val handles = listOf(
                    Offset(L, T), Offset(R, T), Offset(R, B), Offset(L, B),
                    Offset((L + R) / 2f, T), Offset(R, (T + B) / 2f), Offset((L + R) / 2f, B), Offset(L, (T + B) / 2f),
                )
                var best = -1; var bd = Float.MAX_VALUE
                for (i in 0 until 4) { val d = (handles[i] - pos).getDistance(); if (d < bd) { bd = d; best = i } }
                if (bd > hit) {
                    best = -1; bd = Float.MAX_VALUE
                    for (i in 4 until 8) { val d = (handles[i] - pos).getDistance(); if (d < bd) { bd = d; best = i } }
                    if (bd > hit * 0.9f) best = -1
                }
                st.id = when {
                    best >= 0 -> best
                    pos.x in L..R && pos.y in T..B -> 8
                    else -> 9
                }
                st.grab = if (best >= 0) pos - handles[best] else Offset.Zero
                st.start = pos
                st.l0 = box.l; st.t0 = box.t; st.r0 = box.r; st.b0 = box.b
                st.began = false
                drag(down.id) { change ->
                    change.consume()
                    val p = change.position
                    if (!st.began) {
                        if ((p - st.start).getDistance() < 2f) return@drag
                        st.began = true; active = st.id; onTouch()
                        if (st.id == 9) box.set(((st.start.x - ox) / sc).coerceIn(0f, iw.toFloat()), ((st.start.y - oy) / sc).coerceIn(0f, ih.toFloat()),
                            ((st.start.x - ox) / sc).coerceIn(0f, iw.toFloat()), ((st.start.y - oy) / sc).coerceIn(0f, ih.toFloat()))
                    }
                    val fx = iw.toFloat(); val fy = ih.toFloat()
                    val x = ((p.x - st.grab.x - ox) / sc).coerceIn(0f, fx)
                    val y = ((p.y - st.grab.y - oy) / sc).coerceIn(0f, fy)
                    when (st.id) {
                        0 -> { box.l = x.coerceAtMost(box.r - minSize); box.t = y.coerceAtMost(box.b - minSize); focus = Offset(box.l, box.t) }
                        1 -> { box.r = x.coerceAtLeast(box.l + minSize); box.t = y.coerceAtMost(box.b - minSize); focus = Offset(box.r, box.t) }
                        2 -> { box.r = x.coerceAtLeast(box.l + minSize); box.b = y.coerceAtLeast(box.t + minSize); focus = Offset(box.r, box.b) }
                        3 -> { box.l = x.coerceAtMost(box.r - minSize); box.b = y.coerceAtLeast(box.t + minSize); focus = Offset(box.l, box.b) }
                        4 -> { box.t = y.coerceAtMost(box.b - minSize); focus = Offset((box.l + box.r) / 2f, box.t) }
                        5 -> { box.r = x.coerceAtLeast(box.l + minSize); focus = Offset(box.r, (box.t + box.b) / 2f) }
                        6 -> { box.b = y.coerceAtLeast(box.t + minSize); focus = Offset((box.l + box.r) / 2f, box.b) }
                        7 -> { box.l = x.coerceAtMost(box.r - minSize); focus = Offset(box.l, (box.t + box.b) / 2f) }
                        8 -> {
                            val dx = (p.x - st.start.x) / sc; val dy = (p.y - st.start.y) / sc
                            val w = st.r0 - st.l0; val h = st.b0 - st.t0
                            val nl = (st.l0 + dx).coerceIn(0f, fx - w); val nt = (st.t0 + dy).coerceIn(0f, fy - h)
                            box.set(nl, nt, nl + w, nt + h)
                        }
                        else -> {
                            val ax = ((st.start.x - ox) / sc).coerceIn(0f, fx); val ay = ((st.start.y - oy) / sc).coerceIn(0f, fy)
                            box.set(min(ax, x), min(ay, y), max(ax, x), max(ay, y))
                            focus = Offset(x, y)
                        }
                    }
                }
                // finger lifted: a drawn box that is tiny becomes a usable one
                if (st.began && st.id == 9) {
                    if (box.r - box.l < minSize) box.r = min(iw.toFloat(), box.l + minSize).also { if (it - box.l < minSize) box.l = it - minSize }
                    if (box.b - box.t < minSize) box.b = min(ih.toFloat(), box.t + minSize).also { if (it - box.t < minSize) box.t = it - minSize }
                }
                active = -1; focus = null
            }
        },
    ) {
        val (ox, oy, sc) = fitOf(size.width, size.height, iw, ih)
        val dw = iw * sc; val dh = ih * sc
        bmp?.let {
            drawImage(
                it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height),
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
            )
        }
        val L = ox + box.l * sc; val T = oy + box.t * sc; val R = ox + box.r * sc; val B = oy + box.b * sc
        val dim = Color(0xAA000000)
        drawRect(dim, Offset(ox, oy), Size(dw, T - oy))
        drawRect(dim, Offset(ox, B), Size(dw, oy + dh - B))
        drawRect(dim, Offset(ox, T), Size(L - ox, B - T))
        drawRect(dim, Offset(R, T), Size(ox + dw - R, B - T))
        val sw = 2.dp.toPx()
        if (gridA > 0.01f) {                                         // thirds grid while dragging: helps to keep the cut straight
            val gc = Color.White.copy(alpha = 0.45f * gridA)
            for (k in 1..2) {
                drawLine(gc, Offset(L + (R - L) * k / 3f, T), Offset(L + (R - L) * k / 3f, B), strokeWidth = 1f)
                drawLine(gc, Offset(L, T + (B - T) * k / 3f), Offset(R, T + (B - T) * k / 3f), strokeWidth = 1f)
            }
        }
        drawRect(accent, Offset(L, T), Size(R - L, B - T), style = Stroke(width = sw))
        // round dots on the corners (match a corner), small squares on the sides (cut straight)
        val ind = Color(0xFF4F46E5)
        val cr = 11.dp.toPx(); val sq = 8.dp.toPx()
        val pts = listOf(Offset(L, T), Offset(R, T), Offset(R, B), Offset(L, B))
        val mids = listOf(Offset((L + R) / 2f, T), Offset(R, (T + B) / 2f), Offset((L + R) / 2f, B), Offset(L, (T + B) / 2f))
        for (k in 0 until 4) {
            val m = mids[k]; val r = if (active == 4 + k) sq * 1.5f else sq
            drawRect(Color.White, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r))
            drawRect(ind, Offset(m.x - r, m.y - r), Size(2 * r, 2 * r), style = Stroke(width = sw * 0.8f))
        }
        for (i in 0 until 4) {
            val r = if (active == i) cr * 1.5f else cr
            drawCircle(Color.White, r, pts[i])
            drawCircle(ind, r, pts[i], style = Stroke(width = sw))
        }
        val f = focus
        if (f != null && bmp != null && (active in 0..7 || active == 9)) {
            drawLoupe(bmp, iw, f, Offset(ox + f.x * sc, oy + f.y * sc), sc, size, accent)
        }
    }
}

/**
 * A round magnifier that floats above the finger and shows the exact picture spot under a handle (about 2.8 times bigger),
 * with a cross hair on it. Below the finger instead when there is no room above.
 * [focus] = the spot in picture pixels, [anchor] = where that spot is on the screen, [sc] = screen pixels per picture pixel.
 */
fun DrawScope.drawLoupe(bmp: ImageBitmap, imgW: Int, focus: Offset, anchor: Offset, sc: Float, canvas: Size, accent: Color) {
    val r = 54.dp.toPx()
    val zoom = 2.8f
    val cx = anchor.x.coerceIn(r + 8.dp.toPx(), canvas.width - r - 8.dp.toPx())
    var cy = anchor.y - r - 64.dp.toPx()
    if (cy < r + 8.dp.toPx()) cy = anchor.y + r + 64.dp.toPx()
    cy = cy.coerceIn(r + 8.dp.toPx(), canvas.height - r - 8.dp.toPx())
    val kb = bmp.width.toFloat() / imgW                         // picture pixel -> bitmap pixel
    val fx = focus.x * kb; val fy = focus.y * kb
    val half = (r / zoom) / sc * kb                              // half of the shown area, in bitmap pixels
    val back = r / half                                          // screen pixels per bitmap pixel inside the loupe
    val sl = max(0f, fx - half); val st = max(0f, fy - half)
    val sr = min(bmp.width.toFloat(), fx + half); val sb = min(bmp.height.toFloat(), fy + half)
    val circle = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
    drawCircle(Color(0x55000000), r + 5.dp.toPx(), Offset(cx, cy + 3.dp.toPx()))
    drawCircle(Color(0xFF111827), r, Offset(cx, cy))
    if (sr - sl >= 1f && sb - st >= 1f) {
        clipPath(circle) {
            val a = IntOffset(sl.roundToInt(), st.roundToInt())
            val s = IntSize((sr - sl).roundToInt().coerceAtLeast(1), (sb - st).roundToInt().coerceAtLeast(1))
            val d = IntOffset((cx + (sl - fx) * back).roundToInt(), (cy + (st - fy) * back).roundToInt())
            val ds = IntSize(((sr - sl) * back).roundToInt().coerceAtLeast(1), ((sb - st) * back).roundToInt().coerceAtLeast(1))
            drawImage(bmp, srcOffset = a, srcSize = s, dstOffset = d, dstSize = ds)
        }
    }
    val arm = 11.dp.toPx()
    drawLine(Color(0xAA000000), Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 3.5f)
    drawLine(Color(0xAA000000), Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 3.5f)
    drawLine(Color.White, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 1.6f)
    drawLine(Color.White, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 1.6f)
    drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
    drawCircle(accent, r - 3.dp.toPx(), Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
}
