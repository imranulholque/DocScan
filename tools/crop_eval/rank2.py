import pickle, numpy as np, math, itertools
C=pickle.load(open('cands.pkl','rb'))
idx=[i for i in range(len(C)) if len(C[i])]
def F(c):
    return np.array([math.log(c['con']+1), math.log(c['frac']), math.log(c['centre']), math.log(min(1,c['fill'])+1e-3), c['touch'], c['orth'], c['rank'], math.log(c['chd']+1)])
X={i:np.array([F(c) for c in C[i]]) for i in idx}; Y={i:np.array([c['iou'] for c in C[i]]) for i in idx}
def sel(w,i): return Y[i][int(np.argmax(X[i]@w))]
# listwise softmax: target distribution ~ exp(iou/T)
def train(ids,lam=1.0,T=0.05,iters=400,lr=.05,nf=8):
    w=np.zeros(nf); w[:3]=[1,.5,.5]
    for _ in range(iters):
        g=np.zeros(nf)
        for i in ids:
            s=X[i]@w; p=np.exp(s-s.max());p/=p.sum()
            t=np.exp((Y[i]-Y[i].max())/T);t/=t.sum()
            g+=X[i].T@(p-t)
        w-=lr*(g/len(ids)+lam*0.01*w)
    return w
for lam in (0.1,1,10):
  for T in (.03,.1):
    r=[]
    for i in idx:
        w=train([j for j in idx if j!=i],lam,T,150); r.append(sel(w,i))
    r=np.array(r); print('listwise lam',lam,'T',T,'LOO',r.mean().round(3),(r>.9).sum(),(r<.5).sum())
# hand score exponent grid, with 2-fold style stability: report train mean on all
best=[]
for a,b,c_,d in itertools.product([.5,1,1.5],[0,.25,.5,1],[0,.25,.5,1],[0,1,2]):
    w=np.array([a,b,c_,d,0,0,0,0.]); 
    best.append((np.mean([sel(w,i) for i in idx]),(a,b,c_,d)))
best.sort(reverse=True); print(best[:6]); 
w0=np.array([1,.5,.5,1,0,0,0,0.]); print('orig-form',np.mean([sel(w0,i) for i in idx]))
