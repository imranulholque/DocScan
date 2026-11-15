"""Fast document finder (OpenCV, vectorised). Candidates -> features -> learned ranker -> edge snapping."""
import math, numpy as np, cv2

S_WORK = 420

def _order(q):
    q = np.asarray(q, np.float32).reshape(4, 2)
    s = q.sum(1); d = q[:, 0] - q[:, 1]
    idx = [int(np.argmin(s)), int(np.argmax(d)), int(np.argmax(s)), int(np.argmin(d))]
    if len(set(idx)) < 4:
        c = q.mean(0); a = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]); o = q[np.argsort(a)]
        k = int(np.argmin(o.sum(1))); return np.roll(o, -k, 0)
    return q[idx]

def _area(q):
    x = q[:, 0]; y = q[:, 1]
    return abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2

def _line_x(p0, p1, p2, p3):
    d1 = p1 - p0; d2 = p3 - p2; den = d1[0] * d2[1] - d1[1] * d2[0]
    if abs(den) < 1e-9: return None
    t = ((p2[0] - p0[0]) * d2[1] - (p2[1] - p0[1]) * d2[0]) / den
    s = ((p2[0] - p0[0]) * d1[1] - (p2[1] - p0[1]) * d1[0]) / den
    return p0 + t * d1, t, s

def _quad_from_hull(h):
    """Convex hull -> 4 corners by repeatedly replacing an edge by the intersection of its neighbours (smallest area added)."""
    p = [np.asarray(x, np.float64) for x in h]
    while len(p) > 4:
        n = len(p); bi = -1; ba = 1e30; bx = None
        for i in range(n):
            p0, p1, p2, p3 = p[(i - 1) % n], p[i], p[(i + 1) % n], p[(i + 2) % n]
            r = _line_x(p0, p1, p3, p2)
            if r is None or r[1] <= 1 or r[2] <= 1: continue
            x = r[0]; a = abs((p1[0] - x[0]) * (p2[1] - x[1]) - (p1[1] - x[1]) * (p2[0] - x[0])) / 2
            if a < ba: ba, bi, bx = a, i, x
        if bi < 0:
            tri = [abs((p[(i + 1) % n][0] - p[i - 1][0]) * (p[i][1] - p[i - 1][1]) - (p[i][0] - p[i - 1][0]) * (p[(i + 1) % n][1] - p[i - 1][1])) for i in range(n)]
            p.pop(int(np.argmin(tri))); continue
        j = (bi + 1) % n; p[bi] = bx; p.pop(j)
    return np.array(p, np.float32)

def _blob_quad(mask):
    """Biggest blob of a bool mask (holes filled) -> (quad, blob area) or None."""
    m = mask.astype(np.uint8)
    n, L, st, _ = cv2.connectedComponentsWithStats(m, connectivity=4)
    if n <= 1: return None
    out = []
    for ci in (np.argsort(-st[1:, cv2.CC_STAT_AREA])[:2] + 1):
        blob = (L == ci).astype(np.uint8)
        cs, _ = cv2.findContours(blob, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not cs: continue
        c = max(cs, key=cv2.contourArea)
        area = cv2.contourArea(c)
        if area < 30: continue
        hull = cv2.convexHull(c)[:, 0, :].astype(np.float32)
        if len(hull) < 4: continue
        q = _quad_from_hull(hull)
        if len(q) != 4: continue
        out.append((_order(q), area))
    return out

def _otsu(u8):
    return cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[0]

def generate(small, lite=False):
    """small = RGB uint8 (long side ~S_WORK). Returns a list of (name, quad)."""
    h, w = small.shape[:2]
    P = small.astype(np.float32)
    blur = cv2.GaussianBlur(P, (0, 0), 1.6)
    lum = blur @ np.array([.299, .587, .114], np.float32)
    m = max(2, round(min(h, w) * .03))
    bm = np.zeros((h, w), bool); bm[:m] = 1; bm[-m:] = 1; bm[:, :m] = 1; bm[:, -m:] = 1
    cb = np.median(blur[bm], 0)
    y0, y1, x0, x1 = round(h * .4), round(h * .6), round(w * .4), round(w * .6)
    cc = np.median(blur[y0:y1, x0:x1].reshape(-1, 3), 0)
    def dist(c): return np.minimum(255, np.sqrt(((blur - c) ** 2).sum(2)) * 1.3).astype(np.uint8)
    u2 = dist(cb); u4 = dist(cc); u1 = np.clip(lum, 0, 255).astype(np.uint8)
    t1, t2, t4 = _otsu(u1), _otsu(u2), _otsu(u4)
    masks = [('u2', u2 > t2), ('u2lo', u2 > t2 * .5), ('u2hi', u2 > t2 * 1.4),
             ('u1', u1 > t1), ('u1d', u1 < t1), ('u1u2', (u1 > t1) & (u2 > t2 * .7)),
             ('u4', u4 < t4), ('u4s', u4 < t4 * .6), ('u4m', u4 < t4 * 1.4)]
    # lightness relative to the picture: bright paper
    for pc in (45, 60):
        masks.append(('br%d' % pc, u1 > np.percentile(u1, pc)))

    if not lite:
      pass
    # k-means colour clusters (small copy): each cluster, and the cluster most present in the middle
    sm = cv2.resize(small, (max(8, round(w * 96 / max(w, h))), max(8, round(h * 96 / max(w, h)))), interpolation=cv2.INTER_AREA)
    lab = cv2.cvtColor(sm, cv2.COLOR_RGB2LAB).reshape(-1, 3).astype(np.float32)
    for K in ((2, 3) if not lite else ()):
        cv2.setRNGSeed(7)
        _, lb, _c = cv2.kmeans(lab, K, None, (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 10, 1.0), 1, cv2.KMEANS_PP_CENTERS)
        L = cv2.resize(lb.reshape(sm.shape[:2]).astype(np.uint8), (w, h), interpolation=cv2.INTER_NEAREST)
        for c in range(K):
            masks.append(('km%d_%d' % (K, c), L == c))
    # grab-cut from a frame (paper = what is not the border)
    gs = 160.0 / max(w, h); gw, gh = max(16, round(w * gs)), max(16, round(h * gs))
    gi = cv2.resize(small, (gw, gh), interpolation=cv2.INTER_AREA)
    try:
        if lite: raise RuntimeError('lite')
        gm = np.zeros((gh, gw), np.uint8)
        rect = (max(1, int(gw * .04)), max(1, int(gh * .04)), int(gw * .92), int(gh * .92))
        bg = np.zeros((1, 65), np.float64); fg = np.zeros((1, 65), np.float64)
        cv2.setRNGSeed(3)
        cv2.grabCut(gi, gm, rect, bg, fg, 3, cv2.GC_INIT_WITH_RECT)
        masks.append(('gc', cv2.resize(((gm == 1) | (gm == 3)).astype(np.uint8), (w, h), interpolation=cv2.INTER_NEAREST) > 0))
    except Exception:
        pass
    # edge blobs
    g = cv2.Canny(cv2.GaussianBlur(small, (0, 0), 1.2), 30, 90)
    g = cv2.dilate(g, np.ones((3, 3), np.uint8)); g = cv2.morphologyEx(g, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    cands = []
    k5 = np.ones((5, 5), np.uint8)
    for name, mk in masks:
        mk = cv2.morphologyEx(mk.astype(np.uint8), cv2.MORPH_OPEN, k5)
        mk = cv2.morphologyEx(mk, cv2.MORPH_CLOSE, k5)
        # fill holes
        inv = (1 - mk).astype(np.uint8); n, L = cv2.connectedComponents(inv, connectivity=4)
        bord = set(np.unique(np.concatenate([L[0], L[-1], L[:, 0], L[:, -1]]))) - {0}
        filled = ~(np.isin(L, list(bord)) & (inv > 0))
        r = _blob_quad(filled)
        for qd, ar in (r or []):
            frac = ar / (w * h)
            if .002 < frac < .985: cands.append((name, qd))

    cs_, _ = cv2.findContours(g, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    for c in sorted(cs_, key=cv2.contourArea, reverse=True)[:6]:
        if cv2.contourArea(c) < .04 * w * h: break
        hull = cv2.convexHull(c)
        ap = cv2.approxPolyDP(hull, .03 * cv2.arcLength(hull, True), True)
        if len(ap) == 4: cands.append(('edgepoly', _order(ap[:, 0, :])))
        else:
            q = _quad_from_hull(hull[:, 0, :].astype(np.float32))
            if len(q) == 4: cands.append(('edgehull', _order(q)))
    # whole frame variants
    for ins, nm in ((0.0, 'full0'), (.02, 'full2'), (.05, 'full5')):
        a, b = ins * w, ins * h
        cands.append((nm, np.array([[a, b], [w - 1 - a, b], [w - 1 - a, h - 1 - b], [a, h - 1 - b]], np.float32)))
    return cands

# ---------------------------------------------------------------- features
def _bilin(img, xs, ys):
    h, w = img.shape[:2]
    xs = np.clip(xs, 0, w - 1.001); ys = np.clip(ys, 0, h - 1.001)
    x0 = xs.astype(np.int32); y0 = ys.astype(np.int32); fx = (xs - x0)[..., None]; fy = (ys - y0)[..., None]
    a = img[y0, x0]; b = img[y0, x0 + 1]; c = img[y0 + 1, x0]; d = img[y0 + 1, x0 + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy

def side_contrast(Pb, q, off=None, k=20):
    """Per side: median colour jump across the side (outside - inside). Sides that run along the picture rim return nan."""
    h, w = Pb.shape[:2]; off = off or max(2.5, .012 * max(h, w))
    c = q.mean(0); res = []
    t = (np.arange(k) + .5) / k * .8 + .1
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]; ln = float(np.hypot(*(b - a))) or 1.0
        u = (b - a) / ln; n = np.array([u[1], -u[0]])
        if np.dot(n, (a + b) / 2 - c) < 0: n = -n
        p = a[None] + t[:, None] * (b - a)[None]
        po = p + n * off; pi = p - n * off
        ins = ((po[:, 0] > 1) & (po[:, 0] < w - 2) & (po[:, 1] > 1) & (po[:, 1] < h - 2))
        if ins.mean() < .5: res.append(np.nan); continue
        d = np.sqrt(((_bilin(Pb, po[:, 0], po[:, 1]) - _bilin(Pb, pi[:, 0], pi[:, 1])) ** 2).sum(1))
        res.append(float(np.median(d[ins])))
    return np.array(res)

def features(small, Pb, lumn, cands):
    h, w = small.shape[:2]; n = len(cands)
    S = 64.0 / max(w, h); mw, mh = max(8, round(w * S)), max(8, round(h * S))
    masks = []
    for _, q in cands:
        m = np.zeros((mh, mw), np.uint8); cv2.fillPoly(m, [np.round(q * S).astype(np.int32)], 1); masks.append(m.astype(bool))
    M = np.stack(masks).reshape(n, -1).astype(np.float32)
    inter = M @ M.T; sz = M.sum(1); uni = sz[:, None] + sz[None] - inter
    IoU = inter / np.maximum(uni, 1)
    Pm = cv2.resize(small, (mw, mh), interpolation=cv2.INTER_AREA).astype(np.float32)
    Lm = Pm @ np.array([.299, .587, .114], np.float32)
    gstd = float(Lm.std()) + 1e-3
    F = []
    ring_k = np.ones((7, 7), np.uint8)
    for i, (name, q) in enumerate(cands):
        area = _area(q); frac = area / (w * h)
        cen = q.mean(0); centre = 1 - .5 * math.hypot(cen[0] - w / 2, cen[1] - h / 2) / math.hypot(w / 2, h / 2)
        sc = side_contrast(Pb, q)
        valid = ~np.isnan(sc); nb = 4 - int(valid.sum())
        con_mean = float(np.nanmean(sc)) if valid.any() else 0.0
        con_min = float(np.nanmin(sc)) if valid.any() else 0.0
        m = masks[i]; mm = m.astype(np.uint8)
        ring = cv2.dilate(mm, ring_k, iterations=2).astype(bool) & ~m
        if m.sum() < 4: F.append(None); continue
        ci = Pm[m].mean(0); co = Pm[ring].mean(0) if ring.sum() > 3 else ci
        chd = float(np.linalg.norm(ci - co))
        std_in = float(Lm[m].std()) / gstd
        outside = ~cv2.dilate(mm, ring_k, iterations=2).astype(bool)
        lumd = float(Lm[m].mean() - (Lm[outside].mean() if outside.sum() > 3 else Lm[m].mean()))
        sat = float((Pm[m].max(1) - Pm[m].min(1)).mean() / 255)
        sides = [float(np.hypot(*(q[(j + 1) % 4] - q[j]))) for j in range(4)]
        asp = min(sides[0] + sides[2], sides[1] + sides[3]) / (max(sides[0] + sides[2], sides[1] + sides[3]) + 1e-6)
        opp = min(sides[0], sides[2]) / (max(sides[0], sides[2]) + 1e-6) * min(sides[1], sides[3]) / (max(sides[1], sides[3]) + 1e-6)
        cs = 0.0
        for j in range(4):
            a = q[j - 1] - q[j]; b = q[(j + 1) % 4] - q[j]
            cs = max(cs, abs(float(np.dot(a, b))) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))
        touch = int(sum(1 for x, y in q if x < 2 or y < 2 or x > w - 3 or y > h - 3))
        agree = float((IoU[i] > .8).sum() - 1)
        agreeW = float(IoU[i].sum() - 1) / max(1, n - 1)
        F.append([math.log(frac + 1e-3), math.log(max(frac, 1e-3)) ** 2 / 10, centre, math.log(1 + con_mean), math.log(1 + con_min),
                  nb, math.log(1 + chd), std_in, lumd / 100, sat, asp, opp, cs, touch,
                  math.log1p(agree), agreeW * 5, 1.0 if name.startswith('full') else 0.0, 1.0 if name == 'gc' else 0.0,
                  1.0 if name.startswith('edge') else 0.0, 1.0 if name.startswith('km') else 0.0,
                  1.0 if name in ('u2', 'u2lo', 'u2hi') else 0.0, 1.0 if frac > .9 else 0.0])
    return F

FEATURE_N = 22

def prepare(small, lite=False):
    Pb = cv2.GaussianBlur(small.astype(np.float32), (0, 0), 1.2)
    cands = generate(small, lite)
    F = features(small, Pb, None, cands)
    keep = [i for i, f in enumerate(F) if f is not None]
    return [cands[i] for i in keep], np.array([F[i] for i in keep], np.float32), Pb

# ---------------------------------------------------------------- edge snapping
def refine(mid, q, rounds=2, band_frac=.03, k=40):
    """Move each side of quad q (pixels of `mid`) onto the strongest colour edge nearby, fit a straight line through the hits
    (outliers dropped), cross the 4 lines. Sides that run along the picture rim stay where they are."""
    h, w = mid.shape[:2]
    Pb = cv2.GaussianBlur(mid.astype(np.float32), (0, 0), 1.0)
    q = np.asarray(q, np.float64).copy()
    band = max(4.0, band_frac * max(h, w))
    ts = np.arange(-band, band + .01, .5)
    for _ in range(rounds):
        c = q.mean(0); lines = []
        for i in range(4):
            a, b = q[i], q[(i + 1) % 4]; ln = float(np.hypot(*(b - a))) or 1.0
            u = (b - a) / ln; n = np.array([u[1], -u[0]])
            if np.dot(n, (a + b) / 2 - c) < 0: n = -n
            t = np.linspace(.08, .92, k)
            p = a[None] + t[:, None] * (b - a)[None]
            if ((p[:, 0] < 3) | (p[:, 0] > w - 4) | (p[:, 1] < 3) | (p[:, 1] > h - 4)).mean() > .5 and \
               ((p[:, 0] < 6) | (p[:, 0] > w - 7) | (p[:, 1] < 6) | (p[:, 1] > h - 7)).mean() > .7:
                lines.append(None); continue
            G = p[:, None, :] + (ts[None, :, None]) * n[None, None, :]
            o = G + 1.5 * n; i_ = G - 1.5 * n
            vo = _bilin(Pb, o[..., 0], o[..., 1]); vi = _bilin(Pb, i_[..., 0], i_[..., 1])
            e = np.sqrt(((vo - vi) ** 2).sum(-1))                       # k x m
            mx = e.max(1); bi = np.where(e >= .55 * mx[:, None], e, 0).argmax(1)
            # outermost strong local maximum
            pk = (e >= .55 * mx[:, None]) & (np.pad(e, ((0, 0), (1, 1)))[:, :-2] <= e) & (np.pad(e, ((0, 0), (1, 1)))[:, 2:] <= e)
            has = pk.any(1); last = e.shape[1] - 1 - np.argmax(pk[:, ::-1], 1)
            bi = np.where(has, last, e.argmax(1))
            hits = p + ts[bi][:, None] * n[None]
            med = np.median(mx); use = mx >= max(4.0, .5 * med)
            pts = hits[use]
            if len(pts) < max(6, k * .35): lines.append(None); continue
            for _it in range(4):
                m_ = pts.mean(0); cov = np.cov((pts - m_).T)
                ev, evec = np.linalg.eigh(cov); d = evec[:, 1]
                res = np.abs((pts - m_) @ np.array([-d[1], d[0]]))
                lim = max(1.0, 2.5 * np.median(res)); keep = res <= lim
                if keep.sum() < max(6, k * .3) or keep.all(): break
                pts = pts[keep]
            lines.append((m_, d))
        nq = q.copy(); ok = True
        for i in range(4):
            l1, l2 = lines[(i + 3) % 4], lines[i]
            if l1 is None or l2 is None:
                continue
            r = _line_x(l1[0], l1[0] + l1[1], l2[0], l2[0] + l2[1])
            if r is None or np.hypot(*(r[0] - q[i])) > 2.2 * band + .02 * max(h, w): ok = False; break
            nq[i] = r[0]
        if not ok or _area(nq) < .6 * _area(q): break
        # a side without a line: keep the corners shared with it only when both neighbours moved - otherwise leave
        q = nq; band = max(2.5, band * .45); ts = np.arange(-band, band + .01, .5)
    return q


# ---------------------------------------------------------------- learned ranker (trained on the 54 user-corrected crops)
WEIGHTS = {"mu": [-1.70653, 0.606, 0.84766, 3.83647, 3.14164, 1.32616, 3.93214, 0.79198, 0.13511, 0.08114, 0.62315, 0.67361, 0.32632, 1.8843, 1.23156, 1.41201, 0.09419, 0.03023, 0.0686, 0.26221, 0.15988, 0.15581], "sd": [1.70041, 0.89254, 0.14318, 1.47204, 1.48546, 1.26722, 1.37252, 0.39406, 0.56407, 0.07879, 0.22781, 0.2898, 0.31222, 1.55742, 1.05483, 1.03383, 0.29209, 0.17123, 0.25278, 0.43984, 0.3665, 0.36268], "w": [0.56997, -0.93523, 1.31744, 0.0444, 0.33989, -0.37775, 0.2758, 0.24891, 0.55681, -0.5401, 0.07904, 0.47894, -1.2655, -0.38113, -0.15763, 0.36396, -0.59726, 0.06733, 0.01046, -0.10649, -0.04839, -0.20599]}
SURE_AGREE = 3          # at least this many other outlines say the same thing -> "sure"
INSET = 0.0

def find_page(rgb, lite=False, work=None, mid_side=800):
    """rgb: HxWx3 uint8 (any size). Returns (quad 4x2 float TL,TR,BR,BL in pixels of rgb, sure: bool)."""
    H, W_ = rgb.shape[:2]
    work = work or (260 if lite else S_WORK)
    s = work / float(max(H, W_))
    small = cv2.resize(rgb, (max(16, round(W_ * s)), max(16, round(H * s))), interpolation=cv2.INTER_AREA) if s < 1 else rgb
    s = small.shape[1] / float(W_)
    cands, F, Pb = prepare(small, lite)
    if not cands: return None, False
    mu = np.array(WEIGHTS['mu'], np.float32); sd = np.array(WEIGHTS['sd'], np.float32); w = np.array(WEIGHTS['w'], np.float32)
    sc = ((F - mu) / sd) @ w
    k = int(np.argmax(sc)); q = cands[k][1].astype(np.float64)
    sure = F[k][14] >= math.log1p(SURE_AGREE) and F[k][4] >= math.log1p(2.0)
    if not lite:
        ms = mid_side / float(max(H, W_))
        mid = cv2.resize(rgb, (max(16, round(W_ * ms)), max(16, round(H * ms))), interpolation=cv2.INTER_AREA) if ms < 1 else rgb
        ms = mid.shape[1] / float(small.shape[1])
        try:
            q = refine(mid, q * ms) / ms
        except Exception:
            pass
    q = _order(q).astype(np.float64)
    if INSET:
        c = q.mean(0); q = c + (q - c) * (1 - INSET)
    q = q / s
    q[:, 0] = np.clip(q[:, 0], 0, W_ - 1); q[:, 1] = np.clip(q[:, 1], 0, H - 1)
    return q, bool(sure)
