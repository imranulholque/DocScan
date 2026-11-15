"""Line based page finder: finds the 4 straight sides of a paper / book page / card and crosses them.

Why: when all 4 corners are in the picture, the page is a quadrilateral made of 4 long straight edges. Finding those straight
lines directly (instead of colour blobs) is fast (about 15-30 ms on a 480 px copy) and the corners are exact (sub-pixel line fits).

Steps
  1. line segments (LSD; Hough as the fallback) on the brightness picture and on the colour-saturation picture
  2. segments on the same straight line are merged into one long line (total least squares fit)
  3. every way of picking 2 + 2 lines (two roughly opposite sides, two roughly opposite sides at a right angle) is a candidate quad
  4. a candidate is scored by: how much of each side is really covered by an edge, the colour jump across each side,
     how even the inside is (paper), and - the main subject rule - the page must be IN THE MIDDLE of the picture
     (it must contain the picture centre, and a page near the middle beats an equally good one at the rim / a neighbour)
Returns (quad, confidence, sure) or None. The caller falls back to the colour based finder when this one is not sure.
"""
import math
import numpy as np
import cv2

WORK = 480                   # long side of the working copy
ANG_TOL = math.radians(3.5)  # segments closer than this in direction can be one line
OPP_TOL = math.radians(35)   # two opposite sides may differ this much (perspective)
PERP_MIN, PERP_MAX = math.radians(50), math.radians(130)
TOP_LINES = 10


# ------------------------------------------------------------------ segments
def _segments(gray, sat, work):
    out = []
    for img in (gray, sat):
        segs = None
        try:
            det = cv2.createLineSegmentDetector()
            r = det.detect(img)[0]
            if r is not None and len(r):
                segs = r.reshape(-1, 4).astype(np.float64)
        except Exception:
            segs = None
        if segs is None:
            try:
                v = float(np.median(img))
                e = cv2.Canny(img, max(10, .66 * v), max(30, 1.33 * v))
                ls = cv2.HoughLinesP(e, 1, np.pi / 180, int(.08 * work), minLineLength=int(.1 * work), maxLineGap=int(.02 * work))
                if ls is not None:
                    segs = ls.reshape(-1, 4).astype(np.float64)
            except Exception:
                segs = None
        if segs is not None and len(segs):
            out.append(segs)
    if not out:
        return np.zeros((0, 4))
    s = np.concatenate(out, 0)
    ln = np.hypot(s[:, 2] - s[:, 0], s[:, 3] - s[:, 1])
    return s[ln >= .06 * work]


class _Line:
    __slots__ = ("m", "d", "ang", "L", "segs", "iv")

    def __init__(self, seg, ln):
        self.segs = [(seg, ln)]
        self._fit()

    def _fit(self):
        pts = np.array([[s[0], s[1]] for s, _ in self.segs] + [[s[2], s[3]] for s, _ in self.segs])
        w = np.array([l for _, l in self.segs] * 2)
        w = w / w.sum()
        m = (pts * w[:, None]).sum(0)
        c = pts - m
        ev, evec = np.linalg.eigh((c * w[:, None]).T @ c)
        d = evec[:, 1]
        if d[0] < 0 or (abs(d[0]) < 1e-9 and d[1] < 0):
            d = -d
        self.m, self.d = m, d
        self.ang = math.atan2(d[1], d[0]) % math.pi
        self.L = float(sum(l for _, l in self.segs))

    def add(self, seg, ln):
        self.segs.append((seg, ln))
        self._fit()

    def dist(self, p):
        return abs((p[0] - self.m[0]) * -self.d[1] + (p[1] - self.m[1]) * self.d[0])

    def intervals(self, gap):
        iv = []
        for s, _ in self.segs:
            a = (s[0] - self.m[0]) * self.d[0] + (s[1] - self.m[1]) * self.d[1]
            b = (s[2] - self.m[0]) * self.d[0] + (s[3] - self.m[1]) * self.d[1]
            iv.append((min(a, b), max(a, b)))
        iv.sort()
        out = [list(iv[0])]
        for a, b in iv[1:]:
            if a <= out[-1][1] + gap:
                out[-1][1] = max(out[-1][1], b)
            else:
                out.append([a, b])
        self.iv = out
        return out


def _cluster(segs, work):
    tol = .010 * work
    lines = []
    ln = np.hypot(segs[:, 2] - segs[:, 0], segs[:, 3] - segs[:, 1])
    for i in np.argsort(-ln):
        s = segs[i]
        a = math.atan2(s[3] - s[1], s[2] - s[0]) % math.pi
        for c in lines:
            da = abs(a - c.ang)
            da = min(da, math.pi - da)
            if da > ANG_TOL:
                continue
            if max(c.dist((s[0], s[1])), c.dist((s[2], s[3]))) <= tol:
                c.add(s, float(ln[i]))
                break
        else:
            lines.append(_Line(s, float(ln[i])))
    return lines


# ------------------------------------------------------------------ geometry
def _isect(a, b):
    den = a.d[0] * b.d[1] - a.d[1] * b.d[0]
    if abs(den) < 1e-6:
        return None
    t = ((b.m[0] - a.m[0]) * b.d[1] - (b.m[1] - a.m[1]) * b.d[0]) / den
    return a.m + t * a.d


def _adiff(a, b):
    d = abs(a - b) % math.pi
    return min(d, math.pi - d)


def _convex(q):
    s = 0
    for i in range(4):
        a, b, c = q[i], q[(i + 1) % 4], q[(i + 2) % 4]
        cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        if abs(cr) < 1e-6:
            return False
        sg = 1 if cr > 0 else -1
        if s == 0:
            s = sg
        elif s != sg:
            return False
    return True


def _area(q):
    x, y = q[:, 0], q[:, 1]
    return abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2


def _cover(line, p0, p1):
    t0 = (p0[0] - line.m[0]) * line.d[0] + (p0[1] - line.m[1]) * line.d[1]
    t1 = (p1[0] - line.m[0]) * line.d[0] + (p1[1] - line.m[1]) * line.d[1]
    lo, hi = min(t0, t1), max(t0, t1)
    if hi - lo < 1e-6:
        return 0.0
    got = 0.0
    for a, b in line.iv:
        o = min(hi, b) - max(lo, a)
        if o > 0:
            got += o
    return min(1.0, got / (hi - lo))


def _side_stats(Pb, q, off, med):
    """Per side: (colour jump across the side, signed brightness inside-minus-outside, how far the colour just INSIDE the side is
    from the colour of the page interior). nan row when the side runs along the picture rim."""
    h, w = Pb.shape[:2]
    c = q.mean(0)
    t = (np.arange(24) + .5) / 24 * .8 + .1
    lw = np.array([.299, .587, .114])
    res = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        ln = float(np.hypot(*(b - a))) or 1.0
        u = (b - a) / ln
        n = np.array([u[1], -u[0]])
        if np.dot(n, (a + b) / 2 - c) < 0:
            n = -n
        p = a[None] + t[:, None] * (b - a)[None]
        po, pi_, pk = p + n * off, p - n * off, p - n * (1.7 * off)
        ok = (po[:, 0] > 1) & (po[:, 0] < w - 2) & (po[:, 1] > 1) & (po[:, 1] < h - 2)
        if ok.mean() < .6:
            res.append((float("nan"), 0.0, 0.0))
            continue

        def smp(pp):
            return Pb[np.clip(pp[:, 1].astype(int), 0, h - 1), np.clip(pp[:, 0].astype(int), 0, w - 1)]
        vo, vi, vk = smp(po), smp(pi_), smp(pk)
        jump = float(np.median(np.sqrt(((vo - vi) ** 2).sum(1))[ok]))
        sgn = float(np.median(((vi - vo) @ lw)[ok]))
        dev = float(np.median(np.linalg.norm(vk - med, axis=1)[ok]))
        res.append((jump, sgn, dev))
    return res


def _is_rim(q, i, w, h):
    a, b = q[i], q[(i + 1) % 4]
    t = np.linspace(.1, .9, 12)
    p = a[None] + t[:, None] * (b - a)[None]
    m = max(3.0, .02 * max(w, h))
    near = (p[:, 0] < m) | (p[:, 0] > w - 1 - m) | (p[:, 1] < m) | (p[:, 1] > h - 1 - m)
    return near.mean() > .6


# ------------------------------------------------------------------ main
def find_quad(rgb, work=WORK):
    """rgb: HxWx3 uint8. Returns (quad 4x2 float TL,TR,BR,BL in pixels of rgb, confidence 0..1, sure bool) or None."""
    H, W_ = rgb.shape[:2]
    s = work / float(max(H, W_))
    small = cv2.resize(rgb, (max(16, round(W_ * s)), max(16, round(H * s))), interpolation=cv2.INTER_AREA) if s < 1 else rgb
    s = small.shape[1] / float(W_)
    h, w = small.shape[:2]
    gray = cv2.cvtColor(cv2.GaussianBlur(small, (0, 0), 1.0), cv2.COLOR_RGB2GRAY)
    sat = cv2.cvtColor(cv2.GaussianBlur(small, (0, 0), 1.0), cv2.COLOR_RGB2HSV)[:, :, 1]
    segs = _segments(gray, sat, max(h, w))
    if len(segs) < 4:
        return None
    lines = _cluster(segs, max(h, w))
    gap = .05 * max(h, w)
    lines = [l for l in lines if l.L >= .09 * max(h, w)]
    lines.sort(key=lambda l: -l.L)
    lines = lines[:TOP_LINES * 3]
    if len(lines) < 4:
        return None
    Pb = cv2.GaussianBlur(small.astype(np.float32), (0, 0), 1.2)
    off = max(2.5, .012 * max(h, w))
    ranked = []
    for l in lines:
        l.intervals(gap)
        a, b = max(l.iv, key=lambda v: v[1] - v[0])
        pts = l.m[None] + (a + (b - a) * (np.arange(24) + .5) / 24)[:, None] * l.d[None]
        n = np.array([-l.d[1], l.d[0]])
        po, pi_ = pts + n * off, pts - n * off
        ok = (po[:, 0] > 1) & (po[:, 0] < w - 2) & (po[:, 1] > 1) & (po[:, 1] < h - 2) & (pi_[:, 0] > 1) & (pi_[:, 0] < w - 2) & (pi_[:, 1] > 1) & (pi_[:, 1] < h - 2)
        if ok.sum() < 4:
            continue
        vo = Pb[po[ok, 1].astype(int), po[ok, 0].astype(int)]
        vi = Pb[pi_[ok, 1].astype(int), pi_[ok, 0].astype(int)]
        con = float(np.percentile(np.sqrt(((vo - vi) ** 2).sum(1)), 25))   # a page side jumps ALONG ITS WHOLE LENGTH; a text baseline only here and there
        if con < 12.0:
            continue
        ranked.append((l.L * min(1.0, con / 24.0), l))
    ranked.sort(key=lambda r: -r[0])
    lines = [l for _, l in ranked[:TOP_LINES]]
    if len(lines) < 4:
        return None
    S = 96.0 / max(h, w)
    mw, mh = max(8, round(w * S)), max(8, round(h * S))
    Pm = cv2.resize(small, (mw, mh), interpolation=cv2.INTER_AREA).astype(np.float32)
    lab = cv2.cvtColor(cv2.resize(small, (mw, mh), interpolation=cv2.INTER_AREA), cv2.COLOR_RGB2LAB).astype(np.float32)
    ctr = (w / 2.0, h / 2.0)
    diag = math.hypot(w / 2.0, h / 2.0)

    # opposite-side pairs
    pairs = []
    for i in range(len(lines)):
        for j in range(i + 1, len(lines)):
            a, b = lines[i], lines[j]
            if _adiff(a.ang, b.ang) > OPP_TOL:
                continue
            n = np.array([-a.d[1], a.d[0]])
            if abs((b.m - a.m) @ n) < .12 * max(h, w):
                continue
            pairs.append((i, j))

    best = None
    seen = set()
    for pi_ in range(len(pairs)):
        for pj in range(pi_ + 1, len(pairs)):
            a, b = pairs[pi_]
            c, d = pairs[pj]
            if len({a, b, c, d}) < 4:
                continue
            la, lb, lc, ld = lines[a], lines[b], lines[c], lines[d]
            mean1 = math.atan2(la.d[1] + lb.d[1] * (1 if la.d @ lb.d > 0 else -1), la.d[0] + lb.d[0] * (1 if la.d @ lb.d > 0 else -1)) % math.pi
            mean2 = math.atan2(lc.d[1] + ld.d[1] * (1 if lc.d @ ld.d > 0 else -1), lc.d[0] + ld.d[0] * (1 if lc.d @ ld.d > 0 else -1)) % math.pi
            pd = _adiff(mean1, mean2)
            if pd < PERP_MIN or pd > PERP_MAX:
                continue
            # polygon order: c∩a, a∩d, d∩b, b∩c  -> side i lies on lines [a, d, b, c]
            P = [_isect(lc, la), _isect(la, ld), _isect(ld, lb), _isect(lb, lc)]
            if any(p is None for p in P):
                continue
            q = np.array(P, np.float64)
            if (q[:, 0] < -.06 * w).any() or (q[:, 0] > 1.06 * w).any() or (q[:, 1] < -.06 * h).any() or (q[:, 1] > 1.06 * h).any():
                continue
            if not _convex(q):
                continue
            ar = _area(q) / float(w * h)
            if ar < .10 or ar > .985:
                continue
            sl = [np.hypot(*(q[(k + 1) % 4] - q[k])) for k in range(4)]
            if min(sl[0], sl[2]) / max(sl[0], sl[2]) < .4 or min(sl[1], sl[3]) / max(sl[1], sl[3]) < .4:
                continue
            ang_ok = True
            for k in range(4):
                u, v = q[k - 1] - q[k], q[(k + 1) % 4] - q[k]
                ca = float(u @ v) / (np.linalg.norm(u) * np.linalg.norm(v) + 1e-9)
                if abs(ca) > .6:                      # more than ~53 degrees away from a right angle
                    ang_ok = False
                    break
            if not ang_ok:
                continue
            key = tuple(sorted((a, b, c, d)))
            if key in seen:
                continue
            seen.add(key)

            side_lines = [la, ld, lb, lc]

            # inside = paper-like (even colour)?
            m = np.zeros((mh, mw), np.uint8)
            cv2.fillPoly(m, [np.round(q * S).astype(np.int32)], 1)
            m = cv2.erode(m, np.ones((3, 3), np.uint8)).astype(bool)
            if m.sum() < 6:
                continue
            med = np.median(lab[m], 0)
            homog = float((np.linalg.norm(lab[m] - med, axis=1) < 28).mean())
            pix = Pm[m]
            lum_ = pix @ np.array([.299, .587, .114], np.float32)
            med_rgb = np.median(pix[lum_ >= np.median(lum_)], 0)          # the paper colour (ink and photos do not pull it)

            st = _side_stats(Pb, q, off, med_rgb)
            sq = []
            rim = 0
            sg = []
            for k in range(4):
                jump, sgn, dev = st[k]
                if _is_rim(q, k, w, h) or math.isnan(jump):
                    sq.append(.45)
                    rim += 1
                    continue
                cv_ = _cover(side_lines[k], q[k], q[(k + 1) % 4])
                inner = max(0.0, 1.0 - max(0.0, dev - 30.0) / 40.0)       # just inside the side the colour must be the page's colour
                sq.append(cv_ * min(1.0, jump / 16.0) * inner)
                sg.append((sgn, min(abs(sgn), 30.0)))
            sq = np.array(sq)
            # every side must separate page from background the same way round (page brighter than table on all 4, or darker on all 4)
            pol = 1.0
            tot = sum(wt for _, wt in sg)
            if tot > 1e-6:
                pol = abs(sum((1 if v > 0 else -1) * wt for v, wt in sg)) / tot

            cen_in = cv2.pointPolygonTest(q.astype(np.float32).reshape(-1, 1, 2), ctr, False) >= 0
            cq = q.mean(0)
            centre = 1.0 - min(1.0, math.hypot(cq[0] - ctr[0], cq[1] - ctr[1]) / diag)

            score = (.55 * float(sq.mean()) + .30 * float(sq.min()) + .10 * homog + .16 * math.sqrt(ar) + .10 * centre) * (.55 + .45 * pol)
            if not cen_in:
                score *= .45                          # main subject: the page must be where the camera is aimed
            if best is None or score > best[0]:
                best = (score, q, sq, rim, cen_in, ar)
    if best is None:
        return None
    score, q, sq, rim, cen_in, ar = best
    sure = bool(cen_in and rim == 0 and float(sq.min()) >= .5 and float(sq.mean()) >= .65 and score >= .62)
    q = _order(q) / s
    q[:, 0] = np.clip(q[:, 0], 0, W_ - 1)
    q[:, 1] = np.clip(q[:, 1], 0, H - 1)
    return q, float(min(1.0, score)), sure


def _order(q):
    q = np.asarray(q, np.float64).reshape(4, 2)
    sm = q.sum(1)
    df = q[:, 0] - q[:, 1]
    idx = [int(np.argmin(sm)), int(np.argmax(df)), int(np.argmax(sm)), int(np.argmin(df))]
    if len(set(idx)) < 4:
        c = q.mean(0)
        a = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0])
        o = q[np.argsort(a)]
        k = int(np.argmin(o.sum(1)))
        return np.roll(o, -k, 0)
    return q[idx]


def iou(q1, q2, size=96):
    """Overlap of two quads (0..1)."""
    q1, q2 = np.asarray(q1, np.float32), np.asarray(q2, np.float32)
    mx = max(q1.max(), q2.max(), 1.0)
    k = size / mx
    a = np.zeros((size + 2, size + 2), np.uint8)
    b = a.copy()
    cv2.fillPoly(a, [np.round(q1 * k).astype(np.int32)], 1)
    cv2.fillPoly(b, [np.round(q2 * k).astype(np.int32)], 1)
    u = float(((a > 0) | (b > 0)).sum())
    return float(((a > 0) & (b > 0)).sum()) / u if u else 0.0
