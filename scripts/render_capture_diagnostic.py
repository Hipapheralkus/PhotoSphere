"""Render local alignment experiments; never writes capture originals."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from analyze_capture_alignment import load_session


def main(session, alignment, output, width, blend):
    frames=load_session(session);report=json.loads(alignment.read_text());report=report.get('native',report)
    rotations=[np.array(f['rotation']).reshape(3,3) for f in report['frames']]
    scale=report.get('focalScale',1)
    h=width//2;x=(np.arange(width)+.5)*2*np.pi/width-np.pi;y=np.pi/2-(np.arange(h)+.5)*np.pi/h
    directions=np.stack(np.broadcast_arrays(np.cos(y)[:,None]*np.sin(x),-np.sin(y)[:,None]+np.zeros(width),np.cos(y)[:,None]*np.cos(x)),axis=-1).astype(np.float32)
    total=np.zeros((h,width,3),np.float32);weight=np.zeros((h,width),np.float32)
    owner=np.full((h,width),-1,np.int16);best=np.zeros((h,width),np.float32)
    for i,(f,r) in enumerate(zip(frames,rotations)):
        image=cv2.imread(f['path'],cv2.IMREAD_REDUCED_COLOR_2)
        sh,sw=image.shape[:2];fx=sw/(2*np.tan(np.radians(f['hfov']/2)))*scale;fy=sh/(2*np.tan(np.radians(f['vfov']/2)))*scale
        camera=directions@r;z=camera[:,:,2];z=np.maximum(z,1e-6)
        u=(fx*camera[:,:,0]/z+(sw-1)/2).astype(np.float32);v=(fy*camera[:,:,1]/z+(sh-1)/2).astype(np.float32)
        valid=(camera[:,:,2]>0)&(u>=0)&(v>=0)&(u<sw-1)&(v<sh-1)
        edge=np.minimum(1-np.abs((u-(sw-1)/2)/(sw*.5)),1-np.abs((v-(sh-1)/2)/(sh*.5)))
        w=np.clip(edge/.25,0,1);w=w*w*(3-2*w)*np.maximum(camera[:,:,2],0)**2*valid
        sampled=cv2.remap(image,u,v,cv2.INTER_LINEAR)
        gain=report['frames'][i].get('exposureGain',1)
        if blend=='winner':
            w=(np.maximum(camera[:,:,2],0)**8)*np.clip(edge/.12,0,1)*valid
            changed=w>best;total[changed]=np.clip(sampled[changed]*gain,0,255);best[changed]=w[changed];owner[changed]=i
        else:
            total+=np.clip(sampled*gain,0,255)*w[:,:,None];weight+=w
        print('render',i,flush=True)
    if blend!='winner':total/=np.maximum(weight,1e-6)[:,:,None]
    cv2.imwrite(str(output),np.clip(total,0,255).astype(np.uint8),[cv2.IMWRITE_JPEG_QUALITY,94])
    if blend=='winner':np.save(output.with_suffix('.owner.npy'),owner)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('session',type=Path);p.add_argument('alignment',type=Path);p.add_argument('output',type=Path);p.add_argument('--width',type=int,default=2048);p.add_argument('--blend',default='feather');a=p.parse_args();main(a.session,a.alignment,a.output,a.width,a.blend)
