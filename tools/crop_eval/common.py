import json, base64, io, numpy as np, cv2
from PIL import Image
def load():
    d=json.load(open('/mnt/user-data/uploads/learned_data.json'))
    out=[]
    for x in d:
        im=np.array(Image.open(io.BytesIO(base64.b64decode(x['image_thumb_jpg']))).convert('RGB'))
        gt=np.array(x['quad_norm'],float)
        out.append((im,gt,x))
    return out
def up(im,side):
    h,w=im.shape[:2]; s=side/max(h,w)
    return cv2.resize(im,(max(8,round(w*s)),max(8,round(h*s))),interpolation=cv2.INTER_CUBIC)
def iou(qa,qb,S=300):
    a=np.zeros((S,S),np.uint8); b=np.zeros((S,S),np.uint8)
    cv2.fillPoly(a,[np.round(np.array(qa)*(S-1)).astype(np.int32)],1)
    cv2.fillPoly(b,[np.round(np.array(qb)*(S-1)).astype(np.int32)],1)
    i=(a&b).sum(); u=(a|b).sum(); return i/max(1,u)
