from baseline import *
import baseline as B

def quad_angles_dev(q):
    d=0
    for i in range(4):
        a=np.array(q[(i-1)%4])-np.array(q[i]); b=np.array(q[(i+1)%4])-np.array(q[i])
        c=abs(np.dot(a,b))/(np.linalg.norm(a)*np.linalg.norm(b)+1e-9); d=max(d,c)
    return d  # max |cos| of corner angle; 0 = perfect rectangle

def masks_v2(pic):
    h,w=pic.shape[:2]; P=pic.astype(np.float32)
    lum=0.299*P[...,0]+0.587*P[...,1]+0.114*P[...,2]
    lb=blurF(blurF(lum,2),2); u1=np.clip(lb,0,255).astype(int)
    def dist(c):
        d=np.sqrt(((P-np.array(c,np.float32))**2).sum(2)); d=blurF(blurF(d,2),2)
        return np.minimum(255,d*1.3).astype(int)
    m=max(2,round(min(h,w)*.03)); bm=np.zeros((h,w),bool);bm[:m]=1;bm[-m:]=1;bm[:,:m]=1;bm[:,-m:]=1
    cb=np.median(P[bm],axis=0)
    u2=dist(cb)
    y0,y1,x0,x1=round(h*.42),round(h*.58),round(w*.42),round(w*.58)
    cc=np.median(P[y0:y1,x0:x1].reshape(-1,3),axis=0)
    u4=dist(cc)
    t1=otsu(u1);t2=otsu(u2);t4=otsu(u4)
    M=[('u2',u2>t2,3),('u1u2',(u1>t1)&(u2>t2*.7),3),('u1',u1>t1,3),('u4',u4<t4,3),('u4s',u4<t4*.6,3)]
    nm=noiseMask(pic)
    if nm is not None: M.append(('noise',nm,1))
    # new generators
    bd=np.sort(blurF(blurF(np.sqrt(((P-cb)**2).sum(2)),1),1)[bm]); p98=bd[min(len(bd)-1,int(len(bd)*.98))]
    d1=blurF(blurF(np.sqrt(((P-cb)**2).sum(2)),1),1)
    for k,f in (('n2',2.6),('n3',4.0)): M.append((k,d1>max(18,f*p98),2))
    M.append(('u2lo',u2>t2*.5,3)); M.append(('u2hi',u2>t2*1.4,3))
    M.append(('u4m',u4<t4*1.4,3))
    # paper = bright & low saturation relative to border
    mx=P.max(2);mn=P.min(2); sat=(mx-mn)/(mx+1)
    M.append(('bright',(lb>np.percentile(lb,60))&(sat<np.percentile(sat,60)),3))
    # texture-free-ness: local gradient magnitude small + brighter than border
    gx=cv2.Sobel(lb,cv2.CV_32F,1,0);gy=cv2.Sobel(lb,cv2.CV_32F,0,1);g=blurF(np.hypot(gx,gy),3)
    M.append(('lowgrad',(g<np.percentile(g,45))&(u2>t2*.5),3))
    return M,P

def cands_v2(pic):
    h,w=pic.shape[:2]; M,P=masks_v2(pic)
    chb=[blurF(P[...,c],1) for c in range(3)]
    cands=[]
    for name,mk,rad in M:
        a=blurF(mk.astype(np.float32),rad); c1=a>.4
        a=blurF(c1.astype(np.float32),rad); c2=a>.6
        # take several largest blobs, not just one
        n,L,st,_=cv2.connectedComponentsWithStats(c2.astype(np.uint8),connectivity=4)
        order=np.argsort(-st[1:,cv2.CC_STAT_AREA])[:2]+1 if n>1 else []
        for oi,ci in enumerate(order):
            lab0=(L==ci); lab,cnt=fillHoles(lab0); frac=cnt/(w*h)
            if cnt<30 or frac<.0015 or frac>.985: continue
            er=cv2.erode(lab.astype(np.uint8),np.array([[0,1,0],[1,1,1],[0,1,0]],np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0)
            ys,xs=np.nonzero(lab&(er==0))
            if len(xs)<8: continue
            hull=cv2.convexHull(np.stack([xs+.5,ys+.5],1).astype(np.float32))[:,0,:]
            if len(hull)<4: continue
            qh=quadFromHull(hull)
            if len(qh)!=4: continue
            q=orderQuad(qh); area=max(1,polyArea(q)); fill=cnt/area
            if fill<.7 or fill>1.25: continue
            ys2,xs2=np.nonzero(lab); cx=xs2.mean();cy=ys2.mean()
            centre=1-.5*math.hypot(cx-w/2,cy-h/2)/math.hypot(w/2,h/2)
            con=sideContrast(chb,q)
            # extra features
            qa=np.array(q); 
            touch=sum(1 for x,y in q if x<2 or y<2 or x>w-3 or y>h-3)
            mask=np.zeros((h,w),np.uint8); cv2.fillPoly(mask,[np.round(qa).astype(np.int32)],1); inm=mask>0
            outm=~inm
            lum=P.mean(2)
            gi=lum[inm].mean() if inm.any() else 0; go=lum[outm].mean() if outm.any() else 0
            ci_=P[inm].mean(0) if inm.any() else np.zeros(3); co_=P[outm].mean(0) if outm.any() else np.zeros(3)
            sides=[math.hypot(q[(i+1)%4][0]-q[i][0],q[(i+1)%4][1]-q[i][1]) for i in range(4)]
            asp=min(sides[0]+sides[2],sides[1]+sides[3])/max(sides[0]+sides[2],sides[1]+sides[3])
            cands.append(dict(q=q,fill=fill,frac=frac,con=con,centre=centre,name=name,rank=oi,
                touch=touch,orth=quad_angles_dev(q),chd=float(np.linalg.norm(ci_-co_)),lumd=gi-go,asp=asp,
                score=con*math.sqrt(frac)*math.sqrt(centre)*min(1,fill)))
    return cands
if __name__=='__main__':
    data=load(); orc=[];base=[]
    for i,(im,gt,x) in enumerate(data):
        small=up(im,512); H,W=small.shape[:2]
        cs=cands_v2(small)
        ious=[iou([(a/W,b/H) for a,b in c['q']],gt) for c in cs]
        o=max(ious) if ious else 0; orc.append(o)
        pk=max(range(len(cs)),key=lambda k:cs[k]['score']) if cs else -1
        base.append(ious[pk] if cs else 0)
        print(i,len(cs),round(o,2),round(base[-1],2))
    print('oracle',np.mean(orc).round(3),'baseline pick',np.mean(base).round(3),'oracle>0.9',(np.array(orc)>.9).sum())
