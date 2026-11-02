# PhotoSphere

A native Android Photo Sphere prototype for Pixel 10 Pro XL on Android 17. Capture overlapping photographs, stitch a 360° × 180° sphere on the phone, and look around it with a responsive interactive viewer. Camera-original JPEG files remain untouched.

Download an installable early-access APK from [GitHub Releases](https://github.com/Hipapheralkus/PhotoSphere/releases). Install updates over the existing app to keep your captures.

## Prototype scope

- Runtime Camera2 capability inspection, standard and maximum-resolution capture modes.
- Sensor-based pointing and roll guidance with smooth visual feedback and a jitter-tolerant steady-hold timer.
- Automatic portrait/landscape layout, including rotation during capture, and free choice of the next direction.
- A 33-stop plan on the tested Pixel, or 48-stop Extra overlap; upper, lower, zenith and nadir coverage.
- Tap a map target or choose from a direction list; automatic recovery from motion and transient camera errors.
- Default-on exposure and white-balance locking, with a simple automatic-brightness option.
- Original JPEGs, capture metadata and a durable session manifest; streaming ZIP export through Android's document picker.
- On-device visual feature matching, rotation refinement, exposure balancing and original-resolution spherical composition.
- Automatic full-detail generation, background progress, cancellation and safe replacement of an existing result.
- Drag/pinch sphere viewer; export a 2:1 JPEG with GPano spherical metadata and saved GPS.
- No account, network permission, analytics, or cloud upload. Location is optional.

The stitcher refines individual image matches, balances local brightness and chooses content-aware seams. Seam-free results are not guaranteed. Hold the lens near the same point while turning. Visual matching refines the sensor orientation, but the rotational model cannot fully align objects at different depths when the camera moves. Neural matching, positional tracking, depth correction and more advanced seam blending are future work.

## Build

Install Android SDK 37.2, Build Tools 37.0.0 and JDK 27. Native processing uses NDK 30.0.16248370 and SDK CMake 4.1.2. The wrapper pins Gradle 9.8.2, with Android Gradle Plugin 9.5.0 and Kotlin 2.4.21. Set `ANDROID_HOME` or put `sdk.dir` in your untracked `local.properties`. See [build requirements](docs/BUILDING.md) for installation commands and version constraints.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The project uses the Gradle wrapper and Android Gradle Plugin's built-in Kotlin support, official OpenCV 5.0.0.1 and AndroidX ExifInterface 1.4.2. The APK contains arm64 and x86_64 native libraries. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Capture

Start a sphere in portrait or landscape. Keep the camera lens at approximately one fixed point. The tested Pixel uses 33 stops by default (31% fewer than the previous 48). Open **Capture options → Extra overlap** for the 48-stop plan in difficult scenes. Narrower camera views fall back to the conservative plan. Turn in any direction: the guide selects the nearest remaining target. Tap a hollow map dot or **Choose direction** to hold a deliberate target while people pass elsewhere; **Follow my aim** returns to free selection. Bring the light into the center, level the phone, and stop moving while the ring fills. Brief jitter freezes the ring instead of flashing the instructions; sustained movement asks you to adjust. Wait for the saved confirmation before moving. You can change orientation during a session. Motion-rejected photos and known temporary camera errors retry automatically up to three times, with a fresh steady hold each time. Persistent errors pause; storage and permission failures pause immediately. Pause whenever needed; finishing early preserves a partial session.

**Capture options → Keep brightness steady** is on by default. The camera settles before the first accepted photo, then holds exposure and white balance for the sphere when the camera supports both locks. Turn it off for automatic adjustments in each direction. Failed or movement-rejected first photos do not establish the lock; ending the sphere restores automatic preview. A camera restart reacquires the lock, and per-photo metadata records the actual result and any fallback. This reduces exposure/color seams but cannot disable every device-specific JPEG tone adjustment. RAW/DNG capture is not included.

Sessions are private app files. Export a ZIP from **Gallery → ⋯ → Export original photos** before uninstalling or clearing app data. Every archive contains the original JPEGs, a JSON manifest, and device camera diagnostics. Sharing the archive shares its photographs and any enabled location information.

## Create and view

Completing a capture automatically creates its sphere at full detail. Open **Gallery** to view finished spheres or create one from a saved capture. Secondary controls let you recreate a result or export its original photos. Generation runs on the phone with progress and a cancel control. At least two overlapping accepted photographs are required. A full sphere needs the upper, lower, horizon and pole directions; an incomplete capture produces a partial view on the full spherical canvas.

When it finishes, choose **View sphere**. Drag to look around, pinch to zoom, and double tap to reset. The viewer uses a smaller preview for smooth interaction; **Export JPEG** saves the full-resolution master through Android's document picker. The master includes saved GPS when available. Exporting the originals is a separate ZIP operation.

Generation chooses dimensions from the source photographs' angular sampling and currently available memory, up to 20,480 × 10,240 (209.7 MP). These are ceilings, not promised detail levels. The master uses JPEG quality 97 with 4:4:4 colour sampling. No 10 MB limit is imposed; JPEG size depends on dimensions and scene detail. An existing sphere remains available if a new generation fails or is cancelled.

## Quality and speed

Everyday mode uses the main camera's largest regular JPEG output. Detail mode tries the largest advertised higher-resolution output with a tested preview configuration. Resolution and fallbacks are recorded, never represented as a guaranteed 50 MP mode. Neither mode saves preview frames or upscales photographs.

The preview is resolution-capped; sensor math and overlays stay lightweight. Camera callbacks, file writes, ZIP export and stitching run away from the UI thread. Matching uses smaller working images, then composition decodes the originals one at a time. The sphere viewer renders only when its view changes. The steady-hold interval is intentional capture guidance, not processing latency.

## Local troubleshooting

Published prototype APKs remain debuggable so an explicitly authorized USB debugging connection can inspect local capture records and processing reports. Recent capture/retry/stitch events are stored in private `files/diagnostics/events.jsonl`, with one rotated file (128 KiB each). These contain event types, timing and non-location technical values; images and GPS stay in the private capture records. No diagnostics are uploaded automatically. The app has no network permission and Android backup is disabled.

## Development

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for session data and [docs/VALIDATION.md](docs/VALIDATION.md) for checks performed and remaining device validation.

The supplied 2017 reference panorama stays local and is excluded from Git.
