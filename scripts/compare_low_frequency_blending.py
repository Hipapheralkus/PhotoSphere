"""Private, local-only two-band blend comparison using captured JPEG pixels.

Writes diagnostics only to the explicitly supplied output directory. Originals
are read-only. No image generation, upload, or geometric reconstruction occurs.
"""
import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np

from analyze_capture_alignment import load_session
from analyze_photometric_balance import balance


def context(session, report):
    frames = load_session(session)
    width = report.get('seamCanvasWidth', 736)
    height, padding = width // 2, width // 6
    x = (np.arange(width + 2 * padding) - padding + .5) * 2 * np.pi / width - np.pi
    y = np.pi / 2 - (np.arange(height) + .5) * np.pi / height
    rays = np.stack(np.broadcast_arrays(np.cos(y)[:, None] * np.sin(x),
                    -np.sin(y)[:, None] + np.zeros(len(x)), np.cos(y)[:, None] * np.cos(x)), axis=-1)
    images, masks, corners, scores, broad_weights = [], [], [], [], []
    best = np.zeros((height, len(x)), np.float32)
    focal = report['focalScale']
    for f, a in zip(frames, report['frames']):
        source = cv2.imread(f['path'], cv2.IMREAD_REDUCED_COLOR_4)
        if source is None:
            raise RuntimeError('Cannot decode an original JPEG')
        if max(source.shape[:2]) > 1024:
            scale = 1024 / max(source.shape[:2])
            source = cv2.resize(source, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        h, w = source.shape[:2]
        camera = rays @ np.asarray(a['rotation']).reshape(3, 3)
        z = np.maximum(camera[:, :, 2], 1e-6)
        tx = camera[:, :, 0] / z / np.tan(np.radians(f['hfov'] / 2)) * focal
        ty = camera[:, :, 1] / z / np.tan(np.radians(f['vfov'] / 2)) * focal
        valid = (camera[:, :, 2] > 0) & (abs(tx) < 1 - 1 / (w * 4)) & (abs(ty) < 1 - 1 / (h * 4))
        mask = valid.astype(np.uint8) * 255
        u, v = ((tx * w + w - 1) / 2).astype(np.float32), ((ty * h + h - 1) / 2).astype(np.float32)
        warped = cv2.remap(source, u, v, cv2.INTER_LINEAR)
        edge = np.minimum(1 - abs(tx), 1 - abs(ty))
        score = (np.maximum(camera[:, :, 2], 0)**8 * np.clip(edge / .12, 0, 1) * valid).astype(np.float32)
        best = np.maximum(best, score)
        feather = np.clip(edge / .25, 0, 1)
        broad = (feather**2 * (3 - 2 * feather) * camera[:, :, 2]**2 * valid).astype(np.float32)
        xx, yy, ww, hh = cv2.boundingRect(mask)
        x0, y0 = max(0, xx - 2), max(0, yy - 2)
        x1, y1 = min(len(x), xx + ww + 2), min(height, yy + hh + 2)
        sl = np.s_[y0:y1, x0:x1]
        images.append(warped[sl].copy()); masks.append(mask[sl].copy()); corners.append((x0, y0))
        scores.append(score[sl].copy()); broad_weights.append(broad[sl].copy())
    corrected, grids, block, count = balance(images, corners, masks)
    print('Exposure ready', block, count, flush=True)
    restricted = []
    for score, mask, (x0, y0) in zip(scores, masks, corners):
        h, w = mask.shape
        restricted.append(cv2.UMat(mask * (score >= .78 * best[y0:y0+h, x0:x0+w]).astype(np.uint8)))
    cv2.detail_GraphCutSeamFinder('COST_COLOR_GRAD').find([im.astype(np.float32) for im in corrected], corners, restricted)
    narrow = []
    for m, broad, mask in zip(restricted, broad_weights, masks):
        soft = cv2.GaussianBlur(cv2.dilate(m.get(), np.ones((3, 3), np.uint8)).astype(np.float32) / 255,
                               (5, 5), .7)
        # Composition quantizes weights to uint16 accumulation units.
        weight = np.maximum(1, np.rint(255 * soft * broad)).astype(np.float32) / 255
        weight[(soft < 1e-5) | (mask == 0)] = 0
        narrow.append(weight)
    print('Seams ready', flush=True)
    return dict(width=width, height=height, padding=padding, images=corrected, masks=masks,
                corners=corners, broad=broad_weights, narrow=narrow)


def correction(ctx, sigma):
    shape = (ctx['height'], ctx['width'] + 2 * ctx['padding'])
    broad_color, narrow_color = np.zeros((*shape, 3), np.float32), np.zeros((*shape, 3), np.float32)
    broad_sum, narrow_sum = np.zeros(shape, np.float32), np.zeros(shape, np.float32)
    for image, mask, (x, y), broad, narrow in zip(ctx['images'], ctx['masks'], ctx['corners'], ctx['broad'], ctx['narrow']):
        valid = mask.astype(np.float32) / 255
        denominator = cv2.GaussianBlur(valid, (0, 0), sigma, borderType=cv2.BORDER_CONSTANT)
        lp = cv2.GaussianBlur(image.astype(np.float32) * valid[:, :, None], (0, 0), sigma,
                              borderType=cv2.BORDER_CONSTANT)
        lp /= np.maximum(denominator, 1e-6)[:, :, None]
        h, w = mask.shape; sl = np.s_[y:y+h, x:x+w]
        broad_color[sl] += lp * broad[:, :, None]; broad_sum[sl] += broad
        narrow_color[sl] += lp * narrow[:, :, None]; narrow_sum[sl] += narrow
    broad_color /= np.maximum(broad_sum, 1e-6)[:, :, None]
    narrow_color /= np.maximum(narrow_sum, 1e-6)[:, :, None]
    delta = broad_color - narrow_color
    delta[(broad_sum < 1e-6) | (narrow_sum < 1e-6)] = 0
    return delta[:, ctx['padding']:ctx['padding'] + ctx['width']]


def main(session, report_path, preview_path, output):
    cv2.setNumThreads(3)
    started = time.monotonic()
    report = json.loads(report_path.read_text()); report = report.get('native', report)
    ctx = context(session, report)
    original = cv2.imread(str(preview_path))
    output.mkdir(parents=True, exist_ok=True)
    # At this size, derivative changes near ceilings remain measurable while
    # avoiding comparing resampling noise at the 4096px viewer-preview scale.
    small = cv2.resize(original, (ctx['width'], ctx['height']), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.float32)
    ceiling = np.indices(gray.shape)[0] < ctx['height'] * .33
    ceiling &= (gray > 55) & (gray < 235)
    rows = []
    for sigma in [8, 12, 20]:
        actual_sigma = sigma * ctx['width'] / 768
        delta = correction(ctx, actual_sigma)
        delta = np.clip(delta, -80, 80)
        expanded = cv2.resize(delta, (original.shape[1], original.shape[0]), interpolation=cv2.INTER_LINEAR)
        result = np.clip(original.astype(np.float32) + expanded, 0, 255).astype(np.uint8)
        cv2.imwrite(str(output / f'low-frequency-{sigma}.jpg'), result, [cv2.IMWRITE_JPEG_QUALITY, 95])
        result_small = np.clip(small.astype(np.float32) + delta, 0, 255).astype(np.uint8)
        lum = cv2.cvtColor(result_small, cv2.COLOR_BGR2GRAY).astype(np.float32)
        before_gradient = np.hypot(cv2.Sobel(gray, cv2.CV_32F, 1, 0), cv2.Sobel(gray, cv2.CV_32F, 0, 1))
        after_gradient = np.hypot(cv2.Sobel(lum, cv2.CV_32F, 1, 0), cv2.Sobel(lum, cv2.CV_32F, 0, 1))
        row = dict(sigmaAt768=sigma, effectiveSigma=actual_sigma, maximumCorrection=float(np.max(abs(delta))),
                   ceilingGradientP90Before=float(np.percentile(before_gradient[ceiling], 90)),
                   ceilingGradientP90After=float(np.percentile(after_gradient[ceiling], 90)),
                   clippedBefore=int(np.any(original >= 253, axis=2).sum()),
                   clippedAfter=int(np.any(result >= 253, axis=2).sum()))
        rows.append(row); print(json.dumps(row), flush=True)
    (output / 'metrics.json').write_text(json.dumps(dict(results=rows, elapsedSeconds=time.monotonic()-started), indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for arg in ['session', 'report', 'preview', 'output']:
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    main(args.session, args.report, args.preview, args.output)
