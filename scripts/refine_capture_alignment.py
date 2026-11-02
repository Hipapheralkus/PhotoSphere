"""Offline experiment over local match diagnostics; outputs are private scene data."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation
from scipy.sparse import lil_matrix


def main(directory, mode):
    d=np.load(directory/'matches.npz')
    n=len(d['priors']); counts=d['edgeN']; a=np.repeat(d['edgeA'],counts);b=np.repeat(d['edgeB'],counts)
    xyA=d['xyA'];xyB=d['xyB']; priors=d['priors']
    weights=np.repeat(np.sqrt(np.minimum(1,100/counts)),counts)
    features=d['features'];active=set(a)|set(b)
    def decode(x):
        delta=np.vstack([np.zeros((1,3)),x[:-1].reshape(n-1,3)])
        return Rotation.from_rotvec(delta).as_matrix()@priors, np.exp(x[-1]),delta
    def rays(xy,scale):
        v=np.column_stack([xy/scale,np.ones(len(xy))]);return v/np.linalg.norm(v,axis=1)[:,None]
    def residual(x):
        r,scale,delta=decode(x)
        wa=np.einsum('nij,nj->ni',r[a],rays(xyA,scale));wb=np.einsum('nij,nj->ni',r[b],rays(xyB,scale))
        penalty=delta[1:]*np.array([.35,.05,.35])
        return np.r_[((wa-wb)*weights[:,None]).ravel(),penalty.ravel(),x[-1]*.1]
    observations=len(a);rows=3*observations+3*(n-1)+1;cols=3*(n-1)+1
    jac=lil_matrix((rows,cols),dtype=int)
    for i,(aa,bb) in enumerate(zip(a,b)):
        for f in (aa,bb):
            if f:jac[3*i:3*i+3,3*(f-1):3*f]=1
        jac[3*i:3*i+3,-1]=1
    jac[3*observations:3*observations+3*(n-1),:-1]=np.eye(3*(n-1),dtype=int)
    jac[-1,-1]=1
    x=np.zeros(cols);x[-1]=np.log(1.03)
    bounds=np.tile(np.radians([5,25,5] if mode=='gravity' else [25,25,25]),n-1)
    for i in range(1,n):
        if i not in active: bounds[3*(i-1):3*i]=1e-8
    low=np.r_[-bounds,np.log(.85)];high=np.r_[bounds,np.log(1.20)]
    for scale in [.02,.007]:
        result=least_squares(residual,x,jac_sparsity=jac.tocsr(),bounds=(low,high),loss='soft_l1',f_scale=scale,
                             x_scale='jac',ftol=1e-5,xtol=1e-5,gtol=1e-5,max_nfev=180,verbose=1)
        x=result.x;print('stage',mode,scale,'nfev',result.nfev,'cost',result.cost,flush=True)
    r,focal,delta=decode(x)
    wa=np.einsum('nij,nj->ni',r[a],rays(xyA,focal));wb=np.einsum('nij,nj->ni',r[b],rays(xyB,focal))
    angles=np.degrees(np.arccos(np.clip(np.sum(wa*wb,axis=1),-1,1)))
    print('result',mode,'focal',focal,'mean/median/p90',np.mean(angles),np.median(angles),np.percentile(angles,90),'maxCorrection',np.degrees(np.max(np.linalg.norm(delta,axis=1))),flush=True)
    out={'mode':mode,'focalScale':float(focal),'meanResidualDegrees':float(np.mean(angles)), 'medianResidualDegrees':float(np.median(angles)),
         'p90ResidualDegrees':float(np.percentile(angles,90)), 'frames':[{'target':int(d['targets'][i]),'rotation':r[i].reshape(-1).tolist(),'correctionDegrees':np.degrees(delta[i]).tolist()} for i in range(n)]}
    (directory/f'alignment-{mode}.json').write_text(json.dumps(out,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--mode',default='gravity');a=p.parse_args();main(a.directory,a.mode)
