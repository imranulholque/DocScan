"""DocScan engine: thin wrapper around the tested Image Fixer code (core.py, scanner.py - copied unchanged).
The Android app calls these functions through Chaquopy. Everything goes through files and plain strings."""
import core
import scanner
from PIL import Image, ImageOps

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


def detect(path):
    """The PC program's Auto crop finder (scanner.detect_document), same call, same thresholds.
    Returns 'sure|x0,y0,x1,y1,x2,y2,x3,y3' (TL,TR,BR,BL in picture pixels); sure = 1 / 0."""
    im = _open(path)
    W, H = im.size
    quad, found, info = scanner.detect_document(im, return_score=True)
    area = abs(sum(quad[i][0] * quad[(i + 1) % 4][1] - quad[(i + 1) % 4][0] * quad[i][1] for i in range(4))) / 2.0
    sure = bool(found and info["score"] >= AUTO_OK_SCORE and info["edge"] >= AUTO_OK_EDGE and area >= 0.08 * W * H)
    q = _inset(quad)
    return "%d|%s" % (1 if sure else 0, ",".join("%.2f" % v for p in q for v in p))


def _inset(quad, k=0.005):
    cx = sum(p[0] for p in quad) / 4.0
    cy = sum(p[1] for p in quad) / 4.0
    return [(cx + (x - cx) * (1.0 - k), cy + (y - cy) * (1.0 - k)) for x, y in quad]


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


def apply(im, mode, clarity):
    """PIL image -> PIL image. mode: orig | color | gray | bw."""
    import numpy as np
    if mode == "orig":
        return im
    t, shadow, sharp = look(mode, clarity)
    im = im.convert("RGB")
    strength = 0.3 + 0.7 * t
    if mode == "bw":                          # sharpen the blurred text first, then the hard black / white step
        arr = core.clarity(np.asarray(im), sharp)
        return core.remove_shadow(Image.fromarray(arr), strength, None, shadow).convert("RGB")
    out = core.remove_shadow(im, strength, None, shadow).convert("RGB")
    return Image.fromarray(core.clarity(np.asarray(out), sharp))


def render(path, dst, mode, clarity, max_side=0):
    """Filtered copy of a cropped page (max_side > 0 = small, fast preview). Returns 'w,h'."""
    im = _open(path)
    max_side = int(max_side)
    if max_side > 0 and max(im.size) > max_side:
        s = max_side / float(max(im.size))
        im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.Resampling.LANCZOS)
    out = apply(im, mode, float(clarity))
    _save_jpg(out, dst, 90 if max_side > 0 else 95)
    return "%d,%d" % out.size


def export_image(path, dst, fmt, mode, clarity):
    """Final picture: JPG or PNG, full resolution, with the chosen look."""
    out = apply(_open(path), mode, float(clarity))
    if fmt == "png":
        out.save(dst, "PNG", optimize=False)
    else:
        out.save(dst, "JPEG", quality=95, subsampling=0, optimize=True)
    return "ok"


def make_pdf(pages, dst, page_size="fit"):
    """pages = 'path|mode|clarity' lines joined by newline. One PDF, one page per picture, in order
    (core.save_pdf). page_size: fit | a4 | letter."""
    srcs = []
    for line in pages.split("\n"):
        if not line.strip():
            continue
        p, m, c = line.rsplit("|", 2)
        srcs.append((lambda p=p, m=m, c=c: apply(_open(p), m, float(c))))
    core.save_pdf(srcs, dst, page_size)
    return "ok"


def thumbs(path, prefix, clarity=60):
    """Four small previews of the page (Original / Color / Gray / B&W) for the look picker."""
    for m in ("orig", "color", "gray", "bw"):
        render(path, "%s_%s.jpg" % (prefix, m), m, clarity, 220)
    return "ok"
