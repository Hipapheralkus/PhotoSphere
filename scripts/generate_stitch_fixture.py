"""Create deterministic synthetic sphere originals for the Android stitching test.

Requires numpy and Pillow. No private photographs or location data are used.
Run: python scripts/generate_stitch_fixture.py artifacts/device/stitch-fixture.zip
"""
import io
import json
import math
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def environment():
    rng = np.random.default_rng(317)
    w, h = 4096, 2048
    texture = np.zeros((h, w, 3), dtype=np.float32)
    for size, weight in [(32, 0.4), (128, 0.3), (512, 0.3)]:
        noise = rng.integers(20, 236, (size // 2, size, 3), dtype=np.uint8)
        texture += np.asarray(Image.fromarray(noise).resize((w, h), Image.Resampling.BICUBIC)) * weight
    longitude = np.linspace(-math.pi, math.pi, w, endpoint=False)[None, :]
    latitude = np.linspace(math.pi/2, -math.pi/2, h)[:, None]
    color = np.empty_like(texture)
    color[:, :, 0] = 128 + 95 * np.cos(latitude) * np.sin(longitude)
    color[:, :, 1] = 128 + 95 * np.cos(latitude) * np.cos(longitude)
    color[:, :, 2] = 128 + 95 * np.sin(latitude)
    image = Image.fromarray((texture * .55 + color * .45).astype(np.uint8))
    draw = ImageDraw.Draw(image)
    for _ in range(900):
        x = int(rng.integers(30, w - 30))
        y = int(rng.integers(70, h - 70))
        radius = int(rng.integers(4, 23))
        color = tuple(int(n) for n in rng.integers(10, 246, 3))
        draw.ellipse((x-radius, y-radius, x+radius, y+radius), fill=color, outline=(240, 240, 220), width=2)
    for yaw in range(-150, 180, 30):
        x = int((yaw + 180) / 360 * w)
        for pitch in (-60, -30, 0, 30, 60):
            y = int((90-pitch)/180*h)
            draw.rounded_rectangle((x-80, y-20, x+80, y+22), radius=7, fill=(20, 30, 27))
            draw.text((x-68, y-13), f"{yaw:+d} / {pitch:+d}", fill=(225, 245, 230), font_size=25)
    # Make the longitude boundary and the exact poles continuous.
    a = np.asarray(image).copy()
    for x in range(24):
        mix = (a[:, x].astype(np.float32) + a[:, -1-x].astype(np.float32)) / 2
        a[:, x] = mix.astype(np.uint8)
        a[:, -1-x] = mix.astype(np.uint8)
    for y in range(40):
        t = y / 40
        a[y] = a[y] * t + np.array([160, 185, 220]) * (1-t)
        a[-1-y] = a[-1-y] * t + np.array([75, 55, 35]) * (1-t)
    return a


def render(texture, yaw, pitch, roll, width, height, hfov, vfov):
    yaw, pitch, roll = np.radians([yaw, pitch, roll])
    right = np.array([math.cos(yaw), 0, -math.sin(yaw)])
    up = np.array([-math.sin(yaw)*math.sin(pitch), math.cos(pitch), -math.cos(yaw)*math.sin(pitch)])
    forward = np.array([math.sin(yaw)*math.cos(pitch), math.sin(pitch), math.cos(yaw)*math.cos(pitch)])
    camera_right = right * math.cos(roll) - up * math.sin(roll)
    camera_up = right * math.sin(roll) + up * math.cos(roll)
    x = ((np.arange(width, dtype=np.float32) + .5) / width * 2 - 1) * math.tan(math.radians(hfov)/2)
    y = (1 - (np.arange(height, dtype=np.float32) + .5) / height * 2) * math.tan(math.radians(vfov)/2)
    rays = forward + x[None, :, None] * camera_right + y[:, None, None] * camera_up
    rays /= np.linalg.norm(rays, axis=2, keepdims=True)
    longitude = np.arctan2(rays[:, :, 0], rays[:, :, 2])
    latitude = np.arcsin(rays[:, :, 1])
    u = (longitude / (2*math.pi) + .5) * texture.shape[1] - .5
    v = np.clip((.5 - latitude / math.pi) * texture.shape[0] - .5, 0, texture.shape[0]-1)
    x0 = np.floor(u).astype(np.int32) % texture.shape[1]
    x1 = (x0 + 1) % texture.shape[1]
    y0 = np.floor(v).astype(np.int32)
    y1 = np.minimum(y0 + 1, texture.shape[0]-1)
    fx = (u - np.floor(u))[:, :, None]
    fy = (v - y0)[:, :, None]
    pixels = (texture[y0, x0] * (1-fx) + texture[y0, x1] * fx) * (1-fy) + (texture[y1, x0] * (1-fx) + texture[y1, x1] * fx) * fy
    return Image.fromarray(pixels.astype(np.uint8))


def main():
    destination = Path(sys.argv[1] if len(sys.argv) > 1 else "artifacts/device/stitch-fixture.zip")
    destination.parent.mkdir(parents=True, exist_ok=True)
    texture = environment()
    targets = []
    for pitch, count in [(0, 12), (30, 11), (60, 6), (90, 1), (-30, 11), (-60, 6), (-90, 1)]:
        targets.extend((i * 360 / count if i*360/count < 180 else i*360/count-360, pitch) for i in range(count))
    frames = []
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as archive:
        ground = io.BytesIO()
        Image.fromarray(texture).save(ground, format="JPEG", quality=97)
        archive.writestr("ground-truth.jpg", ground.getvalue())
        for index, (yaw, pitch) in enumerate(targets):
            landscape = index % 3 == 1
            w, h = (1024, 768) if landscape else (768, 1024)
            hfov, vfov = (70.72, 56.23) if landscape else (56.23, 70.72)
            roll = 0 if index == 0 else (17 if pitch == 90 else -23 if pitch == -90 else 2.5*math.sin(index*.9))
            frame = render(texture, yaw, pitch, roll, w, h, hfov, vfov)
            exif = Image.Exif()
            if index % 4 == 0:
                frame = frame.transpose(Image.Transpose.ROTATE_90)
                exif[274] = 6
            elif index % 4 == 1:
                frame = frame.transpose(Image.Transpose.ROTATE_180)
                exif[274] = 3
            else:
                exif[274] = 1
            photo = io.BytesIO()
            frame.save(photo, format="JPEG", quality=96, exif=exif)
            name = f"originals/frame_{index:03d}.jpg"
            archive.writestr(name, photo.getvalue())
            frames.append(dict(file=name, yaw=yaw, pitch=pitch,
                               poseYaw=yaw + (0 if index == 0 else .8*math.sin(index*1.7)),
                               posePitch=max(-90, min(90, pitch + (0 if index == 0 else .5*math.cos(index*1.3)))),
                               roll=roll, displayRotation=(90 if index % 2 else 270) if landscape else 0,
                               width=frame.width, height=frame.height, bytes=len(photo.getvalue())))
        archive.writestr("fixture.json", json.dumps(dict(schemaVersion=1, frames=frames, hfov=56.23, vfov=70.72,
                                                         syntheticGps=dict(latitude=10.25, longitude=-20.5, altitudeMeters=123.0))))
    print(f"Created {destination}: {len(frames)} mixed-orientation source JPEGs, {destination.stat().st_size} bytes")


if __name__ == "__main__":
    main()
