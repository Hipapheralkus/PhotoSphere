"""Local-only visual alignment diagnostics; never modifies original captures.

Usage: python scripts/analyze_capture_alignment.py SESSION OUTPUT_DIRECTORY
Requires OpenCV, NumPy and SciPy. Outputs contain scene-derived private data and
belong in ignored artifacts/device/, not source control.
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np


def pose_rotation(yaw, pitch, roll):
    y, p, r = np.radians([yaw, pitch, roll])
    return np.array([[np.cos(y), 0, np.sin(y)], [0, 1, 0], [-np.sin(y), 0, np.cos(y)]]) @ np.array(
        [[1, 0, 0], [0, np.cos(p), -np.sin(p)], [0, np.sin(p), np.cos(p)]]) @ np.array(
        [[np.cos(r), -np.sin(r), 0], [np.sin(r), np.cos(r), 0], [0, 0, 1]])


def fit_rotation(a, b):
    u, _, vt = np.linalg.svd(a.T @ b)
    if np.linalg.det(u @ vt) < 0:
        u[:, -1] *= -1
    return u @ vt


def angular(a, b):
    return np.degrees(np.arccos(np.clip(np.sum(a*b, axis=-1), -1, 1)))


def rotation_error(a, b):
    return math.degrees(math.acos(np.clip((np.trace(a.T @ b)-1)/2, -1, 1)))


def load_session(session):
    manifest = json.loads((session / 'manifest.json').read_text())
    latest = {c['targetId']: c for c in manifest['captures'] if c.get('accepted', True)}
    records = list(latest.values())
    frames = []
    for c in records:
        pose, meta = c['pose'], c['capture']['cameraMetadata']
        h, v = manifest['camera']['horizontalFovDegrees'], manifest['camera']['verticalFovDegrees']
        if meta.get('displayRotationDegreesAtCapture', 0) % 180:
            h, v = v, h
        frames.append(dict(path=str(session / c['file']), target=c['targetId'], hfov=h, vfov=v,
                           prior=pose_rotation(pose['yawDegrees'], pose['pitchDegrees'], pose['rollDegrees'])))
    return frames


def prepare(session, output):
    frames = load_session(session)
    cv2.setNumThreads(3)
    sift = cv2.SIFT_create(nfeatures=2400, contrastThreshold=.025)
    for i, f in enumerate(frames):
        image = cv2.imread(f['path'], cv2.IMREAD_REDUCED_COLOR_2)
        scale = min(1, 1280/max(image.shape[:2]))
        image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        h, w = image.shape[:2]
        kp, desc = sift.detectAndCompute(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), None)
        xy = np.array([k.pt for k in kp]).reshape(-1, 2)
        focal = np.array([w/(2*np.tan(np.radians(f['hfov']/2))), h/(2*np.tan(np.radians(f['vfov']/2)))])
        xy = (xy - np.array([(w-1)/2, (h-1)/2]))/focal
        rays = np.column_stack([xy, np.ones(len(xy))])
        rays /= np.linalg.norm(rays, axis=1)[:, None]
        f.update(xy=xy, rays=rays, desc=desc, shape=(h, w))
        print('features',i,f['target'],len(kp),flush=True)
    neighbours=[[] for _ in frames]
    for a, fa in enumerate(frames):
        for b in range(a+1,len(frames)):
            fb=frames[b]
            distance=angular(fa['prior'][:,2],fb['prior'][:,2])
            radius=lambda f: math.degrees(math.atan(math.hypot(math.tan(math.radians(f['hfov']/2)), math.tan(math.radians(f['vfov']/2)))))
            if distance < radius(fa)+radius(fb):
                neighbours[a].append((distance,b));neighbours[b].append((distance,a))
    pairs=sorted(set(tuple(sorted((i,b))) for i,ns in enumerate(neighbours) for _,b in sorted(ns)[:10]))
    matcher=cv2.FlannBasedMatcher(dict(algorithm=1,trees=4),dict(checks=64))
    edges=[]
    rng=np.random.default_rng(73)
    for pair_index,(a,b) in enumerate(pairs):
        fa,fb=frames[a],frames[b]
        if len(fa['rays'])<12 or len(fb['rays'])<12: continue
        ab=matcher.knnMatch(fa['desc'],fb['desc'],k=2)
        ba=matcher.knnMatch(fb['desc'],fa['desc'],k=2)
        indices=[]
        for m,n in ab:
            back,second=ba[m.trainIdx]
            if m.distance < .75*n.distance and back.distance < .75*second.distance and back.trainIdx == m.queryIdx:
                if angular(fa['prior']@fa['rays'][m.queryIdx],fb['prior']@fb['rays'][m.trainIdx])<15:
                    indices.append((m.queryIdx,m.trainIdx))
        if len(indices)<12: continue
        indices=np.array(indices)
        ar,br=fa['rays'][indices[:,0]],fb['rays'][indices[:,1]]
        best=np.zeros(len(ar),dtype=bool)
        for _ in range(250):
            subset=rng.choice(len(ar),3,replace=False)
            r=fit_rotation(ar[subset],br[subset])
            mask=np.sum(ar*(br@r.T),axis=1)>math.cos(math.radians(.6))
            if mask.sum()>best.sum(): best=mask
        if best.sum()<12 or best.sum()<.25*len(ar):continue
        for _ in range(3):
            r=fit_rotation(ar[best],br[best]);best=np.sum(ar*(br@r.T),axis=1)>math.cos(math.radians(.6))
        error=rotation_error(fa['prior'].T@fb['prior'],r)
        if best.sum()<12 or error>15:continue
        selected=indices[best]
        edges.append(dict(a=a,b=b,targets=[fa['target'],fb['target']],count=int(best.sum()),priorError=error,
                          medianError=float(np.median(angular(ar[best],br[best]@r.T))),rotation=r,
                          xyA=fa['xy'][selected[:,0]],xyB=fb['xy'][selected[:,1]]))
        print('edge',pair_index,edges[-1]['targets'],int(best.sum()),round(error,3),flush=True)
    payload=dict(priors=np.stack([f['prior'] for f in frames]),targets=np.array([f['target'] for f in frames]),
                 features=np.array([len(f['rays']) for f in frames]),edgeA=np.array([e['a'] for e in edges]),
                 edgeB=np.array([e['b'] for e in edges]),edgeR=np.stack([e['rotation'] for e in edges]),
                 edgeN=np.array([e['count'] for e in edges]),edgePriorError=np.array([e['priorError'] for e in edges]),
                 xyA=np.concatenate([e['xyA'] for e in edges]),xyB=np.concatenate([e['xyB'] for e in edges]))
    np.savez_compressed(output/'matches.npz',**payload)
    summary=[{k:v for k,v in e.items() if k not in ['rotation','xyA','xyB']} for e in edges]
    (output/'matches-summary.json').write_text(json.dumps(summary,indent=2))
    print('saved',len(edges),'edges',sum(e['count'] for e in edges),'observations',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('session',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    prepare(args.session,args.output)
