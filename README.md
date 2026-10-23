# PhotoSphere

A native Android Photo Sphere prototype for Pixel 10 Pro XL on Android 17. Capture overlapping photographs, stitch a 360° × 180° sphere on the phone, and look around it with a responsive interactive viewer. Camera-original JPEG files remain untouched.

## Prototype scope

- Runtime Camera2 capability inspection, standard and maximum-resolution capture modes.
- Sensor-based pointing and roll guidance with smooth visual feedback and a jitter-tolerant steady-hold timer.
- Automatic portrait/landscape layout, including rotation during capture, and free choice of the next direction.
- Explicit upper, lower, zenith and nadir targets, per-shot feedback, pause and finish controls.
- Original JPEGs, capture metadata and a durable session manifest; streaming ZIP export through Android's document picker.
- On-device visual feature matching, rotation refinement, exposure balancing and original-resolution spherical composition.
- Full detail or quicker generation, background progress, cancellation and safe replacement of an existing result.
- Drag/pinch sphere viewer; export a 2:1 JPEG with GPano spherical metadata and saved GPS.
- No account, network permission, analytics, or cloud upload. Location is optional.

This is a first stitching implementation, not a guarantee of seam-free results. Hold the lens near the same point while turning. Visual matching refines the sensor orientation, but the rotational model cannot fully align objects at different depths when the camera moves. Neural matching, positional tracking, depth correction and multiband seam blending are future work.

## Build

Install Android SDK 37.0 and JDK 21. Native processing uses NDK 28.2.13676358 and CMake 3.22.1; Gradle can install these after SDK licences are accepted. Set `ANDROID_HOME` or put `sdk.dir` in your untracked `local.properties`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The project uses the Gradle wrapper and Android Gradle Plugin's built-in Kotlin support, official OpenCV 4.13.0 and AndroidX ExifInterface 1.4.2. The APK contains arm64 and x86_64 native libraries. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Capture

Choose a capture mode and start a sphere in portrait or landscape. Keep the camera lens at approximately one fixed point. Turn in any direction: the guide selects the nearest remaining target. Bring the light into the center, level the phone, and stop moving while the ring fills. Brief jitter freezes the ring instead of flashing the instructions; sustained movement asks you to adjust. Wait for the saved confirmation before moving. You can change orientation during a session. Pause whenever needed; finishing early preserves a partial session.

Sessions are private app files. Export a ZIP from the session list before uninstalling or clearing app data. Every archive contains the original JPEGs, a JSON manifest, and device camera diagnostics. Sharing the archive shares its photographs and any enabled location information.

## Create and view

Open **Your captures → Create Photo Sphere**, choose **Full detail** or **Quicker**, then **Create**. Generation runs on the phone with progress and a cancel control. At least two overlapping accepted photographs are required. A full sphere needs the upper, lower, horizon and pole directions; an incomplete capture produces a partial view on the full spherical canvas.

When it finishes, choose **View sphere**. Drag to look around, pinch to zoom, and double tap to reset. The viewer uses a smaller preview for smooth interaction; **Export JPEG** saves the full-resolution master through Android's document picker. The master includes saved GPS when available. Exporting the originals is a separate ZIP operation.

Full detail chooses dimensions from the source photographs' angular sampling and currently available memory, up to 20,480 × 10,240 (209.7 MP). Quicker caps the width at 8,192. These are ceilings, not promised detail levels. The master uses JPEG quality 97 with 4:4:4 colour sampling. No 10 MB limit is imposed; JPEG size depends on dimensions and scene detail. An existing sphere remains available if a new generation fails or is cancelled.

## Quality and speed

Everyday mode uses the main camera's largest regular JPEG output. Detail mode tries the largest advertised higher-resolution output with a tested preview configuration. Resolution and fallbacks are recorded, never represented as a guaranteed 50 MP mode. Neither mode saves preview frames or upscales photographs.

The preview is resolution-capped; sensor math and overlays stay lightweight. Camera callbacks, file writes, ZIP export and stitching run away from the UI thread. Matching uses smaller working images, then composition decodes the originals one at a time. The sphere viewer renders only when its view changes. The steady-hold interval is intentional capture guidance, not processing latency.

## Development

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for session data and [docs/VALIDATION.md](docs/VALIDATION.md) for checks performed and remaining device validation.

The supplied 2017 reference panorama stays local and is excluded from Git.
