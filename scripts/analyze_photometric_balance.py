"""Local-only overlap exposure diagnostics. Reads originals; writes only a private JSON report.

Use an ignored artifacts/device/ output directory. Scene-derived pixels never leave this process.
"""
import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np

from analyze_capture_alignment import load_session


def warp_inputs(session, alignment, width=768, padding=128):
    frames = load_session(session)
    report = json.loads(alignment.read_text())
    report = report.get('native', report)
    focal = report.get('focalScale', 1)
    height = width // 2
    x = (np.arange(-padding, width + padding) + .5) * 2 * np.pi / width - np.pi
    y = np.pi / 2 - (np.arange(height) + .5) * np.pi / height
    rays = np.stack(np.broadcast_arrays(np.cos(y)[:, None] * np.sin(x),
                    -np.sin(y)[:, None] + np.zeros(len(x)), np.cos(y)[:, None] * np.cos(x)), axis=-1)
    images, corners, masks = [], [], []
    for frame, aligned in zip(frames, report['frames']):
        source = cv2.imread(frame['path'], cv2.IMREAD_REDUCED_COLOR_8)
        if source is None:
            raise RuntimeError('Cannot decode one of the session originals; use the extracted backup session directory')
        h, w = source.shape[:2]
        rotation = np.asarray(aligned['rotation']).reshape(3, 3)
        camera = rays @ rotation
        z = np.maximum(camera[:, :, 2], 1e-6)
        fx = w / (2 * np.tan(np.radians(frame['hfov'] / 2))) * focal
        fy = h / (2 * np.tan(np.radians(frame['vfov'] / 2))) * focal
        u = (fx * camera[:, :, 0] / z + (w - 1) / 2).astype(np.float32)
        v = (fy * camera[:, :, 1] / z + (h - 1) / 2).astype(np.float32)
        valid = ((camera[:, :, 2] > 0) & (u >= 2) & (v >= 2) & (u < w - 3) & (v < h - 3)).astype(np.uint8) * 255
        points = cv2.findNonZero(valid)
        if points is None:
            raise RuntimeError('A source image has no projected footprint')
        xx, yy, ww, hh = cv2.boundingRect(points)
        image = cv2.remap(source, u, v, cv2.INTER_LINEAR)[yy:yy + hh, xx:xx + ww].copy()
        images.append(image)
        masks.append(valid[yy:yy + hh, xx:xx + ww].copy())
        corners.append((xx, yy))
    return images, corners, masks


def balance(images, corners, masks, max_blocks=512, similarity=.3, filter_iterations=2):
    if len(images) > max_blocks:
        raise ValueError('Block budget must allow at least one block per image')
    block = 32
    blocks = lambda size: sum(((im.shape[1] + size - 1) // size) * ((im.shape[0] + size - 1) // size) for im in images)
    while blocks(block) > max_blocks:
        block += 8
    filtered, reliable = [], []
    for image, mask in zip(images, masks):
        smooth = cv2.GaussianBlur(image, (3, 3), .8)
        stable = cv2.inRange(smooth, (12, 12, 12), (244, 244, 244)) & mask
        stable = cv2.erode(stable, np.ones((3, 3), np.uint8))
        filtered.append(smooth)
        reliable.append(stable)
    solver = cv2.detail_BlocksChannelsCompensator(block, block, 1)
    solver.setNrGainsFilteringIterations(filter_iterations)
    solver.setSimilarityThreshold(similarity)
    solver.feed(corners, filtered, reliable)
    small_maps = solver.getMatGains()
    maps, adjusted = [], []
    for image, grid in zip(images, small_maps):
        grid = np.nan_to_num(grid, nan=1., posinf=1., neginf=1.)
        grid = np.clip(grid, .70, 1.35)
        gain = cv2.resize(grid, (image.shape[1], image.shape[0]), interpolation=cv2.INTER_LINEAR)
        maps.append(grid)
        adjusted.append(np.clip(np.rint(image.astype(np.float32) * gain), 0, 255).astype(np.uint8))
    return adjusted, maps, block, blocks(block)


def metrics(before, after, corners, masks):
    pair_before, pair_after, ceiling_before, ceiling_after = [], [], [], []
    for a in range(len(before)):
        ax, ay = corners[a]; ah, aw = before[a].shape[:2]
        for b in range(a + 1, len(before)):
            bx, by = corners[b]; bh, bw = before[b].shape[:2]
            left, top, right, bottom = max(ax, bx), max(ay, by), min(ax + aw, bx + bw), min(ay + ah, by + bh)
            if left >= right or top >= bottom:
                continue
            sa = np.s_[top - ay:bottom - ay, left - ax:right - ax]
            sb = np.s_[top - by:bottom - by, left - bx:right - bx]
            va, vb = before[a][sa].astype(np.float32), before[b][sb].astype(np.float32)
            valid = (masks[a][sa] > 0) & (masks[b][sb] > 0)
            valid &= (va.min(2) >= 15) & (vb.min(2) >= 15) & (va.max(2) <= 240) & (vb.max(2) <= 240)
            if valid.sum() < 100:
                continue
            ea, eb = after[a][sa].astype(np.float32), after[b][sb].astype(np.float32)
            error_before = np.mean(abs(va - vb), axis=2)
            error_after = np.mean(abs(ea - eb), axis=2)
            pair_before.append(float(np.median(error_before[valid])))
            pair_after.append(float(np.median(error_after[valid])))
            ceiling = valid & (np.arange(top, bottom)[:, None] < 108)
            if ceiling.sum() >= 100:
                ceiling_before.append(float(np.median(error_before[ceiling])))
                ceiling_after.append(float(np.median(error_after[ceiling])))
    return dict(overlapPairs=len(pair_before), medianOverlapErrorBefore=float(np.median(pair_before)),
                medianOverlapErrorAfter=float(np.median(pair_after)), ceilingPairs=len(ceiling_before),
                medianCeilingErrorBefore=float(np.median(ceiling_before)), medianCeilingErrorAfter=float(np.median(ceiling_after)),
                p90OverlapErrorBefore=float(np.percentile(pair_before, 90)), p90OverlapErrorAfter=float(np.percentile(pair_after, 90)))


def main(session, alignment, output, budgets):
    cv2.setNumThreads(3)
    started = time.monotonic()
    images, corners, masks = warp_inputs(session, alignment)
    report = dict(imageCount=len(images), totalRoiPixels=sum(im.shape[0] * im.shape[1] for im in images), experiments=[])
    output.parent.mkdir(parents=True, exist_ok=True)
    for nodes in budgets:
        start = time.monotonic()
        corrected, gains, block, count = balance(images, corners, masks, nodes)
        m = metrics(images, corrected, corners, masks)
        m.update(maxBlocks=nodes, blockSize=block, blocks=count, maximumGain=max(float(g.max()) for g in gains),
                 minimumGain=min(float(g.min()) for g in gains), gainMapBytes=sum(g.nbytes for g in gains),
                 elapsedSeconds=time.monotonic() - start)
        before_clip = sum(int(np.any(im >= 253, axis=2)[mask > 0].sum()) for im, mask in zip(images, masks))
        after_clip = sum(int(np.any(im >= 253, axis=2)[mask > 0].sum()) for im, mask in zip(corrected, masks))
        m.update(clippedPixelsBefore=before_clip, clippedPixelsAfter=after_clip)
        report['experiments'].append(m)
        report['elapsedSeconds'] = time.monotonic() - started
        output.write_text(json.dumps(report, indent=2))
        print(json.dumps(m), flush=True)
    report['elapsedSeconds'] = time.monotonic() - started
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('session', type=Path)
    parser.add_argument('alignment', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--max-blocks', type=int, nargs='+', default=[512])
    args = parser.parse_args()
    if any(b < 48 or b > 1024 for b in args.max_blocks):
        parser.error('--max-blocks must be between 48 and 1024')
    main(args.session, args.alignment, args.output, args.max_blocks)
