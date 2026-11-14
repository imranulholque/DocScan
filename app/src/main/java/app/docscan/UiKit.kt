package app.docscan

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A slider that looks and feels better than the stock one: a thick rounded track with a gradient fill, a round white thumb that
 * grows while it is held, and a big touch area. [value] and the result of [onValueChange] are in [range].
 */
@Composable
fun FancySlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onValueChange)
    var held by remember { mutableStateOf(false) }
    val grow by animateFloatAlways(if (held) 1f else 0f, tween(140), "thumb")
    val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val fillA = cs.primary
    val fillB = androidx.compose.ui.graphics.lerp(cs.primary, cs.secondary, 0.45f)
    val track = cs.surfaceVariant
    val ring = cs.primary
    Canvas(
        modifier.fillMaxWidth().height(34.dp).pointerInput(enabled, range) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pad = 14.dp.toPx()
                fun at(x: Float): Float {
                    val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    return range.start + f * (range.endInclusive - range.start)
                }
                held = true
                change(at(down.position.x))
                drag(down.id) { c -> c.consume(); change(at(c.position.x)) }
                held = false
            }
        },
    ) {
        val pad = 14.dp.toPx()
        val cy = size.height / 2f
        val th = 6.dp.toPx()
        val x0 = pad; val x1 = size.width - pad
        val xv = x0 + (x1 - x0) * frac
        drawRoundRect(track, Offset(x0, cy - th / 2), Size(x1 - x0, th), CornerRadius(th / 2, th / 2))
        if (xv > x0 + 1f) drawRoundRect(
            Brush.horizontalGradient(listOf(fillA, fillB), startX = x0, endX = x1.coerceAtLeast(x0 + 1f)),
            Offset(x0, cy - th / 2), Size(xv - x0, th), CornerRadius(th / 2, th / 2),
        )
        val r = (9.dp.toPx()) * (1f + 0.22f * grow)
        if (grow > 0.01f) drawCircle(ring.copy(alpha = 0.18f * grow), r + 8.dp.toPx() * grow, Offset(xv, cy))
        drawCircle(Color(0x33000000), r + 1.5.dp.toPx(), Offset(xv, cy + 1.dp.toPx()))
        drawCircle(Color.White, r, Offset(xv, cy))
        drawCircle(ring, r, Offset(xv, cy), style = Stroke(width = 2.5.dp.toPx()))
        if (!enabled) drawRect(cs.surface.copy(alpha = 0.5f), Offset.Zero, size)
    }
}

/** Little pictures for the four tabs of the review screen: 0 effect (sparkles), 1 signature (pen stroke), 2 page size, 3 page number. */
@Composable
fun TabGlyph(kind: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val st = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun star(cx: Float, cy: Float, r: Float) {
            val p = Path().apply {
                moveTo(cx, cy - r); quadraticBezierTo(cx, cy, cx + r, cy); quadraticBezierTo(cx, cy, cx, cy + r)
                quadraticBezierTo(cx, cy, cx - r, cy); quadraticBezierTo(cx, cy, cx, cy - r); close()
            }
            drawPath(p, color)
        }
        when (kind) {
            0 -> { star(w * 0.42f, h * 0.55f, w * 0.36f); star(w * 0.80f, h * 0.20f, w * 0.17f); star(w * 0.82f, h * 0.80f, w * 0.12f) }
            1 -> {
                val p = Path().apply {
                    moveTo(w * 0.08f, h * 0.78f)
                    cubicTo(w * 0.22f, h * 0.30f, w * 0.34f, h * 0.30f, w * 0.40f, h * 0.62f)
                    cubicTo(w * 0.44f, h * 0.84f, w * 0.52f, h * 0.60f, w * 0.60f, h * 0.60f)
                }
                drawPath(p, color, style = st)
                drawLine(color, Offset(w * 0.64f, h * 0.38f), Offset(w * 0.90f, h * 0.12f), strokeWidth = w * 0.16f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.46f), Offset(w * 0.66f, h * 0.42f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            }
            2 -> {
                drawRoundRect(color, Offset(w * 0.20f, h * 0.10f), Size(w * 0.60f, h * 0.80f), CornerRadius(w * 0.07f, w * 0.07f), style = st)
                drawLine(color, Offset(w * 0.34f, h * 0.36f), Offset(w * 0.66f, h * 0.36f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.52f), Offset(w * 0.66f, h * 0.52f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.68f), Offset(w * 0.52f, h * 0.68f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            }
            else -> {
                drawLine(color, Offset(w * 0.38f, h * 0.12f), Offset(w * 0.30f, h * 0.88f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.70f, h * 0.12f), Offset(w * 0.62f, h * 0.88f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.36f), Offset(w * 0.88f, h * 0.36f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h * 0.64f), Offset(w * 0.86f, h * 0.64f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            }
        }
    }
}

/** Checkerboard: shows which parts of a picture are see-through. */
fun DrawScope.drawChecker(origin: Offset, area: Size, cell: Float, c1: Color = Color(0xFFF1F2F6), c2: Color = Color(0xFFD9DCE6)) {
    drawRect(c1, origin, area)
    val nx = (area.width / cell).toInt() + 1
    val ny = (area.height / cell).toInt() + 1
    for (j in 0 until ny) {
        for (i in 0 until nx) {
            if ((i + j) % 2 == 1) {
                val w = minOf(cell, area.width - i * cell)
                val h = minOf(cell, area.height - j * cell)
                if (w > 0f && h > 0f) drawRect(c2, Offset(origin.x + i * cell, origin.y + j * cell), Size(w, h))
            }
        }
    }
}

/**
 * Draws signatures on a page that is shown at (ox, oy) with size (dw, dh). Each one is clipped to the page, exactly like the saved file.
 */
fun DrawScope.drawSigs(sigs: List<SigPlace>, bmps: Map<String, ImageBitmap>, ox: Float, oy: Float, dw: Float, dh: Float) {
    if (sigs.isEmpty()) return
    clipRect(ox, oy, ox + dw, oy + dh) {
        for (pl in sigs) {
            val b = bmps[pl.sigId] ?: continue
            val wpx = pl.w * dw
            val hpx = wpx * b.height / b.width
            val c = Offset(ox + pl.cx * dw, oy + pl.cy * dh)
            rotate(pl.rot, c) {
                drawImage(
                    b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                    dstOffset = IntOffset((c.x - wpx / 2f).roundToInt(), (c.y - hpx / 2f).roundToInt()),
                    dstSize = IntSize(wpx.roundToInt().coerceAtLeast(1), hpx.roundToInt().coerceAtLeast(1)),
                    alpha = pl.alpha, filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/** The pictures of every signature in the collection, decoded in the background (id -> picture). */
@Composable
fun rememberSigBitmaps(vm: AppVM): State<Map<String, ImageBitmap>> {
    val list = vm.signatures.toList()
    return produceState(
        initialValue = list.mapNotNull { sg -> cachedBitmap(sg.path, 1200)?.let { sg.id to it } }.toMap(),
        list.map { it.id },
    ) {
        value = withContext(Dispatchers.IO) { list.mapNotNull { sg -> decodeBitmap(sg.path, 1200)?.let { sg.id to it } }.toMap() }
    }
}
