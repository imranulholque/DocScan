import pickle, numpy as np, math, sys
import v2, baseline as B, rank2
from v2 import *
DROP={'bright','n2','n3','lowgrad'}
data=load(); C=pickle.load(open('cands.pkl','rb'))
def keep(cs): return [c for c in cs if c['name'] not in DROP]
idx=[i for i in range(len(C)) if keep(C[i])]
X={i:np.array([rank2.F(c) for c in keep(C[i])]) for i in idx}; Y={i:np.array([c['iou'] for c in keep(C[i])]) for i in idx}
rank2.X=X; rank2.Y=Y
# LOO weights
loo={}
for i in idx: loo[i]=rank2.train([j for j in idx if j!=i],1,.03,150)
W=rank2.train(idx,1,.03,300); np.set_printoptions(precision=3,suppress=True); print('final weights',W)
def run(inset,rounds=3,use_loo=True):
    r=[]
    for i,(im,gt,x) in enumerate(data):
        if i not in idx: r.append(0); continue
        small=up(im,512); fine=up(im,1000); H,W_=fine.shape[:2]; k=fine.shape[1]/small.shape[1]
        cs=keep(C[i]); w=loo[i] if use_loo else W
        best=cs[int(np.argmax(X[i]@w))]
        q=[(a*k,b*k) for a,b in best['q']]
        rq,m=refineQuad(fine,q,rounds)
        if m>0:q=rq
        q=orderQuad(q); cx=sum(p[0] for p in q)/4;cy=sum(p[1] for p in q)/4
        q=[(cx+(x_-cx)*inset,cy+(y_-cy)*inset) for x_,y_ in q]
        r.append(iou([(a/W_,b/H) for a,b in q],gt))
    return np.array(r)
for ins in (.97,.98,.99,.995,1.0,1.01):
    r=run(ins); print('inset',ins,'mean',r.mean().round(4),'>0.9',(r>.9).sum(),'<0.5',(r<.5).sum())
r0=run(.995,0); print('no refine',r0.mean().round(4))
