package app.docscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The page finder of Image Fixer's phone camera (the JavaScript in phone.py), written again in Kotlin.
 * It needs no OpenCV and no Python: it runs natively, on a small copy of the picture, so
 *  - the live outline in the camera appears and follows the paper quickly,
 *  - the auto crop after a photo is fast.
 * Steps: several colour / brightness masks -> paper-like blobs -> enclosing 4-gons -> the one whose 4 sides all have a real
 * colour step wins -> every side is moved onto the real edge (straight-line fit) -> exact corners.
 */
object PageFinder {
    class Result(val quad: List<Offset>, val found: Boolean)

    private class Pt(val x: Double, val y: Double)
    private class Pic(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray)
    private class Cand(val q: List<Pt>, val fill: Double, val frac: Double, val score: Double)
    private class Hit(val x: Double, val y: Double, val t: Double, val s: Double)
    private class Ln(val x: Double, val y: Double, val dx: Double, val dy: Double, val n: Int)

    // ------------------------------------------------------------------ small helpers
    private fun blurF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size)
        val o = FloatArray(a.size)
        val k = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var s = 0f
            val row = y * w
            for (i in -r..r) s += a[row + min(w - 1, max(0, i))]
            for (x in 0 until w) {
                t[row + x] = s / k
                s += a[row + min(w - 1, x + r + 1)] - a[row + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) s += t[min(h - 1, max(0, i)) * w + x]
            for (y in 0 until h) {
                o[y * w + x] = s / k
                s += t[min(h - 1, y + r + 1) * w + x] - t[max(0, y - r) * w + x]
            }
        }
        return o
    }

    private fun otsu(v: IntArray): Int {
        val n = v.size
        val hist = DoubleArray(256)
        for (x in v) hist[x]++
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i]
        var wB = 0.0; var sB = 0.0; var best = -1.0; var th = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = n - wB
            if (wF == 0.0) break
            sB += t * hist[t]
            val mB = sB / wB
            val mF = (sum - sB) / wF
            val q = wB * wF * (mB - mF) * (mB - mF)
            if (q > best) { best = q; th = t }
        }
        return th
    }

    /** The biggest 4-connected blob of the mask: (its pixels, how many). */
    private fun bigBlob(mask: BooleanArray, w: Int, h: Int): Pair<BooleanArray, Int>? {
        val total = w * h
        val seen = BooleanArray(total)
        val st = IntArray(total)
        var bestStart = -1
        var bestN = 0
        for (s in 0 until total) {
            if (!mask[s] || seen[s]) continue
            var sp = 0
            var n = 0
            st[sp++] = s; seen[s] = true
            while (sp > 0) {
                val p = st[--sp]
                n++
                val x = p % w
                val y = (p - x) / w
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; st[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; st[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; st[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = true; st[sp++] = p + w }
            }
            if (n > bestN) { bestN = n; bestStart = s }
        }
        if (bestStart < 0) return null
        val lab = BooleanArray(total)
        var sp = 0
        st[sp++] = bestStart; lab[bestStart] = true
        while (sp > 0) {
            val p = st[--sp]
            val x = p % w
            val y = (p - x) / w
            if (x > 0 && mask[p - 1] && !lab[p - 1]) { lab[p - 1] = true; st[sp++] = p - 1 }
            if (x < w - 1 && mask[p + 1] && !lab[p + 1]) { lab[p + 1] = true; st[sp++] = p + 1 }
            if (y > 0 && mask[p - w] && !lab[p - w]) { lab[p - w] = true; st[sp++] = p - w }
            if (y < h - 1 && mask[p + w] && !lab[p + w]) { lab[p + w] = true; st[sp++] = p + w }
        }
        return lab to bestN
    }

    private fun polyArea(q: List<Pt>): Double {
        var a = 0.0
        for (i in q.indices) { val p = q[i]; val r = q[(i + 1) % q.size]; a += p.x * r.y - r.x * p.y }
        return abs(a) / 2.0
    }

    private fun hullOf(pts0: List<Pt>): List<Pt> {
        val pts = pts0.sortedWith(compareBy<Pt>({ it.x }, { it.y }))
        fun cr(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lo = ArrayList<Pt>()
        for (p in pts) {
            while (lo.size >= 2 && cr(lo[lo.size - 2], lo[lo.size - 1], p) <= 0) lo.removeAt(lo.size - 1)
            lo.add(p)
        }
        val up = ArrayList<Pt>()
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (up.size >= 2 && cr(up[up.size - 2], up[up.size - 1], p) <= 0) up.removeAt(up.size - 1)
            up.add(p)
        }
        lo.removeAt(lo.size - 1); up.removeAt(up.size - 1)
        return lo + up
    }

    /** Intersection of line p0p1 with line p2p3 (t along p0p1, s along p2p3), or null when parallel. */
    private fun lineX(p0: Pt, p1: Pt, p2: Pt, p3: Pt): Hit? {
        val d1x = p1.x - p0.x; val d1y = p1.y - p0.y
        val d2x = p3.x - p2.x; val d2y = p3.y - p2.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-9) return null
        val t = ((p2.x - p0.x) * d2y - (p2.y - p0.y) * d2x) / den
        val s = ((p2.x - p0.x) * d1y - (p2.y - p0.y) * d1x) / den
        return Hit(p0.x + t * d1x, p0.y + t * d1y, t, s)
    }

    /** The smallest enclosing 4-gon of a convex outline: the hull edge whose removal adds the least area is dropped. */
    private fun quadFromHull(h: List<Pt>): List<Pt> {
        val p = ArrayList(h)
        while (p.size > 4) {
            val n = p.size
            var bi = -1
            var ba = 1e30
            var bx: Pt? = null
            for (i in 0 until n) {
                val p0 = p[(i - 1 + n) % n]; val p1 = p[i]; val p2 = p[(i + 1) % n]; val p3 = p[(i + 2) % n]
                val x = lineX(p0, p1, p3, p2) ?: continue
                if (x.t <= 1 || x.s <= 1) continue
                val a = abs((p1.x - x.x) * (p2.y - x.y) - (p1.y - x.y) * (p2.x - x.x)) / 2.0
                if (a < ba) { ba = a; bi = i; bx = Pt(x.x, x.y) }
            }
            if (bi < 0 || bx == null) {
                var m = 1e30
                var mi = 0
                for (i in 0 until n) {
                    val a = polyArea(listOf(p[(i - 1 + n) % n], p[i], p[(i + 1) % n]))
                    if (a < m) { m = a; mi = i }
                }
                p.removeAt(mi)
                continue
            }
            val j = (bi + 1) % n
            p[bi] = bx
            p.removeAt(j)
        }
        return p
    }

    /** Corner order: top-left, top-right, bottom-right, bottom-left. */
    private fun orderQuad(q: List<Pt>): List<Pt> {
        val s = q.map { it.x + it.y }
        val d = q.map { it.x - it.y }
        var tl = 0; var br = 0; var tr = 0; var bl = 0
        for (i in 1 until 4) {
            if (s[i] < s[tl]) tl = i
            if (s[i] > s[br]) br = i
            if (d[i] > d[tr]) tr = i
            if (d[i] < d[bl]) bl = i
        }
        if (setOf(tl, br, tr, bl).size < 4) {
            val cx = q.sumOf { it.x } / 4.0
            val cy = q.sumOf { it.y } / 4.0
            val o = q.sortedBy { atan2(it.y - cy, it.x - cx) }
            var k = 0
            for (i in 1 until 4) if (o[i].x + o[i].y < o[k].x + o[k].y) k = i
            return listOf(o[k], o[(k + 1) % 4], o[(k + 2) % 4], o[(k + 3) % 4])
        }
        return listOf(q[tl], q[tr], q[br], q[bl])
    }

    private fun smp(a: FloatArray, w: Int, h: Int, x0: Double, y0: Double): Double {
        val x = if (x0 < 0) 0.0 else if (x0 > w - 1.001) w - 1.001 else x0
        val y = if (y0 < 0) 0.0 else if (y0 > h - 1.001) h - 1.001 else y0
        val xi = x.toInt(); val yi = y.toInt()
        val fx = x - xi; val fy = y - yi
        val i = yi * w + xi
        return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + w] * (1 - fx) + a[i + w + 1] * fx) * fy
    }

    /** How strong the colour step is across the 4 sides of the outline (a printed frame, a hand, a red box have weak sides). */
    private fun sideContrast(chb: Array<FloatArray>, w: Int, h: Int, q: List<Pt>): Double {
        val cx = q.sumOf { it.x } / 4.0
        val cy = q.sumOf { it.y } / 4.0
        val off = max(2.5, 0.009 * max(w, h))
        val sides = ArrayList<Double>()
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y).let { if (it == 0.0) 1.0 else it }
            val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len
            var nx = uy; var ny = -ux
            if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            val k = 24
            val e = DoubleArray(k)
            for (j in 0 until k) {
                val s = 0.1 + 0.8 * (j + 0.5) / k
                val px = a.x + ux * s * len; val py = a.y + uy * s * len
                var best = 0.0
                for (sh in doubleArrayOf(-1.5, 0.0, 1.5)) {
                    var ee = 0.0
                    for (c in 0 until 3) {
                        val df = smp(chb[c], w, h, px + nx * (off + sh), py + ny * (off + sh)) -
                            smp(chb[c], w, h, px - nx * (off - sh), py - ny * (off - sh))
                        ee += df * df
                    }
                    best = max(best, sqrt(ee))
                }
                e[j] = best
            }
            e.sort()
            sides.add(e[e.size shr 1])
        }
        return 0.5 * (sides.sum() / 4.0) + 0.5 * (sides.minOrNull() ?: 0.0)
    }

    // ------------------------------------------------------------------ coarse finder
    private fun coarseCands(pic: Pic): List<Cand> {
        val w = pic.w; val h = pic.h; val n = w * h
        val lum = FloatArray(n) { 0.299f * pic.r[it] + 0.587f * pic.g[it] + 0.114f * pic.b[it] }
        val lb = blurF(blurF(lum, w, h, 2), w, h, 2)
        val u1 = IntArray(n) { lb[it].toInt().coerceIn(0, 255) }
        val chb = arrayOf(blurF(pic.r, w, h, 1), blurF(pic.g, w, h, 1), blurF(pic.b, w, h, 1))

        fun dist(cr: Float, cg: Float, cb: Float): IntArray {
            val d = FloatArray(n) {
                val dr = pic.r[it] - cr; val dg = pic.g[it] - cg; val db = pic.b[it] - cb
                sqrt(dr * dr + dg * dg + db * db)
            }
            val db = blurF(blurF(d, w, h, 2), w, h, 2)
            return IntArray(n) { min(255f, db[it] * 1.3f).toInt() }
        }

        // the table = the colour along the picture's border
        var br = 0.0; var bg = 0.0; var bb = 0.0; var bn = 0
        val m = max(2, (min(w, h) * 0.03).roundToInt())
        for (y in 0 until h) for (x in 0 until w) {
            if (x < m || y < m || x >= w - m || y >= h - m) {
                val i = y * w + x
                br += pic.r[i]; bg += pic.g[i]; bb += pic.b[i]; bn++
            }
        }
        val u2 = dist((br / bn).toFloat(), (bg / bn).toFloat(), (bb / bn).toFloat())

        // the paper's own colour = the middle of the picture (median of a small patch)
        val px0 = (w * 0.42).roundToInt(); val px1 = (w * 0.58).roundToInt()
        val py0 = (h * 0.42).roundToInt(); val py1 = (h * 0.58).roundToInt()
        val cnt = max(1, (px1 - px0) * (py1 - py0))
        val rr = FloatArray(cnt); val gg = FloatArray(cnt); val bl = FloatArray(cnt)
        var k = 0
        for (y in py0 until py1) for (x in px0 until px1) {
            if (k >= cnt) break
            val i = y * w + x
            rr[k] = pic.r[i]; gg[k] = pic.g[i]; bl[k] = pic.b[i]; k++
        }
        rr.sort(); gg.sort(); bl.sort()
        val u4 = dist(rr[cnt shr 1], gg[cnt shr 1], bl[cnt shr 1])

        val t1 = otsu(u1); val t2 = otsu(u2); val t4 = otsu(u4)
        val masks = listOf(
            BooleanArray(n) { u2[it] > t2 },
            BooleanArray(n) { u1[it] > t1 && u2[it] > t2 * 0.7 },
            BooleanArray(n) { u1[it] > t1 },
            BooleanArray(n) { u4[it] < t4 },
            BooleanArray(n) { u4[it] < t4 * 0.6 },
        )
        val cands = ArrayList<Cand>()
        for (mk in masks) {
            var a = blurF(FloatArray(n) { if (mk[it]) 1f else 0f }, w, h, 3)
            val c1 = BooleanArray(n) { a[it] > 0.4f }
            a = blurF(FloatArray(n) { if (c1[it]) 1f else 0f }, w, h, 3)
            val c2 = BooleanArray(n) { a[it] > 0.6f }
            val blob = bigBlob(c2, w, h) ?: continue
            val lab = blob.first
            val cnt2 = blob.second
            val frac = cnt2.toDouble() / n
            if (frac < 0.08 || frac > 0.985) continue
            val pts = ArrayList<Pt>()
            var cx = 0.0; var cy = 0.0
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                if (!lab[p]) continue
                cx += x; cy += y
                val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 || !lab[p - 1] || !lab[p + 1] || !lab[p - w] || !lab[p + w]
                if (edge) pts.add(Pt(x + 0.5, y + 0.5))
            }
            if (pts.size < 8) continue
            val hull = hullOf(pts)
            if (hull.size < 4) continue
            val qh = quadFromHull(hull)
            if (qh.size != 4) continue
            val q = orderQuad(qh)
            val area = max(1.0, polyArea(q))
            val fill = cnt2 / area
            cx /= cnt2; cy /= cnt2
            if (fill < 0.7 || fill > 1.25) continue
            val centre = 1 - 0.5 * hypot(cx - w / 2.0, cy - h / 2.0) / hypot(w / 2.0, h / 2.0)
            val con = sideContrast(chb, w, h, q)
            cands.add(Cand(q, fill, frac, con * sqrt(frac) * sqrt(centre) * min(1.0, fill)))
        }
        return cands
    }

    private fun coarseQuad(pic: Pic): Cand? = coarseCands(pic).maxByOrNull { it.score }

    // ------------------------------------------------------------------ straight-edge refinement
    private fun refineQuad(pic: Pic, q0: List<Pt>, rounds: Int): Pair<List<Pt>, Int> {
        val w = pic.w; val h = pic.h
        val ch = arrayOf(blurF(pic.r, w, h, 1), blurF(pic.g, w, h, 1), blurF(pic.b, w, h, 1))
        var q = q0
        var band = max(6.0, 0.03 * max(w, h))
        var moved = 0
        for (round in 0 until rounds) {
            val cx = q.sumOf { it.x } / 4.0
            val cy = q.sumOf { it.y } / 4.0
            val lines = ArrayList<Ln>()
            for (i in 0 until 4) {
                val a = q[i]; val b = q[(i + 1) % 4]
                val len = hypot(b.x - a.x, b.y - a.y).let { if (it == 0.0) 1.0 else it }
                val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len
                var nx = uy; var ny = -ux
                if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) < 0) { nx = -nx; ny = -ny }
                val kk = max(12, min(90, (len / 5).roundToInt()))
                val pp = ArrayList<Pt>(kk)
                val ee = DoubleArray(kk)
                for (k in 0 until kk) {
                    val s = 0.06 + 0.88 * (k + 0.5) / kk
                    val px = a.x + ux * s * len; val py = a.y + uy * s * len
                    val prof = ArrayList<Double>()
                    val ts = ArrayList<Double>()
                    var mxv = 0.0
                    var t = -band
                    while (t <= band) {
                        val xa = px + nx * (t + 1.5); val ya = py + ny * (t + 1.5)
                        val xb = px + nx * (t - 1.5); val yb = py + ny * (t - 1.5)
                        var e = 0.0
                        for (c in 0 until 3) { val df = smp(ch[c], w, h, xa, ya) - smp(ch[c], w, h, xb, yb); e += df * df }
                        e = sqrt(e)
                        prof.add(e); ts.add(t)
                        if (e > mxv) mxv = e
                        t += 0.5
                    }
                    // the OUTERMOST strong edge wins (a printed frame inside the page is not the page edge)
                    var bi = -1
                    for (j in prof.size - 2 downTo 1) {
                        if (prof[j] >= 0.55 * mxv && prof[j] >= prof[j - 1] && prof[j] >= prof[j + 1]) { bi = j; break }
                    }
                    if (bi < 0) {
                        var m0 = -1.0
                        for (j in prof.indices) if (prof[j] > m0) { m0 = prof[j]; bi = j }
                    }
                    val bt = ts[bi]
                    pp.add(Pt(px + nx * bt, py + ny * bt)); ee[k] = mxv
                }
                val sorted = ee.sortedArray()
                val med = sorted[sorted.size shr 1]
                var use = pp.filterIndexed { k, _ -> ee[k] >= max(5.0, 0.5 * med) }
                var ln: Ln? = null
                if (use.size >= max(6.0, kk * 0.35)) {
                    for (iter in 0 until 4) {
                        val mx2 = use.sumOf { p -> p.x } / use.size
                        val my2 = use.sumOf { p -> p.y } / use.size
                        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
                        for (p in use) { val dx = p.x - mx2; val dy = p.y - my2; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
                        val th = 0.5 * atan2(2 * sxy, sxx - syy)
                        val dx = cos(th); val dy = sin(th)
                        val res = DoubleArray(use.size) { abs((use[it].x - mx2) * (-dy) + (use[it].y - my2) * dx) }
                        val mr = res.sortedArray()[res.size shr 1]
                        ln = Ln(mx2, my2, dx, dy, use.size)
                        val lim = max(1.0, 2.5 * mr)
                        val keep = use.filterIndexed { k, _ -> res[k] <= lim }
                        if (keep.size < max(6.0, kk * 0.3) || keep.size == use.size) break
                        use = keep
                    }
                    if (ln != null && ln.n < max(6.0, kk * 0.3)) ln = null
                }
                lines.add(ln ?: Ln(a.x, a.y, ux, uy, 0))
            }
            val nq = ArrayList<Pt>()
            var ok = true
            for (i in 0 until 4) {
                val l1 = lines[(i + 3) % 4]; val l2 = lines[i]
                val x = lineX(Pt(l1.x, l1.y), Pt(l1.x + l1.dx, l1.y + l1.dy), Pt(l2.x, l2.y), Pt(l2.x + l2.dx, l2.y + l2.dy))
                if (x == null || hypot(x.x - q[i].x, x.y - q[i].y) > band * 2.2 + 0.02 * max(w, h)) { ok = false; break }
                nq.add(Pt(x.x, x.y))
            }
            if (!ok || polyArea(nq) < 0.6 * polyArea(q)) break
            q = nq; moved++
            band = max(2.5, band * 0.4)
        }
        return q to moved
    }

    /** fine = the picture for the exact corners, small = the same picture smaller for the coarse step (may be the same object). */
    private fun findPaper(fine: Pic, small: Pic, rounds: Int): Pair<List<Pt>, Boolean> {
        val best = coarseQuad(small)
        val W = fine.w.toDouble(); val H = fine.h.toDouble()
        if (best == null) {
            val mm = 0.04
            return listOf(Pt(mm * W, mm * H), Pt((1 - mm) * W, mm * H), Pt((1 - mm) * W, (1 - mm) * H), Pt(mm * W, (1 - mm) * H)) to false
        }
        val k = fine.w.toDouble() / small.w
        var q = best.q.map { Pt(it.x * k, it.y * k) }
        val found = best.fill > 0.82 && best.frac > 0.15
        try {
            val r = refineQuad(fine, q, rounds)
            if (r.second > 0) q = r.first
        } catch (_: Exception) { }
        q = orderQuad(q).map { Pt(it.x.coerceIn(0.0, W), it.y.coerceIn(0.0, H)) }
        return q to found
    }

    // ------------------------------------------------------------------ public
    /** One small camera frame (raw RGB bytes, w x h, upright) -> the page outline as fractions 0..1 (TL, TR, BR, BL), or null. */
    fun live(rgb: ByteArray, w: Int, h: Int): List<Offset>? {
        val n = w * h
        if (rgb.size < n * 3 || w < 8 || h < 8) return null
        val pic = Pic(
            w, h,
            FloatArray(n) { (rgb[it * 3].toInt() and 255).toFloat() },
            FloatArray(n) { (rgb[it * 3 + 1].toInt() and 255).toFloat() },
            FloatArray(n) { (rgb[it * 3 + 2].toInt() and 255).toFloat() },
        )
        val (q, found) = findPaper(pic, pic, 2)
        if (!found) return null
        return q.map { Offset((it.x / w).toFloat(), (it.y / h).toFloat()) }
    }

    private fun picOf(bm: Bitmap, side: Int): Pic {
        val s = min(1.0, side.toDouble() / max(bm.width, bm.height))
        val w = max(8, (bm.width * s).roundToInt())
        val h = max(8, (bm.height * s).roundToInt())
        val sb = if (w == bm.width && h == bm.height) bm else Bitmap.createScaledBitmap(bm, w, h, true)
        val px = IntArray(w * h)
        sb.getPixels(px, 0, w, 0, 0, w, h)
        if (sb !== bm) sb.recycle()
        return Pic(
            w, h,
            FloatArray(w * h) { ((px[it] shr 16) and 255).toFloat() },
            FloatArray(w * h) { ((px[it] shr 8) and 255).toFloat() },
            FloatArray(w * h) { (px[it] and 255).toFloat() },
        )
    }

    /** A photo file (upright working picture) -> (sure, outline in the photo's pixels: TL, TR, BR, BL). */
    fun detect(path: String): Pair<Boolean, List<Offset>> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val W = o.outWidth; val H = o.outHeight
        if (W <= 0 || H <= 0) throw IllegalStateException("unreadable picture")
        var sample = 1
        while (max(W, H) / (sample * 2) >= 1000) sample *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("unreadable picture")
        val fine = picOf(bm, 1000)
        val small = picOf(bm, 420)
        bm.recycle()
        val (q, found) = findPaper(fine, small, 3)
        val sx = W.toDouble() / fine.w
        val sy = H.toDouble() / fine.h
        var pts = q.map { Pt((it.x * sx).coerceIn(0.0, W.toDouble()), (it.y * sy).coerceIn(0.0, H.toDouble())) }
        // a hair inside the page edge (no sliver of table in the crop), like the PC program
        val cx = pts.sumOf { it.x } / 4.0
        val cy = pts.sumOf { it.y } / 4.0
        pts = pts.map { Pt(cx + (it.x - cx) * 0.995, cy + (it.y - cy) * 0.995) }
        val area = polyArea(pts)
        return (found && area >= 0.08 * W * H) to pts.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    }
}
