# Faithful numpy replica of PageFinder.kt (coarse candidates + refine)
import numpy as np, cv2, math
from common import *

def blurF(a,r): return cv2.blur(a.astype(np.float32),(2*r+1,2*r+1),borderType=cv2.BORDER_REPLICATE)
def otsu(v):
    hist=np.bincount(v.ravel(),minlength=256).astype(float); n=hist.sum()
    s=(np.arange(256)*hist).sum(); wB=0;sB=0;best=-1;th=127
    for t in range(256):
        wB+=hist[t]
        if wB==0: continue
        wF=n-wB
        if wF==0: break
        sB+=t*hist[t]; mB=sB/wB; mF=(s-sB)/wF; q=wB*wF*(mB-mF)**2
        if q>best: best=q;th=t
    return th
def polyArea(q):
    q=np.array(q); x=q[:,0];y=q[:,1]; return abs(np.dot(x,np.roll(y,-1))-np.dot(y,np.roll(x,-1)))/2
def lineX(p0,p1,p2,p3):
    d1=(p1[0]-p0[0],p1[1]-p0[1]); d2=(p3[0]-p2[0],p3[1]-p2[1])
    den=d1[0]*d2[1]-d1[1]*d2[0]
    if abs(den)<1e-9: return None
    t=((p2[0]-p0[0])*d2[1]-(p2[1]-p0[1])*d2[0])/den
    s=((p2[0]-p0[0])*d1[1]-(p2[1]-p0[1])*d1[0])/den
    return (p0[0]+t*d1[0],p0[1]+t*d1[1],t,s)
def quadFromHull(h):
    p=[tuple(x) for x in h]
    while len(p)>4:
        n=len(p); bi=-1; ba=1e30; bx=None
        for i in range(n):
            p0=p[(i-1)%n];p1=p[i];p2=p[(i+1)%n];p3=p[(i+2)%n]
            x=lineX(p0,p1,p3,p2)
            if x is None: continue
            if x[2]<=1 or x[3]<=1: continue
            a=abs((p1[0]-x[0])*(p2[1]-x[1])-(p1[1]-x[1])*(p2[0]-x[0]))/2
            if a<ba: ba=a;bi=i;bx=(x[0],x[1])
        if bi<0:
            m=1e30;mi=0
            for i in range(n):
                a=polyArea([p[(i-1)%n],p[i],p[(i+1)%n]])
                if a<m:m=a;mi=i
            p.pop(mi); continue
        j=(bi+1)%n; p[bi]=bx; p.pop(j) if j>bi else p.pop(0) if False else p.pop(j)
    return p
def orderQuad(q):
    q=[tuple(x) for x in q]
    s=[x+y for x,y in q]; d=[x-y for x,y in q]
    tl=br=tr=bl=0
    for i in range(1,4):
        if s[i]<s[tl]: tl=i
        if s[i]>s[br]: br=i
        if d[i]>d[tr]: tr=i
        if d[i]<d[bl]: bl=i
    if len({tl,br,tr,bl})<4:
        cx=sum(x for x,_ in q)/4;cy=sum(y for _,y in q)/4
        o=sorted(q,key=lambda p:math.atan2(p[1]-cy,p[0]-cx))
        k=0
        for i in range(1,4):
            if o[i][0]+o[i][1]<o[k][0]+o[k][1]: k=i
        return [o[k],o[(k+1)%4],o[(k+2)%4],o[(k+3)%4]]
    return [q[tl],q[tr],q[br],q[bl]]
def smp(a,x,y):
    h,w=a.shape
    x=min(max(x,0),w-1.001); y=min(max(y,0),h-1.001)
    xi=int(x);yi=int(y);fx=x-xi;fy=y-yi
    return (a[yi,xi]*(1-fx)+a[yi,xi+1]*fx)*(1-fy)+(a[yi+1,xi]*(1-fx)+a[yi+1,xi+1]*fx)*fy
def sideContrast(chb,q):
    h,w=chb[0].shape; cx=sum(p[0] for p in q)/4;cy=sum(p[1] for p in q)/4
    off=max(2.5,0.009*max(w,h)); sides=[]
    for i in range(4):
        a=q[i];b=q[(i+1)%4]; ln=math.hypot(b[0]-a[0],b[1]-a[1]) or 1
        ux=(b[0]-a[0])/ln;uy=(b[1]-a[1])/ln; nx=uy;ny=-ux
        if nx*((a[0]+b[0])/2-cx)+ny*((a[1]+b[1])/2-cy)<0: nx=-nx;ny=-ny
        k=24; e=[]
        for j in range(k):
            s=0.1+0.8*(j+.5)/k; px=a[0]+ux*s*ln; py=a[1]+uy*s*ln; best=0
            for sh in (-1.5,0,1.5):
                ee=0
                for c in range(3):
                    df=smp(chb[c],px+nx*(off+sh),py+ny*(off+sh))-smp(chb[c],px-nx*(off-sh),py-ny*(off-sh))
                    ee+=df*df
                best=max(best,math.sqrt(ee))
            e.append(best)
        e.sort(); sides.append(e[len(e)>>1])
    return 0.5*(sum(sides)/4)+0.5*min(sides)
def fillHoles(lab):
    h,w=lab.shape
    inv=(~lab).astype(np.uint8)
    n,L=cv2.connectedComponents(inv,connectivity=4)
    border=set(np.unique(np.concatenate([L[0],L[-1],L[:,0],L[:,-1]])))
    outside=np.isin(L,[b for b in border if b!=0]) & (inv>0)
    filled=~outside
    return filled, int(filled.sum())
def bigBlob(mask):
    n,L,st,_=cv2.connectedComponentsWithStats(mask.astype(np.uint8),connectivity=4)
    if n<=1: return None
    i=1+np.argmax(st[1:,cv2.CC_STAT_AREA]); return L==i
def noiseMask(pic):
    h,w=pic.shape[:2]; m=max(2,round(min(h,w)*.04))
    if m*2>=min(h,w): return None
    P=pic.astype(np.float32)
    bm=np.zeros((h,w),bool); bm[:m]=1;bm[-m:]=1;bm[:,:m]=1;bm[:,-m:]=1
    c=np.median(P[bm],axis=0)
    d0=np.sqrt(((P-c)**2).sum(2)); d=blurF(blurF(d0,1),1)
    p98=np.sort(d[bm])[min(bm.sum()-1,int(bm.sum()*.98))]
    return d>max(14,1.6*p98)
def coarseCands(pic):
    h,w=pic.shape[:2]; P=pic.astype(np.float32)
    lum=0.299*P[...,0]+0.587*P[...,1]+0.114*P[...,2]
    lb=blurF(blurF(lum,2),2); u1=np.clip(lb,0,255).astype(int)
    chb=[blurF(P[...,c],1) for c in range(3)]
    def dist(c):
        d=np.sqrt(((P-np.array(c,np.float32))**2).sum(2)); d=blurF(blurF(d,2),2)
        return np.minimum(255,d*1.3).astype(int)
    m=max(2,round(min(h,w)*.03)); bm=np.zeros((h,w),bool);bm[:m]=1;bm[-m:]=1;bm[:,:m]=1;bm[:,-m:]=1
    u2=dist(P[bm].mean(0))
    y0,y1,x0,x1=round(h*.42),round(h*.58),round(w*.42),round(w*.58)
    u4=dist(np.median(P[y0:y1,x0:x1].reshape(-1,3),axis=0))
    t1=otsu(u1);t2=otsu(u2);t4=otsu(u4)
    masks=[(u2>t2,3),((u1>t1)&(u2>t2*.7),3),(u1>t1,3),(u4<t4,3),(u4<t4*.6,3)]
    nm=noiseMask(pic)
    if nm is not None: masks.append((nm,1))
    cands=[]
    for mi,(mk,rad) in enumerate(masks):
        a=blurF(mk.astype(np.float32),rad); c1=a>.4
        a=blurF(c1.astype(np.float32),rad); c2=a>.6
        blob=bigBlob(c2)
        if blob is None: continue
        lab,cnt=fillHoles(blob); frac=cnt/(w*h)
        if cnt<30 or frac<.0015 or frac>.985: continue
        # edge pixels
        er=cv2.erode(lab.astype(np.uint8),np.ones((3,3),np.uint8)[[1],:]*0+np.array([[0,1,0],[1,1,1],[0,1,0]],np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0)
        edge=lab&(er==0)
        ys,xs=np.nonzero(edge)
        if len(xs)<8: continue
        pts=np.stack([xs+.5,ys+.5],1).astype(np.float32)
        hull=cv2.convexHull(pts)[:,0,:]
        if len(hull)<4: continue
        qh=quadFromHull(hull)
        if len(qh)!=4: continue
        q=orderQuad(qh); area=max(1,polyArea(q)); fill=cnt/area
        ys2,xs2=np.nonzero(lab); cx=xs2.mean();cy=ys2.mean()
        if fill<.7 or fill>1.25: continue
        centre=1-.5*math.hypot(cx-w/2,cy-h/2)/math.hypot(w/2,h/2)
        con=sideContrast(chb,q)
        cands.append(dict(q=q,fill=fill,frac=frac,con=con,centre=centre,mask=mi,score=con*math.sqrt(frac)*math.sqrt(centre)*min(1,fill)))
    return cands
def refineQuad(pic,q0,rounds):
    h,w=pic.shape[:2]; P=pic.astype(np.float32)
    ch=[blurF(P[...,c],1) for c in range(3)]
    q=[tuple(p) for p in q0]; band=max(6,.03*max(w,h))
    minSide=min(math.hypot(q0[(i+1)%4][0]-q0[i][0],q0[(i+1)%4][1]-q0[i][1]) for i in range(4))
    band=max(2.5,min(band,.2*minSide)); moved=0
    for _ in range(rounds):
        cx=sum(p[0] for p in q)/4;cy=sum(p[1] for p in q)/4; lines=[]
        for i in range(4):
            a=q[i];b=q[(i+1)%4]; ln=math.hypot(b[0]-a[0],b[1]-a[1]) or 1
            ux=(b[0]-a[0])/ln;uy=(b[1]-a[1])/ln;nx=uy;ny=-ux
            if nx*((a[0]+b[0])/2-cx)+ny*((a[1]+b[1])/2-cy)<0:nx=-nx;ny=-ny
            kk=max(12,min(90,round(ln/5))); pp=[];ee=[]
            for k in range(kk):
                s=.06+.88*(k+.5)/kk; px=a[0]+ux*s*ln;py=a[1]+uy*s*ln
                prof=[];ts=[];mxv=0;t=-band
                while t<=band:
                    e=0
                    for c in range(3):
                        df=smp(ch[c],px+nx*(t+1.5),py+ny*(t+1.5))-smp(ch[c],px+nx*(t-1.5),py+ny*(t-1.5)); e+=df*df
                    e=math.sqrt(e); prof.append(e);ts.append(t); mxv=max(mxv,e); t+=.5
                bi=-1
                for j in range(len(prof)-2,0,-1):
                    if prof[j]>=.55*mxv and prof[j]>=prof[j-1] and prof[j]>=prof[j+1]: bi=j;break
                if bi<0: bi=int(np.argmax(prof))
                bt=ts[bi]; pp.append((px+nx*bt,py+ny*bt)); ee.append(mxv)
            med=sorted(ee)[len(ee)>>1]
            use=[p for k,p in enumerate(pp) if ee[k]>=max(5,.5*med)]; lnr=None
            if len(use)>=max(6,kk*.35):
                for it in range(4):
                    mx=sum(p[0] for p in use)/len(use);my=sum(p[1] for p in use)/len(use)
                    sxx=sxy=syy=0
                    for p in use: dx=p[0]-mx;dy=p[1]-my;sxx+=dx*dx;sxy+=dx*dy;syy+=dy*dy
                    th=.5*math.atan2(2*sxy,sxx-syy); dx=math.cos(th);dy=math.sin(th)
                    res=[abs((p[0]-mx)*(-dy)+(p[1]-my)*dx) for p in use]
                    mr=sorted(res)[len(res)>>1]; lnr=(mx,my,dx,dy,len(use)); lim=max(1,2.5*mr)
                    keep=[p for k,p in enumerate(use) if res[k]<=lim]
                    if len(keep)<max(6,kk*.3) or len(keep)==len(use): break
                    use=keep
                if lnr and lnr[4]<max(6,kk*.3): lnr=None
            lines.append(lnr or (a[0],a[1],ux,uy,0))
        nq=[];ok=True
        for i in range(4):
            l1=lines[(i+3)%4];l2=lines[i]
            x=lineX((l1[0],l1[1]),(l1[0]+l1[2],l1[1]+l1[3]),(l2[0],l2[1]),(l2[0]+l2[2],l2[1]+l2[3]))
            if x is None or math.hypot(x[0]-q[i][0],x[1]-q[i][1])>band*2.2+.02*max(w,h): ok=False;break
            nq.append((x[0],x[1]))
        if not ok or polyArea(nq)<.6*polyArea(q): break
        q=nq;moved+=1;band=max(2.5,band*.4)
    return q,moved
def findPaper(fine,small,rounds=3,pick=None):
    cs=coarseCands(small); W=fine.shape[1];H=fine.shape[0]
    if not cs: return None,False,cs
    best=max(cs,key=lambda c:c['score']) if pick is None else pick(cs)
    k=fine.shape[1]/small.shape[1]; q=[(x*k,y*k) for x,y in best['q']]
    found=best['fill']>.82 and (best['frac']>.15 or best['con']>25)
    try:
        r,m=refineQuad(fine,q,rounds)
        if m>0:q=r
    except Exception: pass
    q=orderQuad(q); q=[(min(max(x,0),W),min(max(y,0),H)) for x,y in q]
    return q,found,cs
def detect(im,fineS=1000,smallS=560,inset=.995):
    fine=up(im,fineS); small=up(im,smallS)
    q,found,cs=findPaper(fine,small)
    H,W=fine.shape[:2]
    if q is None:
        mm=.04; q=[(mm*W,mm*H),((1-mm)*W,mm*H),((1-mm)*W,(1-mm)*H),(mm*W,(1-mm)*H)]; found=False
    cx=sum(p[0] for p in q)/4;cy=sum(p[1] for p in q)/4
    q=[(cx+(x-cx)*inset,cy+(y-cy)*inset) for x,y in q]
    return np.array([(x/W,y/H) for x,y in q]),found
if __name__=='__main__':
    data=load(); res=[]
    for i,(im,gt,x) in enumerate(data):
        q,f=detect(im,512,512)
        res.append(iou(q,gt)); print(i,round(res[-1],3),f)
    r=np.array(res); print('mean IoU',r.mean().round(3),'median',np.median(r).round(3),'>0.9:',(r>.9).sum(),'>0.8:',(r>.8).sum(),'<0.5:',(r<.5).sum())
