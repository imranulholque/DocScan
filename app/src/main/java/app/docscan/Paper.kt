package app.docscan

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
 * PDF does it, and the page number in Times New Roman. Every change glides smoothly (paper, picture, number).
 */
@Composable
fun PaperPreview(
    bmp: ImageBitmap?, paper: String, pos: String, numSize: Int, number: Int,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
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
    val pw by animateFloatAsState(g.pw, spec, label = "pw")
    val ph by animateFloatAsState(g.ph, spec, label = "ph")
    val ix by animateFloatAsState(g.x, spec, label = "ix")
    val iy by animateFloatAsState(g.y, spec, label = "iy")
    val idw by animateFloatAsState(g.dw, spec, label = "idw")
    val idh by animateFloatAsState(g.dh, spec, label = "idh")
    val nx by animateFloatAsState(target.first, spec, label = "nx")
    val ny by animateFloatAsState(target.second, spec, label = "ny")
    val ns by animateFloatAsState(numSize.toFloat(), spec, label = "ns")
    val na by animateFloatAsState(if (pos == "off") 0f else 1f, tween(250), label = "na")

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
        if (na > 0.01f) {
            paint.textSize = ns * sc
            paint.alpha = (na * 255f).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawText(text, left + nx * sc, top + ny * sc, paint)
        }
    }
}

/** A tiny paper: tap one of the six places for the page number (tap the chosen one again = no number). */
@Composable
fun NumberPosPicker(pos: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val line = cs.outline
    val on = cs.primary
    val spots = Paper.positions
    fun center(sp: String, w: Float, h: Float): Offset {
        val fx = when (sp[1]) { 'l' -> 0.22f; 'r' -> 0.78f; else -> 0.5f }
        val fy = if (sp[0] == 't') 0.13f else 0.87f
        val x = fx * w
        val y = fy * h
        return Offset(x, y)
    }
    Canvas(
        modifier.size(64.dp, 86.dp).pointerInput(Unit) {
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
        drawRoundRect(Color.White, Offset.Zero, Size(w, h), CornerRadius(8f, 8f))
        drawRoundRect(line, Offset.Zero, Size(w, h), CornerRadius(8f, 8f), style = Stroke(width = 2f))
        for (i in 0 until 5) {
            val y = h * (0.30f + i * 0.095f)
            drawLine(Color(0xFFD5D9E4), Offset(w * 0.18f, y), Offset(w * (if (i == 4) 0.5f else 0.82f), y), strokeWidth = 4f, cap = StrokeCap.Round)
        }
        for (sp in spots) {
            val c = center(sp, w, h)
            if (sp == pos) {
                drawCircle(on.copy(alpha = 0.25f), 17f, c)
                drawCircle(on, 10f, c)
            } else {
                drawCircle(Color(0xFFB4BACB), 6f, c, style = Stroke(width = 2.5f))
            }
        }
    }
}
