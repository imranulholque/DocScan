"""Re-train the page finder's ranker from the "learned data" the app / PC program collects (learned_data.json or learned_data.py).

    python tools/finder_train/train_finder.py learned_data.json [--photos DIR] [--write]

Every sample holds a small copy of the photo and the 4 corners the person finally chose. For each sample the finder builds its outline
candidates, and the ranker (a small linear model) learns which candidate the person would pick. --photos DIR: full-size photos
(any file name; they are matched to the samples by their look) give sharper training pictures than the 128 px thumbnails.
Prints the honest leave-one-out score (the model never sees the sample it is scored on). --write puts the new weights into
app/src/main/python/finder.py (WEIGHTS)."""
import sys, os, json, re, io, base64, glob, argparse
import numpy as np, cv2
from PIL import Image, ImageOps
HERE = os.path.dirname(os.path.abspath(__file__))
PY = os.path.join(HERE, "..", "..", "app", "src", "main", "python")
sys.path.insert(0, PY)
import finder


def load_samples(path):
    txt = open(path, encoding="utf-8").read()
    if path.endswith(".py"):
        txt = txt[txt.index("r'''") + 4: txt.rindex("'''")]
    return json.loads(txt)


def iou(qa, qb, S=300):
    a = np.zeros((S, S), np.uint8); b = np.zeros((S, S), np.uint8)
    cv2.fillPoly(a, [np.round(np.array(qa) * (S - 1)).astype(np.int32)], 1)
    cv2.fillPoly(b, [np.round(np.array(qb) * (S - 1)).astype(np.int32)], 1)
    return (a & b).sum() / max(1, (a | b).sum())


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("data"); ap.add_argument("--photos"); ap.add_argument("--write", action="store_true")
    ap.add_argument("--lam", type=float, default=0.01); ap.add_argument("--temp", type=float, default=0.03)
    a = ap.parse_args()
    samples = load_samples(a.data)
    thumbs = [Image.open(io.BytesIO(base64.b64decode(s["image_thumb_jpg"]))).convert("RGB") for s in samples]
    photos = {}
    if a.photos:
        small = [np.asarray(t.resize((32, 32)).convert("L"), float) for t in thumbs]
        for f in sorted(glob.glob(os.path.join(a.photos, "*"))):
            try:
                im = ImageOps.exif_transpose(Image.open(f)).convert("RGB")
            except Exception:
                continue
            g = np.asarray(im.resize((32, 32)).convert("L"), float)
            sc = [np.corrcoef(g.ravel(), t.ravel())[0, 1] for t in small]; j = int(np.argmax(sc))
            if sc[j] > 0.98: photos[j] = im
    D = []
    for i, s in enumerate(samples):
        src = photos.get(i, thumbs[i]); k = finder.S_WORK / max(src.size)
        sm = np.asarray(src.resize((round(src.width * k), round(src.height * k)), Image.Resampling.BILINEAR))
        cands, F, _ = finder.prepare(sm)
        h, w = sm.shape[:2]; gt = np.array(s["quad_norm"], float)
        D.append((F, np.array([iou(q / [w, h], gt) for _, q in cands])))
    allF = np.concatenate([d[0] for d in D]); mu = allF.mean(0); sd = allF.std(0) + 1e-6
    X = [(d[0] - mu) / sd for d in D]; Y = [d[1] for d in D]

    def train(ids, iters=300, lr=0.2):
        w = np.zeros(X[0].shape[1])
        for _ in range(iters):
            g = np.zeros_like(w)
            for i in ids:
                sc = X[i] @ w; p = np.exp(sc - sc.max()); p /= p.sum()
                t = np.exp((Y[i] - Y[i].max()) / a.temp); t /= t.sum()
                g += X[i].T @ (p - t)
            w -= lr * (g / len(ids) + a.lam * w)
        return w
    idx = list(range(len(D)))
    loo = np.array([Y[i][int(np.argmax(X[i] @ train([j for j in idx if j != i], 120)))] for i in idx])
    print("samples %d | oracle %.3f | leave-one-out mean IoU %.3f | >0.9: %d | <0.5: %d" %
          (len(D), np.mean([y.max() for y in Y]), loo.mean(), (loo > .9).sum(), (loo < .5).sum()))
    w = train(idx, 400)
    if a.write:
        p = os.path.join(PY, "finder.py"); t = open(p, encoding="utf-8").read()
        new = "WEIGHTS = " + json.dumps({"mu": [round(float(v), 5) for v in mu], "sd": [round(float(v), 5) for v in sd], "w": [round(float(v), 5) for v in w]})
        t = re.sub(r"^WEIGHTS = \{.*\}$", lambda m: new, t, flags=re.M)
        open(p, "w", encoding="utf-8").write(t); print("weights written to", p)


if __name__ == "__main__":
    main()
