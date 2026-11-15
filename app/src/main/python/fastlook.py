"""Fast, text-safe page looks (OpenCV + numpy).
Everything starts from ONE model of the paper: its colour at every place (small copy: the writing dilates away), and an ink
map `alpha` (0 = paper, 1 = writing / drawing / anything that is clearly not paper) with a threshold that follows the picture's own noise.
 * whiten()   paper -> white; pixels that belong to the writing (alpha > 0, plus a 1 px skirt) are returned EXACTLY as they were.
 * clarify()  sharpen / darken the writing only; paper pixels are never touched, so no noise can appear there.
 * scan looks (color / gray / bw) use the same model."""
import numpy as np
import cv2


def _ss(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def _lum(x):
    return x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114


class Model:
    """rgb uint8 (H, W, 3) -> paper colour field + ink map (all heavy work in 8-bit, SIMD friendly)."""

    def __init__(self, rgb):
        self.rgb = rgb
        H, W = rgb.shape[:2]
        self.H, self.W = H, W
        s = min(1.0, 360.0 / max(H, W))
        sm = cv2.resize(rgb, (max(8, round(W * s)), max(8, round(H * s))), interpolation=cv2.INTER_AREA) if s < 1.0 else rgb
        h, w = sm.shape[:2]
        k = int(max(5, round(0.045 * max(h, w)))) | 1
        B = cv2.dilate(sm.astype(np.float32), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        B = cv2.GaussianBlur(B, (0, 0), max(2.0, k * 0.5))
        P = np.percentile(B.reshape(-1, 3), 92, axis=0).astype(np.float32)          # paper level of the whole page, per channel
        B = np.maximum(B, 0.55 * P)                                                 # big dark things (photos, stamps) never look like paper in shadow
        self.P = P
        self.Bs = B
        self._build()

    def paper_field(self):
        """paper colour at full size, float32 (H, W, 3)"""
        return cv2.resize(self.Bs, (self.W, self.H), interpolation=cv2.INTER_LINEAR)

    def _build(self):
        x = self.rgb
        H, W = self.H, self.W
        B8 = np.clip(self.Bs + 0.5, 1, 255).astype(np.uint8)
        Bf8 = cv2.resize(B8, (W, H), interpolation=cv2.INTER_LINEAR)
        r8 = cv2.divide(x, Bf8, scale=255.0)                                        # 255 = paper, lower = darker than the paper around
        c0, c1, c2 = cv2.split(r8)
        m8 = cv2.min(cv2.min(c0, c1), c2)                                           # darkest channel: coloured ink counts as ink as well
        sub = (255 - m8[::3, ::3]).astype(np.float32) / 255.0
        paper = sub[sub < 0.14]
        if paper.size < 50:
            sig, bias = 0.02, 0.0
        else:
            med = float(np.median(paper)); sig = 1.4826 * float(np.median(np.abs(paper - med))); bias = med
        self.sigma, self.pbias = sig, bias
        lo = float(np.clip(3.4 * sig + 0.012 + max(0.0, bias), 0.04, 0.13))
        hi = lo * 2.0 + 0.03
        self.lo, self.hi = lo, hi
        long = max(H, W)
        kb = int(max(5, round(0.011 * long))) | 1
        se = np.ones((kb, kb), np.uint8)
        thin8 = cv2.subtract(cv2.erode(cv2.dilate(m8, se), se), m8)                 # black-hat: dark structures NARROWER than the window = writing
        v = np.arange(256, dtype=np.float32) / 255.0
        lut1 = (_ss((v - lo) / (hi - lo)) * 255.0 + 0.5).astype(np.uint8)
        lut2 = (_ss((v - 0.34) / 0.2) * 255.0 + 0.5).astype(np.uint8)               # very dark things are always kept (photos, stamps, big letters)
        a8 = cv2.max(cv2.LUT(thin8, lut1), cv2.LUT(cv2.subtract(255, m8), lut2))
        self.alpha8 = a8
        self._alpha = None
        self._prot = None

    @property
    def alpha(self):
        if self._alpha is None:
            self._alpha = self.alpha8.astype(np.float32) * (1.0 / 255.0)
        return self._alpha

    def protected(self):
        """ink map grown by one pixel (the anti-aliased edge pixels of the writing belong to it), float32 0..1"""
        if self._prot is None:
            self._prot = cv2.dilate(self.alpha8, np.ones((3, 3), np.uint8)).astype(np.float32) * (1.0 / 255.0)
        return self._prot


_WHITE = {}


def _white(shape):
    w = _WHITE.get(shape)
    if w is None:
        _WHITE.clear()
        w = _WHITE[shape] = np.full(shape, 255, np.uint8)
    return w


def whiten(rgb, strength=1.0, model=None):
    """Page white, writing untouched. strength 0..1. Ink pixels (and their 1 px skirt) are returned bit for bit."""
    s = float(np.clip(strength, 0.0, 1.0))
    if s <= 0.0:
        return rgb
    M = model or Model(rgb)
    ap = M.protected()
    w_x = ap if s >= 1.0 else (1.0 - s * (1.0 - ap)).astype(np.float32)
    w_w = (1.0 - w_x).astype(np.float32)
    return cv2.blendLinear(rgb, _white(rgb.shape), w_x, w_w)


def clarify(rgb, amount=0.6, model=None):
    """Make the WRITING clearer (0..1). Only pixels that are ink (or touch it) change; the paper stays as photographed.
    1. unsharp mask with a noise core, clipped inside the local min..max (no overshoot = no halos / ringing)
    2. gamma on the ink relative to the paper level (paper = 1 does not move)
    Both are weighted by the ink map, so no noise can be added to the paper."""
    t = float(np.clip(amount, 0.0, 1.0))
    if t <= 0.0:
        return rgb
    M = model or Model(rgb)
    H, W = M.H, M.W
    long = max(H, W)
    x = rgb.astype(np.float32)
    Y = cv2.transform(x, np.array([[0.114, 0.587, 0.299]], np.float32))            # BGR weights on RGB data -> use RGB order below
    Y = cv2.transform(x, np.array([[0.299, 0.587, 0.114]], np.float32))
    Bl = cv2.transform(M.paper_field(), np.array([[0.299, 0.587, 0.114]], np.float32))
    Y = Y.reshape(H, W); Bl = Bl.reshape(H, W)
    ap = M.protected()
    zone = cv2.GaussianBlur(cv2.dilate(ap, np.ones((3, 3), np.uint8)), (0, 0), 0.8)
    r0 = float(np.clip(0.0009 * long, 0.7, 2.0))
    core = 0.6 * M.sigma * Bl + 1.0
    kw = int(2 * np.ceil(2.0 * r0)) + 1
    se = np.ones((kw, kw), np.uint8)
    mn = cv2.erode(Y, se); mx = cv2.dilate(Y, se)
    Ys = Y
    for rr, am in ((r0, 1.0 + 3.0 * t), (2.2 * r0, 0.5 + 1.5 * t)):
        dd = Ys - cv2.GaussianBlur(Ys, (0, 0), rr)
        dd = np.sign(dd) * np.maximum(np.abs(dd) - core, 0.0)
        Ys = np.minimum(np.maximum(Ys + am * dd, mn), mx)
    Yz = Y + zone * (Ys - Y)
    n = np.clip(Yz / np.maximum(Bl, 1.0), 1e-3, 1.0)
    Yd = Bl * np.exp(np.log(n) * (1.0 + 1.1 * t))
    Yn = Yz + M.alpha * (Yd - Yz)
    delta = (Yn - Y)
    out = x + delta[..., None]
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


def scan(rgb, mode, strength=1.0, model=None):
    """Scan looks. The paper becomes white, the light is made even (the writing is measured against the paper AROUND it), colour of the ink stays.
    mode: color | gray | bw. strength 0..1 (ink darkness / how hard the black-and-white step is)."""
    M = model or Model(rgb)
    H, W = M.H, M.W
    s = float(np.clip(strength, 0.0, 1.0))
    Bf = M.paper_field()
    n = np.minimum(rgb.astype(np.float32) / np.maximum(Bf, 1.0), 1.0)              # 1 = paper
    ap = M.protected()[..., None]
    g = 1.0 + 0.5 * s
    if mode == "bw":
        lum = cv2.transform(n, np.array([[0.299, 0.587, 0.114]], np.float32)).reshape(H, W)
        th = 0.78 - 0.10 * s
        v = np.clip((lum - (th - 0.22)) / 0.30, 0.0, 1.0)
        v = v * v * (3.0 - 2.0 * v)
        v = 1.0 - (1.0 - v) * M.protected()                                         # paper: exactly white
        v = np.maximum(v, 0.0)
        out = np.repeat((v * 255.0 + 0.5).astype(np.uint8)[..., None], 3, 2)
        return out
    nn = n ** g if g != 1.0 else n
    out = ap * nn + (1.0 - ap) * 1.0
    if mode == "gray":
        lum = cv2.transform(out.astype(np.float32), np.array([[0.299, 0.587, 0.114]], np.float32)).reshape(H, W)
        out = np.repeat(lum[..., None], 3, 2)
    return (np.clip(out, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
