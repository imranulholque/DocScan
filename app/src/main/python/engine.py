"""DocScanner engine: thin wrapper around the tested Image Fixer code (core.py, scanner.py - copied unchanged).
The Android app calls these functions through Chaquopy. Everything goes through files and plain strings."""
import core
import scanner
from PIL import Image, ImageOps

# extra common paper sizes (PDF points); core.pdf_page() reads core.PAGE_SIZES, so they work everywhere
core.PAGE_SIZES.update({"a5": (419.528, 595.276), "legal": (612.0, 1008.0)})

# OpenCV spreads its work over every CPU core by default, which leaves the phone's UI thread starving (the crop screen's
# scanning animation froze). Two threads keep the page finder fast and leave the other cores free for drawing.
try:
    if scanner.cv2 is not None:
        scanner.cv2.setNumThreads(2)
except Exception:
    pass

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


def prepare(src, dst, max_side=2600):
    """Camera / gallery photo -> upright working JPEG (EXIF fixed, long side <= max_side). Returns 'w,h'."""
    im = Image.open(src)
    try:
        im.draft("RGB", (max_side, max_side))          # fast, low-memory JPEG decode
    except Exception:
        pass
    im.load()
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
    """The PC program's Auto crop finder (scanner.detect_document thresholds), run on a smaller copy.
    Only the fast OpenCV finder is used here: when it finds no sheet at all the old finder (it needs many seconds on a phone,
    and the screen looked stuck) is NOT asked; the whole picture is returned as 'not sure' so the person can drag the corners.
    Returns 'sure|x0,y0,x1,y1,x2,y2,x3,y3' (TL,TR,BR,BL in picture pixels); sure = 1 / 0."""
    im = _open(path)
    W, H = im.size
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
    """Live camera outline: the same fast OpenCV page finder, run on a small camera frame (raw RGB bytes, w x h, upright).
    Returns '' when no page is seen, else 'x0,y0,x1,y1,x2,y2,x3,y3' (TL,TR,BR,BL) as fractions 0..1 of the frame."""
    if scanner.cv2 is None:
        return ""
    try:
        try:                                           # a Java byte[] arrives as a signed buffer: read the raw bytes
            raw = memoryview(rgb).cast("B").tobytes()
        except Exception:
            raw = bytes(rgb)
        im = Image.frombytes("RGB", (int(w), int(h)), raw)
        res = scanner._detect_fast(im)
    except Exception:
        return ""
    if res is None:
        return ""
    quad, found, info = res
    area = abs(sum(quad[i][0] * quad[(i + 1) % 4][1] - quad[(i + 1) % 4][0] * quad[i][1] for i in range(4))) / 2.0
    if info.get("score", 0) < 0.35 or area < 0.06 * w * h:
        return ""
    return ",".join("%.4f" % v for x, y in quad for v in (x / float(w), y / float(h)))

def crop(path, quad_csv, dst):
    """Cut the paper out and remove the camera angle (scanner.straighten). Returns 'w,h'."""
    v = [float(x) for x in quad_csv.split(",")]
    quad = [(v[i], v[i + 1]) for i in range(0, 8, 2)]
    out = scanner.straighten(_open(path), quad)
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


def _orig_look(im, clarity):
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
    arr = core.clarity(np.asarray(out), core.new_look(sharp=int(60 * t)))
    return Image.fromarray(arr)


def _full_look(im, mode, clarity):
    """The look at full strength for this slider value (the old 'apply')."""
    import numpy as np
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


def text_clarity(im, tc):
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


def apply(im, mode, clarity, tc=0):
    """PIL image -> PIL image. mode: orig | color | vivid | gray | bw.
    Like Lightroom's effect amount: 0 = the original picture, FULL_AT = the look as designed, above = stronger.
    "orig" has no look; its slider is the text clarity (0 = untouched). For the other looks `clarity` is the effect amount
    and `tc` is the text clarity (0 = off)."""
    if mode == "orig":
        return _orig_look(im, clarity)
    c = float(clarity)
    base = text_clarity(im.convert("RGB"), tc)
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


def export_image(path, dst, fmt, mode, clarity, number=0, pos="off", num_size=12, font_path="", tc=0, rot=0, sigs=""):
    """Final picture: JPG or PNG, full resolution, with the chosen look, the signatures (if any) and the page number (if chosen)."""
    out = apply(_turn(_open(path), rot), mode, float(clarity), float(tc))
    out = sign_page(out, sigs)
    out = stamp_number(out, number, pos, float(num_size), font_path)
    if fmt == "png":
        out.save(dst, "PNG", optimize=False)
    else:
        out.save(dst, "JPEG", quality=95, subsampling=0, optimize=True)
    return "ok"


def make_pdf(pages, dst, page_size="fit", num_pos="off", num_size=12):
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


def thumbs(path, prefix, clarity=60):
    """Small previews of the page (Original / Color / Vivid / Gray / B&W) for the look picker."""
    for m in ("orig", "color", "vivid", "gray", "bw"):
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
