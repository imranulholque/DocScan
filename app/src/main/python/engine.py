"""DocScanner engine: thin wrapper around the tested Image Fixer code (core.py, scanner.py - copied unchanged).
The Android app calls these functions through Chaquopy. Everything goes through files and plain strings."""
import core
import scanner
from PIL import Image, ImageOps

try:                                                    # the fast text-safe looks and the learned page finder need OpenCV
    import cv2
    import numpy as np
    import fastlook
    import finder
    FAST = True
except Exception:                                       # a build without OpenCV: the older (slower) code below is used
    cv2 = None
    FAST = False

# extra common paper sizes (PDF points); core.pdf_page() reads core.PAGE_SIZES, so they work everywhere
core.PAGE_SIZES.update({"a5": (419.528, 595.276), "legal": (612.0, 1008.0)})

# OpenCV spreads its work over every CPU core by default, which leaves the phone's UI thread starving (the crop screen's
# scanning animation froze). Two threads keep the page finder fast and leave the other cores free for drawing.
try:
    if scanner.cv2 is not None:
        scanner.cv2.setNumThreads(2)
except Exception:
    pass
DETECT_SIDE_FAST = 1000      # the learned finder works on a copy with this long side (it shrinks further inside)

NUM_MARGIN = 28.0            # page number distance from the page edge (PDF points, about 1 cm)
NUM_DIGIT_H = 0.676          # height of a Times digit (em)
NUM_DIGIT_W = 0.5            # width of a Times digit (em)
CREDIT = "DocScanner - Imranul Hoque Sabbir"

AUTO_OK_SCORE = 0.6          # same thresholds as the PC program's Auto crop
AUTO_OK_EDGE = 0.6


def _open(path):
    return core.load_image(path)


def _save_jpg(im, dst, q=95):
    im.convert("RGB").save(dst, "JPEG", quality=q, subsampling=0)


def _to_srgb(im):
    """Phone photos often carry a wide-gamut colour profile (Display P3 ...). The phone screen honours it, but Pillow ignores it and the
    saved copy has none, so the same numbers were read as sRGB: colours came out flatter / shifted after the shot. Convert to real sRGB."""
    icc = im.info.get("icc_profile")
    if not icc:
        return im
    try:
        import io
        from PIL import ImageCms
        src = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        try:
            if "srgb" in ImageCms.getProfileDescription(src).lower():
                return im
        except Exception:
            pass
        dst = ImageCms.createProfile("sRGB")
        base = im if im.mode == "RGB" else im.convert("RGB")
        return ImageCms.profileToProfile(base, src, dst, renderingIntent=1, outputMode="RGB")
    except Exception:
        return im


def prepare(src, dst, max_side=3300):
    """Camera / gallery photo -> upright working JPEG (EXIF fixed, colour profile -> sRGB, long side <= max_side). Returns 'w,h'."""
    im = Image.open(src)
    try:
        im.draft("RGB", (max_side, max_side))          # fast, low-memory JPEG decode
    except Exception:
        pass
    im.load()
    im = _to_srgb(im)
    try:
        im = ImageOps.exif_transpose(im)
    except Exception:
        pass
    if im.mode != "RGB":
        im = im.convert("RGB")
    w, h = im.size
    s = max_side / float(max(w, h))
    if s < 1.0:
        im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.Resampling.LANCZOS)
    _save_jpg(im, dst)
    return "%d,%d" % im.size


DETECT_SIDE = 1400           # the page finder works on a copy with this long side (it shrinks further inside): faster on a phone


def _full_frame(W, H, m=0.01):
    return [(W * m, H * m), (W * (1 - m), H * m), (W * (1 - m), H * (1 - m)), (W * m, H * (1 - m))]


def detect(path):
    """Auto crop. FAST (OpenCV): the learned finder (finder.py: many outline candidates -> ranker trained on the user's own corrected
    crops -> straight-edge snapping); about 0.1 s. Without OpenCV / on any error: the PC program's finder as before.
    Returns 'sure|x0,y0,x1,y1,x2,y2,x3,y3' (TL,TR,BR,BL in picture pixels); sure = 1 / 0."""
    im = _open(path)
    W, H = im.size
    if FAST:
        try:
            k = DETECT_SIDE_FAST / float(max(W, H))
            small = im.convert("RGB").resize((max(8, round(W * k)), max(8, round(H * k))), Image.Resampling.BILINEAR) if k < 1.0 else im.convert("RGB")
            if k >= 1.0:
                k = 1.0
            q, sure = finder.find_page(np.asarray(small))
            if q is not None:
                q = [(float(x) / k, float(y) / k) for x, y in q]
                q = [(min(max(x, 0.0), W - 1.0), min(max(y, 0.0), H - 1.0)) for x, y in q]
                return "%d|%s" % (1 if sure else 0, ",".join("%.2f" % v for p in q for v in p))
        except Exception:
            pass
    return _detect_old(im, W, H)


def _detect_old(im, W, H):
    k = DETECT_SIDE / float(max(W, H))
    small = im.resize((max(8, round(W * k)), max(8, round(H * k))), Image.Resampling.BILINEAR) if k < 1.0 else im
    if k >= 1.0:
        k = 1.0
    if scanner.cv2 is not None:
        try:
            res = scanner._detect_fast(small)
        except Exception:
            res = None
        if res is None:
            q = _full_frame(W, H)
            return "0|%s" % ",".join("%.2f" % v for p in q for v in p)
        quad, found, info = res
        quad = [(x / k, y / k) for x, y in quad]
    else:                                                   # no OpenCV in this build: the older finder
        quad, found, info = scanner.detect_document(im, return_score=True)
    area = abs(sum(quad[i][0] * quad[(i + 1) % 4][1] - quad[(i + 1) % 4][0] * quad[i][1] for i in range(4))) / 2.0
    sure = bool(found and info["score"] >= AUTO_OK_SCORE and info["edge"] >= AUTO_OK_EDGE and area >= 0.08 * W * H)
    q = _inset(quad)
    return "%d|%s" % (1 if sure else 0, ",".join("%.2f" % v for p in q for v in p))


def _inset(quad, k=0.005):
    cx = sum(p[0] for p in quad) / 4.0
    cy = sum(p[1] for p in quad) / 4.0
    return [(cx + (x - cx) * (1.0 - k), cy + (y - cy) * (1.0 - k)) for x, y in quad]



def live(rgb, w, h):
    """Live camera outline: the light version of the learned finder on a small camera frame (raw RGB bytes, w x h, upright).
    Returns '' when no page is seen, else 'x0,y0,x1,y1,x2,y2,x3,y3' (TL,TR,BR,BL) as fractions 0..1 of the frame."""
    if not FAST:
        return ""
    try:
        try:                                           # a Java byte[] arrives as a signed buffer: read the raw bytes
            raw = memoryview(rgb).cast("B").tobytes()
        except Exception:
            raw = bytes(rgb)
        a = np.frombuffer(raw, np.uint8).reshape(int(h), int(w), 3)
        q, sure = finder.find_page(a, lite=True)
    except Exception:
        return ""
    if q is None or not sure:
        return ""
    return ",".join("%.4f" % v for x, y in q for v in (x / float(w), y / float(h)))


def live_lines(rgb, w, h):
    """Live camera outline from the 4 straight sides of the page only (linefinder: fast and exact when all 4 corners are in view).
    Same input / output as live(). Returns '' when no page with 4 real straight sides is seen: the app then uses the Kotlin finder."""
    if not FAST or finder.linefinder is None:
        return ""
    try:
        try:
            raw = memoryview(rgb).cast("B").tobytes()
        except Exception:
            raw = bytes(rgb)
        a = np.frombuffer(raw, np.uint8).reshape(int(h), int(w), 3)
        r = finder.linefinder.find_quad(a, 320)
    except Exception:
        return ""
    if r is None:
        return ""
    q, conf, sure = r
    if not sure and conf < 0.6:
        return ""
    return ",".join("%.4f" % v for x, y in q for v in (x / float(w), y / float(h)))


def _paper_balance(im):
    """Natural colour: phone cameras often leave a colour cast on a white page (cool LED light -> blueish, lamp -> yellowish).
    The paper itself is the best reference: the bright, even, nearly colourless part of the page is measured and each colour channel is
    scaled so that part becomes neutral. Careful on purpose - nothing is changed when the bright part is clearly coloured (a colour photo,
    a coloured card), when it is not even, or when the cast is tiny; and no channel is ever scaled by more than about 35 %."""
    try:
        rgb = im.convert("RGB")
        w, h = rgb.size
        k = 360.0 / float(max(w, h))
        small = rgb.resize((max(8, round(w * k)), max(8, round(h * k))), Image.Resampling.BOX) if k < 1.0 else rgb
        a = np.asarray(small).astype(np.float32)
        lum = a[:, :, 0] * 0.299 + a[:, :, 1] * 0.587 + a[:, :, 2] * 0.114
        mx = a.max(axis=2)
        mn = a.min(axis=2)
        ok = mx < 252.0                                   # not burnt out (a burnt-out white has lost its colour)
        if ok.sum() < 0.2 * ok.size:
            return im
        thr = np.percentile(lum[ok], 70)
        m = ok & (lum >= thr) & (lum > 90.0)              # the bright part = paper (text and shadows are darker)
        if m.sum() < 0.03 * m.size:
            return im
        px = a[m]
        p = np.median(px, axis=0)                         # paper colour (R, G, B)
        if float(p.min()) < 60.0:
            return im
        if (float(p.max()) - float(p.min())) / float(p.max()) > 0.30:
            return im                                     # clearly coloured: a photo / coloured page, not a white sheet
        chroma = (px[:, 2] - px[:, 0]) / np.maximum(px.sum(axis=1) / 3.0, 1.0)
        if float(np.percentile(chroma, 90) - np.percentile(chroma, 10)) > 0.16:
            return im                                     # not an even colour: no clear paper here
        g = float(p.mean()) / p                           # gains that make the paper neutral (same brightness)
        g = np.clip(g, 1.0 / 1.35, 1.35)
        if float(np.abs(g - 1.0).max()) < 0.02:
            return im
        full = np.asarray(rgb).astype(np.float32) * g.reshape(1, 1, 3)
        return Image.fromarray(np.clip(full + 0.5, 0, 255).astype(np.uint8), "RGB")
    except Exception:
        return im


def crop(path, quad_csv, dst):
    """Cut the paper out and remove the camera angle (scanner.straighten). Returns 'w,h'."""
    v = [float(x) for x in quad_csv.split(",")]
    quad = [(v[i], v[i + 1]) for i in range(0, 8, 2)]
    out = _paper_balance(scanner.straighten(_open(path), quad))
    _save_jpg(out, dst)
    return "%d,%d" % out.size


def rotate(path, dst, turns):
    """Turn clockwise by turns * 90 degrees."""
    im = _open(path)
    t = int(turns) % 4
    if t:
        im = im.transpose({1: Image.Transpose.ROTATE_270, 2: Image.Transpose.ROTATE_180,
                           3: Image.Transpose.ROTATE_90}[t])
    _save_jpg(im, dst)
    return "%d,%d" % im.size


# ------------------------------------------------------------------ ID card: front + back on ONE page
ID_RATIO = 85.6 / 53.98          # ISO/IEC 7810 ID-1 (NID, driving licence, bank card): 85.6 x 53.98 mm
A4_MM = (210.0, 297.0)


def _card_fit(im, landscape=None):
    """A straightened card photo -> trimmed (no background bleed at the rim) and turned to the wanted direction. Its own shape is kept:
    cards (NID, licence, passport page, student card ...) come in many sizes."""
    im = im.convert("RGB")
    w, h = im.size
    tx, ty = int(round(w * .006)), int(round(h * .006))
    if w - 2 * tx > 16 and h - 2 * ty > 16:
        im = im.crop((tx, ty, w - tx, h - ty))
    w, h = im.size
    if landscape is not None and (w >= h) != landscape:
        im = im.transpose(Image.Transpose.ROTATE_90 if landscape else Image.Transpose.ROTATE_270)
        w, h = im.size
    return im


def _round_paste(page, card, x, y, radius, line):
    """Card with round corners (like the real card) + a hairline outline, pasted onto the page. Corner mask is drawn 4x for smooth edges."""
    from PIL import ImageDraw
    w, h = card.size
    radius = max(6, int(round(min(w, h) * .06)))               # corner round-ness follows the card's own size
    k = 4
    m = Image.new("L", (w * k, h * k), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w * k - 1, h * k - 1), radius=radius * k, fill=255)
    m = m.resize((w, h), Image.Resampling.LANCZOS)
    page.paste(card, (x, y), m)
    ImageDraw.Draw(page).rounded_rectangle((x, y, x + w - 1, y + h - 1), radius=radius, outline=(176, 176, 176), width=line)


def id_card(front, back, dst, size_mm=86.0, dpi=300):
    """Front and back of an ID card (each already cut out and straightened) -> ONE A4 page, both sides centred, same size,
    round corners, equal gaps (like a photocopy, but clean). size_mm = length of the card's long side on the paper (any size; it shrinks to fit the page).
    Landscape cards are stacked top / bottom; portrait cards sit side by side. Returns 'w,h' of the page picture."""
    pxmm = dpi / 25.4
    PW, PH = int(round(A4_MM[0] * pxmm)), int(round(A4_MM[1] * pxmm))
    a = _card_fit(_open(front))
    landscape = a.size[0] >= a.size[1]
    b = _card_fit(_open(back), landscape)
    ra0, rb0 = a.size[0] / float(a.size[1]), b.size[0] / float(b.size[1])
    if abs(ra0 - rb0) / ra0 < .08:                                  # same card, slightly different crops: both sides get the same shape
        b = b.resize((b.size[0], int(round(b.size[0] / ra0))), Image.Resampling.LANCZOS)
    margin = 14.0 * pxmm
    gap = 12.0 * pxmm
    long_px = float(size_mm) * pxmm

    def scaled(im, long_side):
        w, h = im.size
        k = long_side / float(max(w, h))
        return im.resize((max(8, int(round(w * k))), max(8, int(round(h * k)))), Image.Resampling.LANCZOS)

    # the biggest the two can be on the page: shrink when the wished size does not fit
    if landscape:
        avail_h = PH - 2 * margin - gap
        ra, rb = a.size[0] / float(a.size[1]), b.size[0] / float(b.size[1])
        long_px = min(long_px, PW - 2 * margin, avail_h / (1.0 / ra + 1.0 / rb))
    else:
        ra, rb = a.size[1] / float(a.size[0]), b.size[1] / float(b.size[0])
        long_px = min(long_px, PH - 2 * margin, (PW - 2 * margin - gap) / (1.0 / ra + 1.0 / rb))
    a, b = scaled(a, long_px), scaled(b, long_px)
    page = Image.new("RGB", (PW, PH), (255, 255, 255))
    rad = max(6, int(round(3.2 * pxmm * (long_px / (85.6 * pxmm)))))
    line = max(1, int(round(dpi / 150.0)))
    if landscape:
        total = a.size[1] + gap + b.size[1]
        y0 = int(round((PH - total) / 2.0 - PH * .03))            # optical centre: a touch above the middle
        _round_paste(page, a, (PW - a.size[0]) // 2, y0, rad, line)
        _round_paste(page, b, (PW - b.size[0]) // 2, int(round(y0 + a.size[1] + gap)), rad, line)
    else:
        total = a.size[0] + gap + b.size[0]
        x0 = int(round((PW - total) / 2.0))
        _round_paste(page, a, x0, (PH - a.size[1]) // 2, rad, line)
        _round_paste(page, b, int(round(x0 + a.size[0] + gap)), (PH - b.size[1]) // 2, rad, line)
    _save_jpg(page, dst, 95)
    return "%d,%d" % page.size


def fast_ok():
    """True when the OpenCV based fast finder / looks are available in this build."""
    return bool(FAST)


def look(mode, clarity):
    """The ONE 'Text clarity' slider (0..100): every text option of the PC program merged.
    shadow / paper white / text darkness / clean paper  (core.remove_shadow)
    + text sharpness / line & shape sharpness / faint strokes / text darkness / clean specks  (core.clarity)."""
    t = max(0.0, min(1.0, float(clarity) / 100.0))
    shadow = dict(core.SHADOW_DEFAULTS)
    shadow["protect"] = 0
    shadow.update(mode={"color": "color", "gray": "gray", "bw": "bw"}.get(mode, "color"),
                  white=int(35 + 45 * t), ink=int(40 + 35 * t), clean=int(15 + 65 * t))
    sharp = core.new_look(sharp=int(70 * t), objs=int(40 * t), detail=int(60 * t),
                          dark=int(70 * t), clear=int(30 * t))
    return t, shadow, sharp


def _orig_look_old(im, clarity):
    """'Original' keeps the paper and the colours exactly as photographed; only the writing gets clearer.
    clarity 0 = untouched ... 100 = strongest. Only lightness is changed (core.clarity), so colours never shift."""
    import numpy as np
    t = max(0.0, min(1.0, float(clarity) / 100.0))
    if t <= 0.0:
        return im
    look = core.new_look(sharp=int(85 * t), dark=int(10 * t), clear=int(10 * t))
    return Image.fromarray(core.clarity(np.asarray(im.convert("RGB")), look))


def _smoothstep(a):
    import numpy as np
    a = np.clip(a, 0.0, 1.0)
    return a * a * (3.0 - 2.0 * a)


def _vivid(im, clarity):
    """A look for COLOUR pictures (photos, coloured pages, drawings). The scan looks keep only black text on white paper,
    which flattens colours. This one keeps every colour and only
      1. evens out the light (soft shadows lifted, same gain on r,g,b so no colour cast),
      2. sets black / white points from the picture's own brightness,
      3. adds a gentle S-curve contrast and a little colour richness (protected for already strong colours),
      4. sharpens the lightness only (no coloured halos).
    clarity 0..100 = how strong all of it is."""
    import numpy as np
    from PIL import ImageFilter
    t = max(0.0, min(1.0, float(clarity) / 100.0))
    rgb = im.convert("RGB")
    W, H = rgb.size
    x = np.asarray(rgb, dtype=np.float32) / 255.0
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114

    # 1. light: "paper_light" with a WIDE window (so coloured objects are not mistaken for shadows), luminance gain only
    ws = 480.0 / max(W, H)
    sw, sh = max(16, round(W * ws)), max(16, round(H * ws))
    small = np.asarray(rgb.resize((sw, sh), Image.Resampling.BOX))
    bl = core.paper_light(small, None, 0.14).mean(2)
    target = float(np.percentile(bl, 88))
    g = np.clip(target / np.maximum(bl, 20.0), 1.0, 1.0 + 0.7 * (0.5 + 0.5 * t))
    g = 1.0 + (g - 1.0) * (0.55 + 0.35 * t)
    # blur the gain map a lot: the correction may follow soft shadows, never the edges of objects (no halos)
    gm = Image.fromarray(np.clip((g - 1.0) / 0.7 * 255.0 + 0.5, 0, 255).astype(np.uint8))
    gm = np.asarray(gm.filter(ImageFilter.GaussianBlur(0.06 * max(sw, sh))), dtype=np.float32) / 255.0 * 0.7 + 1.0
    g = np.asarray(Image.fromarray(gm.astype(np.float32), "F").resize((W, H), Image.Resampling.BICUBIC), dtype=np.float32)
    x = np.clip(x * g[..., None], 0.0, 1.0)

    # 2. levels from the lightness (one lo / hi for all channels: no colour cast)
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
    sub = Y[::max(1, H // 400), ::max(1, W // 400)]
    lo = float(np.percentile(sub, 0.3 + 0.5 * t))
    hi = float(np.percentile(sub, 99.7 - 0.5 * t))
    if hi - lo > 0.2:
        x = np.clip((x - lo) / (hi - lo), 0.0, 1.0)

    # 3. mid-tones a little lighter if the picture is dark, S-curve (on lightness, keeps hue) + colour richness
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
    med = float(np.percentile(Y[::max(1, H // 300), ::max(1, W // 300)], 50))
    if med < 0.55:
        gam = float(np.clip(np.log(0.55) / np.log(max(med, 0.05)), 0.7, 1.0))
        x = x ** (1.0 - (1.0 - gam) * (0.5 + 0.5 * t))
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
    k = 0.10 + 0.20 * t
    Y2 = Y + k * (_smoothstep(Y) - Y)
    ratio = (Y2 + 1e-4) / (Y + 1e-4)
    x = np.clip(x * np.clip(ratio, 0.75, 1.4)[..., None], 0.0, 1.0)
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
    mx, mn = x.max(2), x.min(2)
    sat = (mx - mn) / np.maximum(mx, 1e-3)
    boost = (0.03 + 0.13 * t) * (1.0 - _smoothstep((sat - 0.35) / 0.55)) * _smoothstep(Y / 0.2)     # weak colours get more, strong ones stay natural
    x = np.clip(Y[..., None] + (x - Y[..., None]) * (1.0 + boost)[..., None], 0.0, 1.0)

    out = Image.fromarray(np.clip(x * 255.0 + 0.5, 0, 255).astype(np.uint8))

    # 4. sharpen the lightness only
    if FAST:                                              # lightness-only unsharp with a noise core, never past the local min / max (no halos)
        a = np.ascontiguousarray(np.asarray(out))
        Yl = cv2.transform(a.astype(np.float32), np.array([[0.299, 0.587, 0.114]], np.float32)).reshape(a.shape[:2])
        r0 = float(np.clip(0.0009 * max(W, H), 0.7, 2.5))
        kw = int(2 * np.ceil(2.0 * r0)) + 1
        se = np.ones((kw, kw), np.uint8)
        mn, mx = cv2.erode(Yl, se), cv2.dilate(Yl, se)
        d = Yl - cv2.GaussianBlur(Yl, (0, 0), r0)
        d = np.sign(d) * np.maximum(np.abs(d) - 2.5, 0.0)
        Ys = np.minimum(np.maximum(Yl + (0.6 + 1.6 * t) * d, mn), mx)
        return Image.fromarray(np.clip(a.astype(np.float32) + (Ys - Yl)[..., None] + 0.5, 0, 255).astype(np.uint8))
    arr = core.clarity(np.asarray(out), core.new_look(sharp=int(60 * t)))
    return Image.fromarray(arr)


EXTRA = ("noshadow", "lighten", "whiteboard", "whiten")


def _light_gain(rgb_arr, t, reach):
    """A smooth map (H x W) that says how much brighter each place must get so the paper is evenly lit.
    The paper brightness is estimated on a small copy (dilate = the writing disappears, then a strong blur), so the correction follows
    soft shadows and never the edges of the writing. reach = how much brighter a place may get (1.0 = twice)."""
    import numpy as np
    from PIL import ImageFilter
    H, W = rgb_arr.shape[:2]
    k = 420.0 / max(W, H)
    sw, sh = max(16, round(W * k)), max(16, round(H * k))
    small = Image.fromarray(rgb_arr).resize((sw, sh), Image.Resampling.BOX).convert("L")
    bg = small.filter(ImageFilter.MaxFilter(11)).filter(ImageFilter.GaussianBlur(0.035 * max(sw, sh)))
    b = np.asarray(bg, dtype=np.float32)
    target = float(np.percentile(b, 92))
    g = np.clip(target / np.maximum(b, 30.0), 1.0, 1.0 + reach) ** (0.55 + 0.45 * t)
    gm = Image.fromarray(g.astype(np.float32), "F").resize((W, H), Image.Resampling.BICUBIC)
    return np.asarray(gm, dtype=np.float32)


def _extra_look(im, mode, clarity):
    """Looks that keep the page natural (the colours stay) and fix what a phone photo gets wrong:
    noshadow    even light: soft shadows of the hand, the phone or a curled page are taken away
    lighten     brighter, whiter page: lifts a dull or dark photo, colours stay
    whiteboard  boards and glossy notes: shadows and glare-free white background, stronger marker colours"""
    import numpy as np
    t = max(0.0, min(1.0, float(clarity) / 100.0))
    rgb = np.asarray(im.convert("RGB"))
    x = rgb.astype(np.float32) / 255.0
    Y = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114

    def white_to(x, pct, to):
        Yv = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
        wp = float(np.percentile(Yv[::max(1, Yv.shape[0] // 400), ::max(1, Yv.shape[1] // 400)], pct))
        return np.clip(x * (to / max(wp, 0.25)), 0.0, 1.0)

    def black_from(x, pct, cap):
        Yv = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
        lo = min(cap, float(np.percentile(Yv[::max(1, Yv.shape[0] // 400), ::max(1, Yv.shape[1] // 400)], pct)))
        return np.clip((x - lo * (0.4 + 0.6 * t)) / (1.0 - lo * (0.4 + 0.6 * t)), 0.0, 1.0)

    if mode in ("noshadow", "whiten"):
        x = np.clip(x * _light_gain(rgb, t, 0.7)[..., None], 0.0, 1.0)
        x = black_from(white_to(x, 75, 0.97), 0.6, 0.25)
    elif mode == "lighten":
        x = white_to(x, 82, 0.96 + 0.03 * t)
        med = float(np.percentile(Y[::max(1, Y.shape[0] // 300), ::max(1, Y.shape[1] // 300)], 50))
        gam = float(np.clip(np.log(0.62) / np.log(max(med, 0.08)), 0.6, 1.0))      # a dark photo is lifted more, a bright one not at all
        x = x ** (1.0 - (1.0 - gam) * (0.5 + 0.5 * t))
    else:                                                                              # whiteboard
        x = np.clip(x * _light_gain(rgb, t, 1.0)[..., None], 0.0, 1.0)
        x = black_from(white_to(x, 70, 0.98), 0.6, 0.25)
        Yv = x[..., 0] * 0.299 + x[..., 1] * 0.587 + x[..., 2] * 0.114
        x = np.clip(Yv[..., None] + (x - Yv[..., None]) * (1.2 + 0.5 * t), 0.0, 1.0)  # stronger marker colours
        x = np.clip((x - 0.04 * t) / (1.0 - 0.04 * t), 0.0, 1.0) ** (1.0 + 0.5 * t * (1.0 - Yv[..., None]))   # background to pure white, ink a little darker
    return Image.fromarray(np.clip(x * 255.0 + 0.5, 0, 255).astype(np.uint8))


def _full_look(im, mode, clarity):
    """The look at full strength for this slider value (the old 'apply')."""
    import numpy as np
    if mode in EXTRA:
        return _extra_look(im, mode, clarity)
    if mode == "vivid":
        return _vivid(im, clarity)
    t, shadow, sharp = look(mode, clarity)
    im = im.convert("RGB")
    strength = 0.3 + 0.7 * t
    if mode == "bw":                          # sharpen the blurred text first, then the hard black / white step
        arr = core.clarity(np.asarray(im), sharp)
        return core.remove_shadow(Image.fromarray(arr), strength, None, shadow).convert("RGB")
    out = core.remove_shadow(im, strength, None, shadow).convert("RGB")
    return Image.fromarray(core.clarity(np.asarray(out), sharp))


FULL_AT = 60.0       # slider value where a look is fully applied (the default); below it the look fades back to the original


def text_clarity_old(im, tc):
    """The TEXT CLARITY slider for every look (0 = untouched ... 100 = strongest): one mix of the Image Fixer text options
    (text sharpness, line / shape sharpness, faint strokes, text darkness, clean specks). It runs on the picture BEFORE the look,
    so faint or soft writing is made bolder and crisper first and the look then turns it into clean black text on white paper
    (after the look the ink is already pure black, so a clarity step there would change nothing)."""
    t = max(0.0, min(1.0, float(tc) / 100.0))
    if t <= 0.0:
        return im
    import numpy as np
    look = core.new_look(sharp=int(85 * t), objs=int(40 * t), detail=int(55 * t), dark=int(75 * t), clear=int(35 * t))
    return Image.fromarray(core.clarity(np.asarray(im.convert("RGB")), look))


def _turn(im, rot):
    """Clockwise quarter turns (the page was turned on the review screen: nothing is re-made there, it is turned here when saving)."""
    t = int(rot) % 4
    if t:
        im = im.transpose({1: Image.Transpose.ROTATE_270, 2: Image.Transpose.ROTATE_180,
                           3: Image.Transpose.ROTATE_90}[t])
    return im


def _orig_look(im, clarity):
    """'Original': paper and colours exactly as photographed, only the writing gets clearer (text-selective: paper pixels never change)."""
    if not FAST:
        return _orig_look_old(im, clarity)
    t = max(0.0, min(1.0, float(clarity) / 100.0))
    if t <= 0.0:
        return im
    a = np.ascontiguousarray(np.asarray(im.convert("RGB")))
    return Image.fromarray(fastlook.clarify(a, t))


def _apply_fast(im, mode, clarity, tc):
    a = np.ascontiguousarray(np.asarray(im.convert("RGB")))
    c = float(clarity)
    M = fastlook.Model(a)
    base = fastlook.clarify(a, max(0.0, min(1.0, float(tc) / 100.0)), M)
    if c <= 0.0:
        return Image.fromarray(base)
    if mode == "whiten":
        out = fastlook.whiten(base, 1.0, M)
    elif mode in ("color", "gray", "bw"):
        out = fastlook.scan(base, mode, min(1.0, c / 100.0), M)
    else:
        out = np.asarray(_full_look(Image.fromarray(base), mode, c).convert("RGB"))
    w = min(1.0, c / FULL_AT)
    if w < 1.0:
        out = cv2.addWeighted(base, 1.0 - w, np.ascontiguousarray(out), w, 0.0)
    return Image.fromarray(out)


def apply(im, mode, clarity, tc=0):
    """PIL image -> PIL image. mode: orig | color | vivid | gray | bw.
    Like Lightroom's effect amount: 0 = the original picture, FULL_AT = the look as designed, above = stronger.
    "orig" has no look; its slider is the text clarity (0 = untouched). For the other looks `clarity` is the effect amount
    and `tc` is the text clarity (0 = off)."""
    if mode == "orig":
        return _orig_look(im, clarity)
    if FAST:
        try:
            return _apply_fast(im, mode, clarity, tc)
        except Exception:
            pass
    c = float(clarity)
    base = text_clarity_old(im.convert("RGB"), tc)
    if c <= 0.0:
        return base
    out = _full_look(base, mode, c)
    w = min(1.0, c / FULL_AT)
    if w >= 1.0:
        return out
    return Image.blend(base, out.convert("RGB"), w)


# ------------------------------------------------------------------ page number (Times New Roman BOLD look)
def num_xy(pos, size, pw, ph, text):
    """Left end and BASELINE of the page number in PDF points, y measured from the TOP of the page.
    pos: tl tc tr bl bc br. Same formula as the live preview in the app."""
    tw = len(text) * NUM_DIGIT_W * size
    m = NUM_MARGIN
    col = pos[1]
    x = m if col == "l" else (pw - m - tw if col == "r" else (pw - tw) / 2.0)
    y = m + NUM_DIGIT_H * size if pos[0] == "t" else ph - m
    return x, y


def stamp_number(im, number, pos, size, font_path):
    """Draws the page number on a picture (JPG / PNG). The picture counts as a 'fit' page (long side = 841.89 pt)."""
    from PIL import ImageDraw, ImageFont
    if pos in (None, "", "off"):
        return im
    im = im.convert("RGB")
    W, H = im.size
    k = max(W, H) / 841.89
    px = max(6, int(round(size * k)))
    try:
        font = ImageFont.truetype(font_path, px)
    except Exception:
        try:
            font = ImageFont.load_default(size=px)
        except Exception:
            font = ImageFont.load_default()
    text = str(int(number))
    x, y = num_xy(pos, size, W / k, H / k, text)
    ImageDraw.Draw(im).text((x * k, y * k), text, font=font, fill=(0, 0, 0), anchor="ls")
    return im


def render(path, dst, mode, clarity, max_side=0, tc=0):
    """Filtered copy of a cropped page (max_side > 0 = small, fast preview). Returns 'w,h'."""
    im = _open(path)
    max_side = int(max_side)
    if max_side > 0 and max(im.size) > max_side:
        s = max_side / float(max(im.size))
        im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.Resampling.LANCZOS)
    out = apply(im, mode, float(clarity), float(tc))
    _save_jpg(out, dst, 90 if max_side > 0 else 95)
    return "%d,%d" % out.size


def _export_image(path, dst, fmt, mode, clarity, number=0, pos="off", num_size=12, font_path="", tc=0, rot=0, sigs=""):
    """Final picture: JPG or PNG, full resolution, with the chosen look, the signatures (if any) and the page number (if chosen)."""
    out = apply(_turn(_open(path), rot), mode, float(clarity), float(tc))
    out = sign_page(out, sigs)
    out = stamp_number(out, number, pos, float(num_size), font_path)
    if fmt == "png":
        out.save(dst, "PNG", optimize=False)
    else:
        out.save(dst, "JPEG", quality=95, subsampling=0, optimize=True)
    return "ok"


def _make_pdf(pages, dst, page_size="fit", num_pos="off", num_size=12):
    """pages = 'path|mode|clarity|textclarity|turns|signatures' lines joined by newline. One PDF, one page per picture, in order.
    page_size: fit | a4 | a5 | letter | legal.  num_pos: off | tl tc tr bl bc br (page number in Times-Bold,
    the standard PDF font = Times New Roman Bold look, drawn as real text)."""
    import io
    rows = []
    for line in pages.split("\n"):
        if not line.strip():
            continue
        p, m, c, t, r, sg = line.rsplit("|", 5)                 # path | mode | clarity | text clarity | turns | signatures
        rows.append((p, m, c, t, r, sg))
    n = len(rows)
    if n == 0:
        raise ValueError("Nothing to save")
    num_size = float(num_size)
    numbered = num_pos not in (None, "", "off")
    offsets = {}
    with open(dst, "wb") as f:
        f.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")

        def put(num, body):
            offsets[num] = f.tell()
            f.write(b"%d 0 obj\n" % num + body + b"\nendobj\n")

        font_obj = 3 + 3 * n
        for i, (p, m, c, t, r, sg) in enumerate(rows):
            im = sign_page(apply(_turn(_open(p), int(r)), m, float(c), float(t)), sg).convert("RGB")
            w, h = im.size
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=95, subsampling=0, optimize=True)
            data = buf.getvalue()
            pw, ph, x, y, dw, dh = core.pdf_page(w, h, page_size)
            base = 3 + 3 * i
            res = "/XObject << /Im0 %d 0 R >> /ProcSet [/PDF /Text /ImageC]" % (base + 2)
            if numbered:
                res += " /Font << /F1 %d 0 R >>" % font_obj
            put(base, ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << %s >> /Contents %d 0 R >>"
                       % (pw, ph, res, base + 1)).encode())
            content = "q %.3f 0 0 %.3f %.3f %.3f cm /Im0 Do Q" % (dw, dh, x, y)
            if numbered:
                text = str(i + 1)
                tx, ty = num_xy(num_pos, num_size, pw, ph, text)
                content += " BT 0 g /F1 %.2f Tf %.2f %.2f Td (%s) Tj ET" % (num_size, tx, ph - ty, text)
            content = content.encode()
            put(base + 1, b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
            put(base + 2, b"<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB "
                          b"/BitsPerComponent 8 /Filter /DCTDecode /Length %d >>\nstream\n" % (w, h, len(data))
                + data + b"\nendstream")
            del im, data, buf
        kids = " ".join("%d 0 R" % (3 + 3 * i) for i in range(n))
        put(2, ("<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, n)).encode())
        put(1, b"<< /Type /Catalog /Pages 2 0 R >>")
        total = font_obj
        put(font_obj, b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
        info = font_obj + 1
        put(info, ("<< /Producer (%s) /Creator (%s) >>" % (CREDIT, CREDIT)).encode())
        total = info + 1
        xref = f.tell()
        f.write(b"xref\n0 %d\n0000000000 65535 f \n" % total)
        for num in range(1, total):
            f.write(b"%010d 00000 n \n" % offsets[num])
        f.write(b"trailer\n<< /Size %d /Root 1 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (total, info, xref))
    return "ok"


def _threads(n):
    try:
        if cv2 is not None:
            cv2.setNumThreads(n)
    except Exception:
        pass


def export_image(*a, **k):
    """Saving shows only a progress bar (nothing animates), so OpenCV may use more cores here than while the camera / crop screens run."""
    _threads(4)
    try:
        return _export_image(*a, **k)
    finally:
        _threads(2)


def make_pdf(*a, **k):
    _threads(4)
    try:
        return _make_pdf(*a, **k)
    finally:
        _threads(2)


def thumbs(path, prefix, clarity=60):
    """Small previews of the page (Original / Color / Vivid / Gray / B&W) for the look picker."""
    for m in ("orig", "color", "vivid", "gray", "bw") + EXTRA:
        render(path, "%s_%s.jpg" % (prefix, m), m, 0 if m == "orig" else clarity, 220)
    return "ok"



# ------------------------------------------------------------------ signatures (background removal = Image Fixer's core.remove_background)
SIG_MAX = 1400               # a saved signature is never bigger than this (long side, pixels)


def _bleed(cut):
    """Pixels that are (almost) transparent get the colour of the ink: when the signature is turned or resized no light or dark
    halo appears around the strokes (their hidden colour would otherwise be mixed in)."""
    import numpy as np
    a = np.asarray(cut)
    al = a[..., 3]
    solid = al > 200
    if int(solid.sum()) < 8:
        return cut
    ink = np.median(a[..., :3][solid], axis=0)
    weak = al < 140
    out = a.copy()
    out[..., :3][weak] = ink.astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def sig_guess(path):
    """Where the writing is: 'x0,y0,x1,y1' (picture pixels) around the ink on the paper, with a little room.
    The whole picture when no clear writing is found."""
    im = _open(path)
    W, H = im.size
    k = 700.0 / float(max(W, H))
    small = im.resize((max(8, round(W * k)), max(8, round(H * k))), Image.Resampling.BILINEAR) if k < 1.0 else im
    if k >= 1.0:
        k = 1.0
    try:
        cut = core.remove_background(small, None, strength=60, soft=30, uneven=False, tint="orig", opacity=100, remove=True,
                                     specks=70, fine=70)
        a = cut.split()[3]
        sw, sh = small.size
        b = core.content_box(a, pad=0.10, thr=90)
        if b is None:
            raise ValueError("nothing")
        covered = (b[2] - b[0]) * (b[3] - b[1]) / float(sw * sh)
        if covered > 0.97:
            raise ValueError("everything")
        x0, y0, x1, y1 = [v / k for v in b]
    except Exception:
        x0, y0, x1, y1 = 0, 0, W, H
    return "%d,%d,%d,%d" % (max(0, x0), max(0, y0), min(W, x1), min(H, y1))


def sig_make(src, dst, box, strength=50, uneven=False, tint="orig", remove=True, bold=0, max_side=SIG_MAX, trim=True):
    """Picture (cropped to box 'x0,y0,x1,y1') -> transparent PNG with the paper taken away. Returns 'w,h'.
    strength  0..100  how much paper / shadow is removed (high = cleaner, low = keeps the thinnest strokes)
    uneven    the paper is not one flat colour (shadow, grey print): the paper colour is followed across the picture
    tint      orig | black | blue (ink colour)      bold  0..100  darker, bolder ink
    trim      cut the empty margin away (the final signature); False keeps the crop box as it is (the live preview)"""
    import numpy as np
    im = _open(src)
    W, H = im.size
    v = [int(round(float(x))) for x in str(box).split(",")]
    x0, y0, x1, y1 = max(0, min(v[0], W - 2)), max(0, min(v[1], H - 2)), max(2, min(v[2], W)), max(2, min(v[3], H))
    if x1 - x0 < 2 or y1 - y0 < 2:
        x0, y0, x1, y1 = 0, 0, W, H
    im = im.crop((x0, y0, x1, y1)).convert("RGB")
    max_side = int(max_side)
    if max(im.size) > max_side:
        k = max_side / float(max(im.size))
        im = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.Resampling.LANCZOS)
    if remove:
        cut = core.remove_background(im, None, strength=int(strength), soft=30, uneven=bool(uneven), tint=str(tint),
                                     opacity=100, remove=True, specks=50, fine=70)
        al = cut.split()[3]
        if float(bold) > 0:
            lut = [int(min(255, v * (1.0 + float(bold) / 35.0))) for v in range(256)]
            cut.putalpha(al.point(lut))
            al = cut.split()[3]
        if trim:
            b = core.content_box(al, pad=0.04, thr=14)
            if b is not None:
                cut = cut.crop(b)
        cut = _bleed(cut)
    else:
        cut = im.convert("RGBA")
    cut.save(dst, "PNG")
    return "%d,%d" % cut.size


def sign_page(im, sigs):
    """Puts the signatures on a page picture. sigs = items 'path~cx~cy~width~rot~alpha' joined by ';'
    cx, cy = centre and width = fractions of the page picture, rot = clockwise degrees, alpha = 0..1."""
    if not sigs:
        return im
    base = im.convert("RGB")
    W, H = base.size
    for item in str(sigs).split(";"):
        if not item.strip():
            continue
        try:
            p, cx, cy, w, rot, al = item.split("~")
            s = Image.open(p).convert("RGBA")
            tw = max(2.0, float(w) * W)
            f = tw / float(s.width)
            nw, nh = max(1, round(s.width * f)), max(1, round(s.height * f))
            s = s.resize((nw, nh), Image.Resampling.LANCZOS if f < 1.0 else Image.Resampling.BICUBIC)
            r = float(rot) % 360.0
            if abs(r) > 0.05:
                try:
                    s = s.convert("RGBa").rotate(-r, expand=True, resample=Image.Resampling.BICUBIC).convert("RGBA")
                except Exception:
                    s = s.rotate(-r, expand=True, resample=Image.Resampling.BICUBIC)
            a = s.split()[3]
            op = max(0.0, min(1.0, float(al)))
            if op < 0.999:
                a = a.point([int(v * op) for v in range(256)])
            x = int(round(float(cx) * W - s.width / 2.0))
            y = int(round(float(cy) * H - s.height / 2.0))
            base.paste(s.convert("RGB"), (x, y), a)
        except Exception:
            continue
    return base
