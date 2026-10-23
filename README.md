# PhotoSphere

A fast, native Android capture prototype for Pixel 10 Pro XL on Android 17. Guides overlapping photographs around a full sphere and preserves camera-original JPEG files for later stitching.

## Prototype scope

- Runtime Camera2 capability inspection, standard and maximum-resolution capture modes.
- Sensor-based pointing and roll guidance with smooth visual feedback and a jitter-tolerant steady-hold timer.
- Automatic portrait/landscape layout, including rotation during capture, and free choice of the next direction.
- Explicit upper, lower, zenith and nadir targets, per-shot feedback, pause and finish controls.
- Original JPEGs, capture metadata and a durable session manifest; streaming ZIP export through Android's document picker.
- No account, network permission, analytics, or cloud upload. Location is optional.

This milestone captures source images. It does **not** yet stitch images, measure physical translation, or guarantee seam-free results. Hold the lens near the same point while turning. Orientation is estimated from the phone's sensors; image-based registration and positional tracking belong in the next milestone.

## Build

Install Android SDK 37.0 and JDK 17 or newer (JDK 21 recommended). Set `ANDROID_HOME` or put `sdk.dir` in your untracked `local.properties`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The project uses the Gradle wrapper and Android Gradle Plugin's built-in Kotlin support. It has no third-party runtime dependencies.

## Capture

Choose a capture mode and start a sphere in portrait or landscape. Keep the camera lens at approximately one fixed point. Turn in any direction: the guide selects the nearest remaining target. Bring the light into the center, level the phone, and stop moving while the ring fills. Brief jitter freezes the ring instead of flashing the instructions; sustained movement asks you to adjust. Wait for the saved confirmation before moving. You can change orientation during a session. Pause whenever needed; finishing early preserves a partial session.

Sessions are private app files. Export a ZIP from the session list before uninstalling or clearing app data. Every archive contains the original JPEGs, a JSON manifest, and device camera diagnostics. Sharing the archive shares its photographs and any enabled location information.

## Quality and speed

Everyday mode uses the main camera's largest regular JPEG output. Detail mode tries the largest advertised higher-resolution output with a tested preview configuration. Resolution and fallbacks are recorded, never represented as a guaranteed 50 MP mode. Neither mode saves preview frames or upscales photographs.

The preview is resolution-capped; sensor math and overlays stay lightweight. Camera callbacks, original-file writes and ZIP export use background threads. The steady-hold interval is intentional capture guidance, not processing latency.

## Development

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for session data and [docs/VALIDATION.md](docs/VALIDATION.md) for checks performed and remaining device validation.

The supplied 2017 reference panorama stays local and is excluded from Git.
