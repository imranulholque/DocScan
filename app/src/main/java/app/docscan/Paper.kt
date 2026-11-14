package app.docscan

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the picture sits on the page: all in PDF points (same numbers as engine.py / core.pdf_page). */
class Geom(val pw: Float, val ph: Float, val x: Float, val y: Float, val dw: Float, val dh: Float)

object Paper {
    val sizes = listOf("fit", "a4", "a5", "letter", "legal")
    val positions = listOf("tl", "tc", "tr", "bl", "bc", "br")
    val numSizes = listOf(10, 12, 14, 16, 20, 24)

    const val MARGIN = 28f          // page number distance from the page edge
    const val DIGIT_H = 0.676f      // Times digit height (em)
    const val DIGIT_W = 0.5f        // Times digit width (em)

    private val std = mapOf(
        "a4" to (595.276f to 841.89f), "a5" to (419.528f to 595.276f),
        "letter" to (612f to 792f), "legal" to (612f to 1008f),
    )

    fun label(size: String, s: Str) = when (size) {
        "a4" -> "A4"; "a5" -> "A5"; "letter" -> "Letter"; "legal" -> "Legal"; else -> s.sizeFit
    }

    fun mm(size: String): String? = when (size) {
        "a4" -> "210 × 297 mm"; "a5" -> "148 × 210 mm"; "letter" -> "216 × 279 mm"; "legal" -> "216 × 356 mm"; else -> null
    }

    fun geom(w: Int, h: Int, mode: String): Geom {
        val st = std[mode]
        if (st != null) {
            var pw = st.first; var ph = st.second
            if (w > h) { val t = pw; pw = ph; ph = t }
            val sc = min(pw / w, ph / h)
            val dw = w * sc; val dh = h * sc
            return Geom(pw, ph, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
        }
        val sc = 841.89f / max(w, h)
        return Geom(w * sc, h * sc, 0f, 0f, w * sc, h * sc)
    }

    /** Left end and baseline (y from the TOP of the page) of the page number, in points. Same formula as engine.num_xy. */
    fun numXY(pos: String, size: Float, pw: Float, ph: Float, tw: Float): Pair<Float, Float> {
        val x = when (pos.getOrNull(1)) { 'l' -> MARGIN; 'r' -> pw - MARGIN - tw; else -> (pw - tw) / 2f }
        val y = if (pos.getOrNull(0) == 't') MARGIN + DIGIT_H * size else ph - MARGIN
        return x to y
    }
}

/**
 * The page as it will be saved: paper shape (A4 / A5 / Letter / Legal / picture size), the picture placed like the
 * PDF does it, and the page number in Times New Roman Bold. Every change glides smoothly (paper, picture, number).
 */
@Composable
fun PaperPreview(
    bmp: ImageBitmap?, paper: String, pos: String, numSize: Int, number: Int,
    modifier: Modifier = Modifier,
    sigs: List<SigPlace> = emptyList(), sigBmps: Map<String, ImageBitmap> = emptyMap(),
) {
    val ctx = LocalContext.current
    val mark = MaterialTheme.colorScheme.primary
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            typeface = Fonts.typeface(ctx)
        }
    }
    val iw = bmp?.width ?: 3
    val ih = bmp?.height ?: 4
    val g = Paper.geom(iw, ih, paper)
    val text = number.toString()
    val tw = text.length * Paper.DIGIT_W * numSize
    val target = Paper.numXY(if (pos == "off") "bc" else pos, numSize.toFloat(), g.pw, g.ph, tw)
    val spec = tween<Float>(450, easing = FastOutSlowInEasing)
    val pw by animateFloatAlways(g.pw, spec, label = "pw")
    val ph by animateFloatAlways(g.ph, spec, label = "ph")
    val ix by animateFloatAlways(g.x, spec, label = "ix")
    val iy by animateFloatAlways(g.y, spec, label = "iy")
    val idw by animateFloatAlways(g.dw, spec, label = "idw")
    val idh by animateFloatAlways(g.dh, spec, label = "idh")
    val nx by animateFloatAlways(target.first, spec, label = "nx")
    val ny by animateFloatAlways(target.second, spec, label = "ny")
    val ns by animateFloatAlways(numSize.toFloat(), spec, label = "ns")
    val na by animateFloatAlways(if (pos == "off") 0f else 1f, tween(250), label = "na")

    Canvas(modifier.fillMaxSize()) {
        val pad = 10f
        val sc = min((this.size.width - 2 * pad) / pw, (this.size.height - 2 * pad) / ph)
        val left = (this.size.width - pw * sc) / 2f
        val top = (this.size.height - ph * sc) / 2f
        drawRect(Color(0x33000000), Offset(left + 4f, top + 7f), Size(pw * sc, ph * sc))
        drawRect(Color.White, Offset(left, top), Size(pw * sc, ph * sc))
        bmp?.let {
            drawImage(
                it,
                dstOffset = IntOffset((left + ix * sc).roundToInt(), (top + iy * sc).roundToInt()),
                dstSize = IntSize((idw * sc).roundToInt().coerceAtLeast(1), (idh * sc).roundToInt().coerceAtLeast(1)),
            )
        }
        if (sigs.isNotEmpty()) drawSigs(sigs, sigBmps, left + ix * sc, top + iy * sc, idw * sc, idh * sc)
        if (na > 0.01f) {
            paint.textSize = ns * sc
            paint.alpha = (na * 255f).roundToInt().coerceIn(0, 255)
            // a soft blue box behind the number: shows clearly WHERE the number goes
            val bw = text.length * Paper.DIGIT_W * ns * sc
            val bh = Paper.DIGIT_H * ns * sc
            val padBox = 5f + ns * sc * 0.18f
            val bx = left + nx * sc - padBox
            val by = top + ny * sc - bh - padBox
            drawRoundRect(mark.copy(alpha = 0.16f * na), Offset(bx, by), Size(bw + 2 * padBox, bh + 2 * padBox), CornerRadius(8f, 8f))
            drawRoundRect(mark.copy(alpha = 0.85f * na), Offset(bx, by), Size(bw + 2 * padBox, bh + 2 * padBox), CornerRadius(8f, 8f), style = Stroke(width = 2.5f))
            drawContext.canvas.nativeCanvas.drawText(text, left + nx * sc, top + ny * sc, paint)
        }
    }
}

/** A tiny page: the six places for the number. The chosen place shows a bold "1" in a coloured box; tap another place to move it. */
@Composable
fun NumberSpotPicker(pos: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val on = cs.primary
    val line = cs.outline
    val paint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE; typeface = Fonts.typeface(ctx); textAlign = android.graphics.Paint.Align.CENTER }
    }
    val spots = Paper.positions
    fun center(sp: String, w: Float, h: Float): Offset {
        val fx = when (sp[1]) { 'l' -> 0.2f; 'r' -> 0.8f; else -> 0.5f }
        val fy = if (sp[0] == 't') 0.12f else 0.88f
        return Offset(fx * w, fy * h)
    }
    // smooth movement of the chosen box
    val target = center(if (pos == "off") "bc" else pos, 1f, 1f)
    val px by animateFloatAlways(target.x, tween(220, easing = FastOutSlowInEasing), label = "px")
    val py by animateFloatAlways(target.y, tween(220, easing = FastOutSlowInEasing), label = "py")
    Canvas(
        modifier.size(84.dp, 112.dp).pointerInput(Unit) {
            detectTapGestures { off ->
                val w = this.size.width.toFloat(); val h = this.size.height.toFloat()
                var best = spots[0]; var bd = Float.MAX_VALUE
                for (sp in spots) {
                    val d = (center(sp, w, h) - off).getDistance()
                    if (d < bd) { bd = d; best = sp }
                }
                onPick(best)
            }
        },
    ) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(Color.White, Offset.Zero, Size(w, h), CornerRadius(10f, 10f))
        drawRoundRect(line, Offset.Zero, Size(w, h), CornerRadius(10f, 10f), style = Stroke(width = 2f))
        for (i in 0 until 6) {
            val y = h * (0.27f + i * 0.085f)
            drawLine(Color(0xFFD5D9E4), Offset(w * 0.2f, y), Offset(w * (if (i == 5) 0.55f else 0.8f), y), strokeWidth = 4f, cap = StrokeCap.Round)
        }
        for (sp in spots) {
            val c = center(sp, w, h)
            drawCircle(Color(0xFFB4BACB), w * 0.045f, c)          // the other places: small dots
        }
        val r = w * 0.13f
        val cx = px * w; val cy = py * h
        drawRoundRect(on, Offset(cx - r, cy - r), Size(2 * r, 2 * r), CornerRadius(r * 0.45f, r * 0.45f))
        paint.textSize = r * 1.7f
        drawContext.canvas.nativeCanvas.drawText("1", cx, cy + r * 0.58f, paint)
    }
}
