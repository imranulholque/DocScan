package app.docscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
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
    private class Cand(val q: List<Pt>, val fill: Double, val frac: Double, val score: Double, val con: Double)
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

    /** The [k] biggest 4-connected blobs of the mask, biggest first: (their pixels, how many). */
    private fun bigBlobs(mask: BooleanArray, w: Int, h: Int, k: Int): List<Pair<BooleanArray, Int>> {
        val total = w * h
        val seen = BooleanArray(total)
        val st = IntArray(total)
        val comps = ArrayList<IntArray>()   // (start pixel, size)
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
            comps.add(intArrayOf(s, n))
        }
        comps.sortByDescending { it[1] }
        val out = ArrayList<Pair<BooleanArray, Int>>()
        for (ci in 0 until min(k, comps.size)) {
            val lab = BooleanArray(total)
            var sp = 0
            val s0 = comps[ci][0]
            st[sp++] = s0; lab[s0] = true
            while (sp > 0) {
                val p = st[--sp]
                val x = p % w
                val y = (p - x) / w
                if (x > 0 && mask[p - 1] && !lab[p - 1]) { lab[p - 1] = true; st[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !lab[p + 1]) { lab[p + 1] = true; st[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && !lab[p - w]) { lab[p - w] = true; st[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && !lab[p + w]) { lab[p + w] = true; st[sp++] = p + w }
            }
            out.add(lab to comps[ci][1])
        }
        return out
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

    /** Fills the holes of a blob: a dark face / shirt / printed picture inside a paper is not "outside" the paper. Returns (filled blob, its pixel count). */
    private fun fillHoles(lab: BooleanArray, w: Int, h: Int): Pair<BooleanArray, Int> {
        val total = w * h
        val outside = BooleanArray(total)
        val st = IntArray(total)
        var sp = 0
        for (x in 0 until w) {
            val a = x; val b = (h - 1) * w + x
            if (!lab[a] && !outside[a]) { outside[a] = true; st[sp++] = a }
            if (!lab[b] && !outside[b]) { outside[b] = true; st[sp++] = b }
        }
        for (y in 0 until h) {
            val a = y * w; val b = y * w + w - 1
            if (!lab[a] && !outside[a]) { outside[a] = true; st[sp++] = a }
            if (!lab[b] && !outside[b]) { outside[b] = true; st[sp++] = b }
        }
        while (sp > 0) {
            val p = st[--sp]
            val x = p % w
            val y = (p - x) / w
            if (x > 0 && !lab[p - 1] && !outside[p - 1]) { outside[p - 1] = true; st[sp++] = p - 1 }
            if (x < w - 1 && !lab[p + 1] && !outside[p + 1]) { outside[p + 1] = true; st[sp++] = p + 1 }
            if (y > 0 && !lab[p - w] && !outside[p - w]) { outside[p - w] = true; st[sp++] = p - w }
            if (y < h - 1 && !lab[p + w] && !outside[p + w]) { outside[p + w] = true; st[sp++] = p + w }
        }
        var cnt = 0
        val filled = BooleanArray(total) { val f = !outside[it]; if (f) cnt++; f }
        return filled to cnt
    }

    /**
     * Mask for SMALL things (a passport photo, a card, a note far from the camera). The table colour is the median of the border strip and the
     * threshold comes from the table's own texture noise, so it does not depend on how big the object is (Otsu does: it splits the table texture
     * when the object is tiny).
     */
    private fun noiseMask(pic: Pic): BooleanArray? {
        val w = pic.w; val h = pic.h; val n = w * h
        val m = max(2, (min(w, h) * 0.04).roundToInt())
        if (m * 2 >= min(w, h)) return null
        val cnt = n - (w - 2 * m) * (h - 2 * m)
        if (cnt < 16) return null
        val rr = FloatArray(cnt); val gg = FloatArray(cnt); val bb = FloatArray(cnt)
        var k = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x < m || y < m || x >= w - m || y >= h - m) {
                val i = y * w + x
                rr[k] = pic.r[i]; gg[k] = pic.g[i]; bb[k] = pic.b[i]; k++
            }
        }
        rr.sort(); gg.sort(); bb.sort()
        val cr = rr[cnt shr 1]; val cg = gg[cnt shr 1]; val cb = bb[cnt shr 1]
        val d0 = FloatArray(n) {
            val dr = pic.r[it] - cr; val dg = pic.g[it] - cg; val db = pic.b[it] - cb
            sqrt(dr * dr + dg * dg + db * db)
        }
        val d = blurF(blurF(d0, w, h, 1), w, h, 1)
        val bv = FloatArray(cnt)
        k = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x < m || y < m || x >= w - m || y >= h - m) { bv[k++] = d[y * w + x] }
        }
        bv.sort()
        val p98 = bv[min(cnt - 1, (cnt * 0.98).toInt())]
        val thr = max(14f, 1.6f * p98)
        return BooleanArray(n) { d[it] > thr }
    }

    // ------------------------------------------------------------------ learned ranking
    /**
     * Candidate ranking learned from the collected crops (learned_data.json: 54 pages, their final corrected corners).
     * A listwise softmax model: for every picture the candidate whose outline matched the corrected crop best should score highest.
     * Features: ln(side contrast + 1), ln(area fraction), ln(centredness), ln(fill), corners touching the picture border,
     * worst corner squareness (max |cos|), blob rank (0 = biggest), ln(inside / outside colour distance + 1).
     * Leave-one-out on the collected pages: mean overlap 0.777 -> 0.82 (see tools/ in the repository).
     */
    private val RANK_W = doubleArrayOf(0.942, 1.911, 0.563, 0.133, -0.466, -0.608, 0.022, -0.421)

    private fun learnedScore(q: List<Pt>, w: Int, h: Int, con: Double, frac: Double, centre: Double, fill: Double, blobRank: Int, chd: Double): Double {
        var touch = 0
        for (p in q) if (p.x < 2 || p.y < 2 || p.x > w - 3 || p.y > h - 3) touch++
        var orth = 0.0
        for (i in 0 until 4) {
            val o = q[i]; val a = q[(i + 3) % 4]; val b = q[(i + 1) % 4]
            val ax = a.x - o.x; val ay = a.y - o.y; val bx = b.x - o.x; val by = b.y - o.y
            val c = abs(ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by) + 1e-9)
            if (c > orth) orth = c
        }
        return RANK_W[0] * ln(con + 1.0) + RANK_W[1] * ln(frac) + RANK_W[2] * ln(centre) +
            RANK_W[3] * ln(min(1.0, fill) + 1e-3) + RANK_W[4] * touch + RANK_W[5] * orth +
            RANK_W[6] * blobRank + RANK_W[7] * ln(chd + 1.0)
    }

    /** Colour distance between the mean colour inside the 4-gon and the mean colour outside it (a real page differs from its table). */
    private fun insideOutsideColour(pic: Pic, q: List<Pt>, totR: Double, totG: Double, totB: Double): Double {
        val w = pic.w; val h = pic.h
        val x0 = max(0, floor(q.minOf { it.x }).toInt()); val x1 = min(w - 1, ceil(q.maxOf { it.x }).toInt())
        val y0 = max(0, floor(q.minOf { it.y }).toInt()); val y1 = min(h - 1, ceil(q.maxOf { it.y }).toInt())
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        for (y in y0..y1) for (x in x0..x1) {
            var pos = 0; var neg = 0
            for (i in 0 until 4) {
                val a = q[i]; val b = q[(i + 1) % 4]
                val cr = (b.x - a.x) * (y + 0.5 - a.y) - (b.y - a.y) * (x + 0.5 - a.x)
                if (cr >= 0) pos++ else neg++
            }
            if (pos == 4 || neg == 4) {
                val i = y * w + x
                sr += pic.r[i]; sg += pic.g[i]; sb += pic.b[i]; cnt++
            }
        }
        val total = w * h
        if (cnt == 0 || cnt >= total) return 0.0
        val out = (total - cnt).toDouble()
        val dr = sr / cnt - (totR - sr) / out
        val dg = sg / cnt - (totG - sg) / out
        val db = sb / cnt - (totB - sb) / out
        return sqrt(dr * dr + dg * dg + db * db)
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
        val masks = ArrayList<Pair<BooleanArray, Int>>()   // (mask, blur radius used to clean it)
        masks.add(BooleanArray(n) { u2[it] > t2 } to 3)
        masks.add(BooleanArray(n) { u1[it] > t1 && u2[it] > t2 * 0.7 } to 3)
        masks.add(BooleanArray(n) { u1[it] > t1 } to 3)
        masks.add(BooleanArray(n) { u4[it] < t4 } to 3)
        masks.add(BooleanArray(n) { u4[it] < t4 * 0.6 } to 3)
        // learned from the collected crops: wider / tighter variants find ID cards, passport photos and screenshots the first five miss
        masks.add(BooleanArray(n) { u2[it] > t2 * 0.5 } to 3)
        masks.add(BooleanArray(n) { u2[it] > t2 * 1.4 } to 3)
        masks.add(BooleanArray(n) { u4[it] < t4 * 1.4 } to 3)
        noiseMask(pic)?.let { masks.add(it to 1) }          // small objects: light cleaning so they survive
        var totR = 0.0; var totG = 0.0; var totB = 0.0
        for (i in 0 until n) { totR += pic.r[i]; totG += pic.g[i]; totB += pic.b[i] }
        val cands = ArrayList<Cand>()
        for ((mk, rad) in masks) {
            var a = blurF(FloatArray(n) { if (mk[it]) 1f else 0f }, w, h, rad)
            val c1 = BooleanArray(n) { a[it] > 0.4f }
            a = blurF(FloatArray(n) { if (c1[it]) 1f else 0f }, w, h, rad)
            val c2 = BooleanArray(n) { a[it] > 0.6f }
            // the two biggest blobs: the page is not always the biggest one (a hand, a table patch)
            val blobs = bigBlobs(c2, w, h, 2)
            for ((bi, blob) in blobs.withIndex()) {
                val filledBlob = fillHoles(blob.first, w, h)
                val lab = filledBlob.first
                val cnt2 = filledBlob.second
                val frac = cnt2.toDouble() / n
                if (cnt2 < 30 || frac < 0.0015 || frac > 0.985) continue
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
                val chd = insideOutsideColour(pic, q, totR, totG, totB)
                cands.add(Cand(q, fill, frac, learnedScore(q, w, h, con, frac, centre, fill, bi, chd), con))
            }
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
        var minSide = 1e30
        for (i in 0 until 4) minSide = min(minSide, hypot(q0[(i + 1) % 4].x - q0[i].x, q0[(i + 1) % 4].y - q0[i].y))
        band = max(2.5, min(band, 0.2 * minSide))   // a small object must not search for its edge 30 px away, into the table
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
        val found = best.fill > 0.82 && (best.frac > 0.15 || best.con > 25.0)
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
        val small = picOf(bm, 560)
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
        return (found && area >= 0.0015 * W * H) to pts.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    }
}
