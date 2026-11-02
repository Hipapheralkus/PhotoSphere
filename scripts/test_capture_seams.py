"""Private local seam experiment using real pixels and no image synthesis."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from analyze_capture_alignment import load_session


def main(session, alignment, output, ownership_ratio):
    cv2.setNumThreads(3)
    frames=load_session(session);rpt=json.loads(alignment.read_text());rpt=rpt.get('native',rpt)
    width=768;height=width//2;padding=128;canvas_width=width+2*padding
    x=(np.arange(canvas_width)-padding+.5)*2*np.pi/width-np.pi;y=np.pi/2-(np.arange(height)+.5)*np.pi/height
    directions=np.stack(np.broadcast_arrays(np.cos(y)[:,None]*np.sin(x),-np.sin(y)[:,None]+np.zeros(canvas_width),np.cos(y)[:,None]*np.cos(x)),axis=-1).astype(np.float32)
    images=[];masks=[];corners=[];rotations=[];scores=[];best_score=np.zeros((height,canvas_width),np.float32);focal=rpt.get('focalScale',1)
    for i,f in enumerate(frames):
        r=np.array(rpt['frames'][i]['rotation']).reshape(3,3);rotations.append(r)
        im=cv2.imread(f['path'],cv2.IMREAD_REDUCED_COLOR_4);sh,sw=im.shape[:2]
        fx=sw/(2*np.tan(np.radians(f['hfov']/2)))*focal;fy=sh/(2*np.tan(np.radians(f['vfov']/2)))*focal
        camera=directions@r;z=np.maximum(camera[:,:,2],1e-6)
        u=(fx*camera[:,:,0]/z+(sw-1)/2).astype(np.float32);v=(fy*camera[:,:,1]/z+(sh-1)/2).astype(np.float32)
        valid=((camera[:,:,2]>0)&(u>=0)&(v>=0)&(u<sw-1)&(v<sh-1)).astype(np.uint8)*255
        warped=cv2.remap(im,u,v,cv2.INTER_LINEAR)
        edge=np.minimum(1-np.abs((u-(sw-1)/2)/(sw*.5)),1-np.abs((v-(sh-1)/2)/(sh*.5)))
        score=(np.maximum(camera[:,:,2],0)**8)*np.clip(edge/.12,0,1)*(valid/255)
        best_score=np.maximum(best_score,score)
        xx,yy,ww,hh=cv2.boundingRect(valid);x0=max(0,xx-2);y0=max(0,yy-2);x1=min(canvas_width,xx+ww+2);y1=min(height,yy+hh+2)
        images.append(warped[y0:y1,x0:x1].copy());masks.append(valid[y0:y1,x0:x1].copy());corners.append((x0,y0))
        scores.append(score[y0:y1,x0:x1].copy())
    print('roi pixels',sum(i.shape[0]*i.shape[1] for i in images),flush=True)
    raw=[i.copy() for i in images]
    compensator=cv2.detail_BlocksChannelsCompensator(32,32,2)
    compensator.feed(corners,images,masks)
    gains=[]
    for i in range(len(images)):
        images[i]=compensator.apply(i,corners[i],images[i],masks[i]);gains.append(compensator.getMatGains()[i])
    restricted=[]
    for i,m in enumerate(masks):
        x0,y0=corners[i];h,w=m.shape
        restricted.append(m*((scores[i]>=ownership_ratio*best_score[y0:y0+h,x0:x0+w]).astype(np.uint8)))
    umasks=[cv2.UMat(m) for m in restricted]
    seam=cv2.detail_GraphCutSeamFinder('COST_COLOR_GRAD');seam.find([i.astype(np.float32) for i in images],corners,umasks)
    seam_masks=[u.get() for u in umasks]
    np.savez_compressed(output.with_suffix('.npz'),**{f'gain{i}':g for i,g in enumerate(gains)},**{f'mask{i}':m for i,m in enumerate(seam_masks)},corners=np.array(corners))
    # Reproject from originals at2048px, applying each low-resolution seam and gain field.
    w=2048;h=w//2;xx=(np.arange(w)+.5)*2*np.pi/w-np.pi;yy=np.pi/2-(np.arange(h)+.5)*np.pi/h
    dirs=np.stack(np.broadcast_arrays(np.cos(yy)[:,None]*np.sin(xx),-np.sin(yy)[:,None]+np.zeros(w),np.cos(yy)[:,None]*np.cos(xx)),axis=-1).astype(np.float32)
    total=np.zeros((h,w,3),np.float32);weight=np.zeros((h,w),np.float32)
    for i,(f,r) in enumerate(zip(frames,rotations)):
        im=cv2.imread(f['path'],cv2.IMREAD_REDUCED_COLOR_2);sh,sw=im.shape[:2]
        fx=sw/(2*np.tan(np.radians(f['hfov']/2)))*focal;fy=sh/(2*np.tan(np.radians(f['vfov']/2)))*focal
        cam=dirs@r;z=np.maximum(cam[:,:,2],1e-6);u=(fx*cam[:,:,0]/z+(sw-1)/2).astype(np.float32);v=(fy*cam[:,:,1]/z+(sh-1)/2).astype(np.float32)
        valid=(cam[:,:,2]>0)&(u>=0)&(v>=0)&(u<sw-1)&(v<sh-1)
        mx=np.broadcast_to(((np.arange(w)+.5)/w*width+padding-.5-corners[i][0])[None,:],(h,w)).astype(np.float32)
        my=np.broadcast_to(((np.arange(h)+.5)/h*height-.5-corners[i][1])[:,None],(h,w)).astype(np.float32)
        soft=cv2.GaussianBlur(cv2.dilate(seam_masks[i],np.ones((3,3),np.uint8)).astype(np.float32)/255,(5,5),.7)
        blend=cv2.remap(soft,mx,my,cv2.INTER_LINEAR)*valid
        gain=cv2.resize(gains[i],(images[i].shape[1],images[i].shape[0]),interpolation=cv2.INTER_LINEAR)
        gain=cv2.remap(gain,mx,my,cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        pixels=cv2.remap(im,u,v,cv2.INTER_LINEAR).astype(np.float32)*gain
        total+=pixels*blend[:,:,None];weight+=blend
    total/=np.maximum(weight,1e-6)[:,:,None]
    cv2.imwrite(str(output),np.clip(total,0,255).astype(np.uint8),[cv2.IMWRITE_JPEG_QUALITY,94])
    print('done',output,'uncovered',np.count_nonzero(weight==0),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('session',type=Path);p.add_argument('alignment',type=Path);p.add_argument('output',type=Path);p.add_argument('--ownership-ratio',type=float,default=0.0);a=p.parse_args();main(a.session,a.alignment,a.output,a.ownership_ratio)
