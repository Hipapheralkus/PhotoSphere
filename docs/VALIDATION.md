# Prototype validation

## Current build validation — version 0.3.0, 2 November 2026

`assembleDebug`, `assembleRelease` (R8), `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` pass with the versions in [BUILDING.md](BUILDING.md). All 53 JVM tests pass: 22 guidance/coverage, 4 bounded retry, 4 exposure-lock policy, 3 pose association, 6 stitch sizing, 3 input selection, 7 lifecycle/publication and 4 spherical metadata. Native builds cover arm64-v8a and x86_64. The installable APK retains the existing update certificate, debugging, no network permission and disabled backup. Packaged license hashes and 16 KiB ZIP/native library alignment were checked.

The final two-band native build also passed the isolated Android x86_64 synthetic runner: 48 mixed-orientation originals, mean absolute RGB error 2.39/255 against ground truth (limit 15), full and partial coverage, spherical/GPS metadata, unchanged original hashes, replacement cancellation, cancellation after native encoding, and ordinary viewer drag/pinch/reset/rotation. Test imagery is synthetic; this does not establish physical camera quality. The final app also passed the no-photo home/gallery, camera-preview rotation, target selection, follow-aim, accessible chooser, automatic retry and permanent-error pause runner in the emulator.

The new default-on brightness option locks supported AE/AWB controls after settling, retains the lock only after durable acceptance, releases rejected first candidates, and restores automatic preview at session end. Per-photo metadata records actual result flags and fallback/restart behavior. Four policy tests cover acceptance, rejection, fallback and unsupported controls. The explicit two-JPEG `BrightnessSmokeInstrumentation` runner compiles, but **has not been run on the physical Pixel**: the user chose to continue without the phone. Existing capability records advertise both locks; that is not a substitute for testing real exposure/ISO/white-balance consistency. Rejected-first-photo recovery on hardware also remains unverified. RAW/DNG capture is not implemented.

## Historical build validation — version 0.2.0

### Build and automated checks

Built with JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1 and Android SDK 37.0. The app targets Android 17 and requires Android 11 or newer.

`assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `testDebugUnitTest` and `lintDebug` pass. There are 40 passing JVM tests: 17 guidance/orientation/coverage, 3 exposure-pose association, 6 stitch sizing, 3 input selection, 7 lifecycle/publication, and 4 spherical metadata tests. Lint has no errors; warnings remain for prototype localization/layout direction, SDK/API usage, backup configuration and tooling versions. Native builds use NDK 28.2.13676358, CMake 3.22.1, OpenCV 4.13.0 and both arm64-v8a/x86_64 ABIs.

## Physical Pixel 10 Pro XL, Android 17

The separate androidTest instrumentation runner captured two real JPEGs in each capture mode. It checked encoded image dimensions, positive sensor timestamps, byte counts, durable session commits, recovery reports for intact sessions, and streaming ZIP export. SHA-256 comparisons verified that exported originals matched the saved camera JPEGs byte for byte. This run did not exercise rejected or orphaned originals.

| Measurement | Everyday | More detail |
| --- | --- | --- |
| Selected logical rear camera | 0 | 0 |
| Actual negotiated JPEG | 4080 × 3072 | 4080 × 3072 |
| Pixel count | 12.53 MP | 12.53 MP |
| Camera open to first preview | 348 ms | 295 ms |
| Original capture/save samples | 1342, 1286 ms | 1294, 1280 ms |
| Original bytes preserved in export | Yes | Yes |

This device/build did not expose a larger JPEG through the selected public Camera2 configuration. Detail mode explicitly recorded that it used the same resolution. This does not establish that every firmware version or camera configuration has the same limit.

These are four functional-test samples, not a broad performance benchmark. The test scene did not achieve autofocus convergence before the 1200 ms deadline; `threeAWaitTimedOut` is recorded in each image's metadata. This explains most of the measured capture delay. Actual exposure was approximately 67 ms in the first sample. Focus/sharpness quality is not established by these byte-level checks.

The camera reported REALTIME sensor timestamps, calibration/intrinsic data, distortion coefficients and the active physical camera ID. The app can therefore associate its motion samples with exposure timestamps on this device.

The ordinary app was installed and launched successfully. One measured cold Activity launch was 219 ms. This is a single sample, not a startup guarantee.

These physical camera measurements used the initial prototype before the landscape and guidance update. Later UI validation uses the Android 16.1 emulator and is separate from Pixel camera-quality measurements.

## Rotation and UI validation — version 0.1.1

The Android 16.1 emulator ran the ordinary app through portrait, landscape, reverse landscape and portrait return with a paused capture session. The instrumentation test passed: the same Activity, capture session ID/object, Camera2 session, TextureView and preview surface survived each rotation. Progress stayed unchanged, the camera remained ready, frame aspect ratio and overlay centring were correct, the capture button stayed visible, and landscape controls did not overlap the preview pane. The test took no photographs and retained one explicitly identified empty test session.

Home and camera layouts were also visually inspected in portrait and landscape. JVM tests cover the sensor-matrix rotation convention; the emulator rotation test checks lifecycle and geometry, not physical handheld comfort or real-world stitching accuracy. The updated guidance still needs a handheld Pixel trial.

To run the separate rotation test, build/select its runner explicitly (the test runner is absent from the normal app APK):

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-PtestRunner=app.photosphere.RotationSmokeInstrumentation'
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w app.photosphere.test/app.photosphere.RotationSmokeInstrumentation
```

Its screenshots and JSON report are stored in app cache under `rotation-validation/<runId>/`. Reset any emulator window-manager rotation overrides first. Building without the `testRunner` property selects the original physical-camera runner again.

## Stitching and viewing — version 0.2.0

The Android 16.1 emulator ran the ordinary foreground service and sphere viewer against 48 deterministic synthetic originals. The fixture covers the whole sphere and mixes portrait, both landscape display rotations, EXIF orientations 1/3/6, nonzero roll (including pole views), and slightly inaccurate sensor yaw/pitch. Synthetic GPS is explicitly labelled; no private photographs are included in the test fixture.

The final run visually matched all 48 photos into one connected graph, with 210 verified overlap pairs, 100% rendered footprint coverage and approximately 0.049° mean visual residual. Its 3328 × 1664 memory-limited output had mean absolute RGB error 2.68/255 against the known sphere at 1024 × 512, excluding three rows at each pole. The automated threshold is 15/255. The complete test, including import, generation, metadata checks, partial coverage, cancellation and viewer gestures, took about 15 seconds; this is an emulator functional run with 0.79 MP inputs, not a Pixel speed measurement or full-resolution thermal benchmark. Output size and timings vary with available memory.

The device test verifies:

- 2:1 encoded JPEG dimensions, GPano namespace/fields/source count and synthetic GPS/altitude.
- No invented compass heading, and removal of stale GPS when location is absent.
- Original JPEG SHA-256 hashes remain unchanged; metadata edits preserve the encoded JPEG scan.
- Cancelling a replacement retains the previous published job and identical master bytes.
- Four source views produce about 22.8% photographed coverage with neutral missing directions; cancellation after native encoding removes only its unpublished outputs.
- Large malformed native JSON returns an error without crashing, and a pre-existing output file is preserved.
- The ordinary viewer renders actual scene pixels, supports drag/pinch/reset, and retains direction and zoom when rotating to landscape. Portrait/landscape screenshots were inspected.

The ordinary **Create** dialog and session controls were also exercised outside instrumentation. Generation completed after notification permission was declined. **View sphere → Export JPEG → Save** used Android's Downloads document picker; the exported 3,188,639-byte JPEG matched the published master byte for byte. These files contain only synthetic test imagery/location.

This controlled scene assumes a fixed projection centre. It does not establish handheld scene alignment, parallax correction, moving-subject quality, full-size Pixel processing speed, or third-party gallery compatibility. The Pixel disconnected during implementation, so the stitching update has not yet been installed or exercised on its saved originals. The previously inspected real capture has four of 48 planned directions; it can only form a partial sphere.

To reproduce (fixture generation needs Python, Pillow and NumPy):

```powershell
python scripts/generate_stitch_fixture.py artifacts/device/stitch-fixture.zip
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-PtestRunner=app.photosphere.StitchSmokeInstrumentation'
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb push artifacts/device/stitch-fixture.zip /data/local/tmp/photosphere-stitch-fixture.zip
adb shell am instrument -w -e fixture /data/local/tmp/photosphere-stitch-fixture.zip app.photosphere.test/app.photosphere.StitchSmokeInstrumentation
```

Reports and screenshots stay under app cache `stitch-validation/<runId>/`. The test retains an explicitly marked synthetic capture. It never deletes or replaces a user's original capture. An additional explicitly selected `ExistingCaptureStitchInstrumentation` runner accepts `-e session <id> -e quality FULL` to test ordinary generation from an existing saved capture without taking photographs. Its report includes GPS verification booleans, not coordinates. The runner is absent from the normal APK.

## Real capture and capture controls — version 0.3.0

A complete 48-direction handheld capture was backed up locally before testing. It contained 53 original attempts, including five motion-rejected attempts; all originals were retained. The selected 48 accepted originals were hashed before and after generation. Private images, original manifests, location records and screenshots remain in ignored local storage, not this repository.

The installed v0.2 baseline generated a 16,640 × 8,320 JPEG in 25.7 seconds, with 40 visually matched frames, 83 verified pairs and 1.815° mean visual residual. The first joint-ray refinement generated a 17,408 × 8,704 JPEG in 27.0 seconds, with 40 matched frames, 85 pairs and 0.492° mean residual. These are single runs with dynamically available memory, not a throughput guarantee or a matched-resolution quality benchmark. Both passed GPano, saved GPS/altitude and byte-identical-original checks. Eight mostly blank views lacked verified visual matches and retained recorded orientations.

The final two-band build regenerated the same saved originals in an isolated local Android x86_64 emulator after the phone disconnected. It produced a 17,920 × 8,960 JPEG (160.6 MP, 37,104,560 bytes) in 107.1 seconds, with 40 visually matched frames, 88 pairs, 0.529° mean residual and complete rendered footprint coverage. The 1.164 fitted focal scale remains an alignment parameter, not lens calibration. Low-frequency correction used sigma 11.5 at the working seam resolution; the maximum correction was 64.70/255 and RMS 12.12/255. Visual comparison found substantially smoother flat-surface brightness while retaining sharp detail; local parallax breaks remain.

The final master passed encoded-dimension, GPano, saved GPS/altitude, unchanged-original hashes and unchanged original-directory checks. Its 974,635,008-byte estimated peak stayed within the 1,258,291,200-byte configured budget. The full-resolution JPEG and private diagnostic report were copied back to the computer. This timing is an emulator measurement, not current Pixel performance. The final two-band/brightness build has not been installed on the disconnected phone, and this final master has not yet been copied there. Prior capture/gallery controls and the preceding seam build were exercised on the Pixel.

The photographer confirmed turning around their body, rather than around the lens. This changes the viewpoint. The fitted focal scale (1.186 in the first refined run) therefore must not be interpreted as a measured lens calibration; it can absorb model error from parallax. Lower correspondence residual alone does not prove seamless scene reconstruction. Close furniture and repeated cabinet edges require visual inspection and can still have local breaks.

The physical Pixel passed portrait, landscape, reverse-landscape and portrait-return capture checks while keeping the same activity/session/Camera2 preview. Map selection, follow-aim, the accessible direction list, bounded simulated retries and permanent-error pause were exercised without requesting a photograph. The test retains its empty session for transparent lifecycle validation; the gallery hides zero-photo sessions.

The new plan has an exact spherical covering-radius check, dense-grid checks, 3° aiming perturbations, mixed portrait/landscape and roll stress, and a connected angular-overlap graph check. These establish geometry for the estimated field of view, not handheld image quality, translation tolerance or successful matching on textureless surfaces. A full 33-photo physical capture remains to be evaluated.

## Hosted CI

The first private-repository GitHub Actions job was refused before any runner or build step started. GitHub's annotation reports an account payment or spending-limit issue. Local build results above are independent of hosted CI; account billing settings were not changed.

## What remains to validate

- A person completing the new 33-stop plan, including overhead and ground targets, and the comfort of the revised capture guidance.
- Controlled movement during focus/exposure, low light, bright sky and near/far scenes.
- Optional GPS permission and a fresh physical location fix.
- Tracking and shutter speed over a longer handheld session, thermal behavior and battery use.
- Actual positional tracking with ARCore and shared-camera resolution combinations; not implemented in this milestone.
- A controlled capture around the lens centre, broader real-scene stitching quality, repeated-generation memory/thermal behavior, and external Android viewer compatibility.

Physical test photographs, raw device diagnostics and screenshots stay local and are excluded from Git. The reference panorama is also excluded.

## Re-run the physical test

Unlock a connected phone, enable USB debugging, and grant the app camera access. The test deliberately takes four photographs and keeps its test files in app cache; it never runs as part of normal app launch.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w app.photosphere.test/app.photosphere.DeviceSmokeInstrumentation
adb shell run-as app.photosphere cat cache/device-validation/report.json
```

The runner's result is explicit PASS/FAIL. Lock screens can prevent a preview surface from appearing; the runner then times out instead of reporting camera capabilities that it did not observe.
