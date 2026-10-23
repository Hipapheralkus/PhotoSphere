# Prototype validation — 23 October 2026

## Build and automated checks

Built with JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1 and Android SDK 37.0. The app targets Android 17 and requires Android 11 or newer.

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` pass. There are 13 passing JVM tests: 10 guidance/orientation/coverage tests and 3 exposure-pose association tests. Lint has no errors; remaining warnings concern English prototype strings, portrait capture and newer available tooling versions.

## Physical Pixel 10 Pro XL, Android 17

The separate androidTest instrumentation runner captured two real JPEGs in each capture mode. It checked encoded image dimensions, positive sensor timestamps, byte counts, durable session commits, rejected/orphan recovery representation, and streaming ZIP export. SHA-256 comparisons verified that exported originals matched the saved camera JPEGs byte for byte.

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

## What remains to validate

- A person completing a full sphere, including overhead and ground targets, and the comfort of the capture guidance.
- Controlled movement during focus/exposure, low light, bright sky and near/far scenes.
- Optional GPS permission and a fresh physical location fix.
- Tracking and shutter speed over a longer handheld session, thermal behavior and battery use.
- Actual positional tracking with ARCore and shared-camera resolution combinations; not implemented in this milestone.
- Stitching quality and viewer compatibility after a stitcher is implemented.

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
