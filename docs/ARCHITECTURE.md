# Capture, stitching and viewing architecture

The native Android prototype guides capture in any direction around the whole sphere, preserves camera-produced JPEGs, stitches them on the phone, and displays/exports the resulting Photo Sphere. The first hardware target is Pixel 10 Pro XL running Android 17; actual device results belong in [VALIDATION.md](VALIDATION.md).

The application uses Kotlin, Camera2, an OpenCV 4.13.0 C++ pipeline, AndroidX ExifInterface 1.4.2 and a GLES sphere viewer. Camera2 provides explicit stream configurations, capture metadata and control over advertised higher-resolution sensor modes.

## Components and execution

| Component | Responsibility | Execution |
| --- | --- | --- |
| `MainActivity` | Capture screen, permission flow, session state, pause/finish, export picker | Main thread; session operations submitted to its IO executor |
| `OrientationTracker` | Rotation-vector orientation and gyroscope angular speed | Sensor callbacks; small matrix calculations |
| `PoseHistory` | Bounded sensor history and exposure-timestamp association | Main thread; at most 2,000 samples |
| `CapturePlan`, `TargetSelector`, `Guidance` | Spherical target placement, nearest remaining target, stable display feedback and strict capture gate | Pure Kotlin calculations |
| `CaptureOverlay`, `SphereMapView` | Projected target, roll guide, progress and coverage diagram | Canvas drawing on the UI thread |
| `CameraEngine` | Camera selection, preview, full JPEG capture, timestamp pairing | Camera handler thread; separate JPEG file executor |
| `SessionStore` | Atomic manifest commits, recovery inventory, streaming export | Application IO executor |
| `StitchRepository`, `StitchSizing` | Validate accepted originals, choose output dimensions, persist job state and result pointer | IO worker; observer callbacks on main |
| `StitchService`, `NativeStitcher` | Foreground job, cancellation, visual registration and original-resolution composition | Dedicated worker; bounded native parallelism |
| `PanoramaMetadata` | GPano XMP and optional EXIF GPS without pixel recompression | Stitch worker |
| `PanoramaViewerActivity`, `SphereViewerView` | Interactive preview and master JPEG export | GLES render thread, background decoding/export |

The live preview and saved photographs are separate streams. Preview dimensions are capped at 1920 × 1080 or a smaller supported size with a suitable aspect ratio. A fit-centre transform preserves the full frame without stretching. The overlay uses the corresponding frame bounds so its pinhole projection does not include the letterbox bars. Camera readiness requires both capture-result metadata and a presented TextureView frame.

Sensor updates only perform orientation/guidance calculations and update lightweight views. There is no full-resolution bitmap decoding, feature extraction, stitching, or neural inference in the capture loop. JPEG bytes are copied from the camera's compressed ImageReader output; no application recompression is performed.

## Capture resolution and camera state

The camera selector prefers the default rear logical camera and a normal wide field of view. Requests use 1× zoom where the API exposes zoom-ratio control. The actual physical camera ID and focal length are recorded when supplied by the camera.

`FAST`, labelled Everyday in the interface, starts with the largest JPEG size in the regular stream map. `DETAIL` additionally considers the high-resolution sizes and the separate maximum-resolution stream map. Candidates are tried in descending pixel count, with session-support checks and actual configuration callbacks. Fallbacks are included in `CameraInfo.diagnosticsJson.notices`. A marketed 50 MP sensor does not establish which JPEG sizes this app can actually capture. [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)

A maximum-resolution JPEG surface is explicitly marked for that sensor mode. Its still request targets only the JPEG surface; it does not combine a maximum-resolution-only surface with an incompatible normal preview surface in one request. The repeating preview remains active. [CaptureRequest sensor pixel mode](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_PIXEL_MODE), [OutputConfiguration](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration)

There is one in-flight capture per engine. The engine waits for autofocus and autoexposure readiness, with a bounded 1.2-second wait before taking the image and recording that the wait expired. The outer camera-result/image deadline is 15 seconds in ordinary mode or 30 seconds in maximum-resolution mode. These are failure deadlines, not deliberate per-photo delays. Normal ready captures proceed immediately. JPEG quality is requested as 97; the camera determines the encoding.

JPEG images and total capture results are paired by their exact sensor timestamp. A camera failure, missing timestamp, missing image, timeout, or save failure produces an explicit error callback. Completion callbacks run on the main thread. Camera closure cancels work still awaiting the camera, while a JPEG already being written is allowed to finish. Closing during `openCamera` keeps the callback thread alive long enough to close a subsequently returned device.

## Guidance and its present limits

The current tracker uses Android's rotation vector, falling back to the game rotation vector, plus gyroscope angular speed. The capture session sets a relative yaw origin. It does **not** estimate lateral, vertical, or forward camera displacement.

The target planner uses the shorter of the selected JPEG's two estimated fields of view on both axes. It places overlapping horizon, upper, lower, zenith, and nadir views with a step factor of 0.55. This conservative footprint allows portrait and landscape photographs within one session without depending on a fixed rectangular orientation. It requires more targets than a plan optimized for portrait alone; count depends on the camera. This is planned geometric coverage, not a measurement that every scene pixel was captured successfully.

Target IDs describe a coverage plan, not a required visiting order. `TargetSelector` chooses the closest uncaptured target using spherical angular distance, so the photographer can turn left, right, up or down. It retains the previous choice near boundaries until another target is at least 8° closer. A target already centred within 3° can also take over when the old target is outside 4.5°; a large turn away from the old target allows an earlier switch. Captured targets are excluded.

The strict shutter conditions are:

- Angular aiming error at most 3°.
- Roll within 4°, except for targets at or above 75° absolute pitch, where roll is not a useful constraint.
- Gyroscope speed at most 3° per second.
- Fresh sensor data and 650 ms of qualifying hold time.

Display indicators use hysteresis: aiming enters at 3° and exits beyond 4.5°, level enters at 4° and exits beyond 6°, and stillness enters at 3°/s and exits beyond 6°/s. Exits must persist for 120 ms. This keeps a tiny deviation from immediately flashing a different instruction. These forgiving indicators never authorize capture: `GuidanceState.captureSafe` checks the strict instantaneous conditions, and `ready` also requires the completed hold.

A brief outlier freezes hold progress; it does not add qualifying time. An excursion lasting 120 ms resets progress, while stale data, backwards clocks or a sensor delivery gap reset it immediately. Repeated copies of an old sensor sample cannot advance the timer. Accepting a shot latches the gate until the controller changes target or retries. Magnetic disturbances and yaw drift can still affect pointing. The displayed camera field of view is an estimate derived from camera characteristics and observed preview focal length/crop.

The controller shows the hold ring only while the pose is strictly safe or within the brief settling grace. A sustained near miss reveals the target and a centring, level, or stillness instruction even if it remains inside the wider display hysteresis band. This avoids an indefinitely empty hold ring.

The activity accepts all four display rotations and handles orientation changes without recreating the capture session. `OrientationTracker.setDisplayRotation` corrects the displayed roll while preserving optical yaw, pitch and the session yaw anchor. For `Surface.ROTATION_90`, a physical counter-clockwise phone turn is compensated by adding 90° to the measured clockwise roll. The raw rotation vector remains unchanged for later processing. On a display change the controller resets hold progress and recent pose history; a capture spanning that change is preserved but rejected for coverage. [Android display rotation convention](https://developer.android.com/reference/android/view/Display#getRotation())

`PoseHistory` retains up to 2,000 orientation samples. After capture, the activity checks the camera's `SENSOR_INFO_TIMESTAMP_SOURCE`. Only `REALTIME` permits comparison with Android sensor timestamps. When a nearest sample is within 100 ms of the exposure timestamp, that sample becomes the recorded pose; metadata records `poseAssociation: sensor_timestamp_nearest` and the signed `poseTimestampOffsetNs`. This is nearest-sample association, not interpolation or an exact exposure-time pose.

If the camera clock is `UNKNOWN` or no sufficiently close sample exists, the original trigger pose is retained and labelled `capture_request_only`, with a null offset. The capture-request pose timestamp is always recorded. Available history within ±1 second of the exposure, or of the trigger for the fallback case, is exported as `poseSamples`. The window may be incomplete when capture finishes quickly or tracking has stopped.

For a successfully associated exposure pose, the activity rechecks the strict aim, roll and angular speed conditions after the JPEG arrives. If the phone moved outside those tolerances while autofocus, autoexposure or capture was underway, the original and metadata are retained with `accepted: false`; the target stays uncaptured and the interface asks for a retake. A trigger-pose fallback cannot establish exposure-time movement and is accepted without this additional check, provided the display rotation did not change during capture. Neither path measures camera translation or evaluates image sharpness.

## Durable originals and session records

Files live under private application storage:

```text
files/sessions/<session-id>/
  manifest.json
  originals/
    target_0000_<unique-id>.jpg
    target_0001_<unique-id>.jpg
```

Each attempt gets a unique original filename. CameraEngine writes to a temporary file in the destination directory, syncs the file descriptor, and moves the file into place. SessionStore then checks the byte count and JPEG boundary markers before committing a new manifest using Android `AtomicFile`. Coverage counts include unique target IDs with accepted captures and advance only after the manifest commit succeeds. Rejected photographs remain stored but do not complete targets. The manifest is replaced atomically; it is not an append-only log.

Schema version 1 records the target plan, quality, camera configuration and diagnostics, application/device versions, per-capture source path, accepted flag, associated or fallback pose, nearby pose samples, sensor timestamp, JPEG dimensions/orientation, byte count, timing and optional location. Available camera metadata includes pose-association source/offset, capture-request pose timestamp, exposure-pose acceptance, exposure, ISO, focus, focal length, crop, distortion/intrinsic calibration, rolling-shutter skew, physical camera ID, stabilization, and colour-correction values. Optional camera fields may be absent. Missing accepted flags in earlier version-1 manifests are treated as accepted for compatibility.

The manifest's `projection: equirectangular` and `intendedCoverage` fields describe the intended future output. The files in `originals/` remain ordinary individual camera photographs.

Location is optional. With permission and a fresh location, the capture request supplies `JPEG_GPS_LOCATION` and the manifest stores coordinates, available altitude/accuracy, and fix time. The activity currently accepts fixes up to five minutes old. Actual EXIF preservation and downstream GPS visibility should be checked on exported files.

## Recovery, lifecycle and export

An application interruption preserves a partial session. The prototype does not resume a previous orientation coordinate system after a sensor restart; a new sphere gets a new anchor. In-screen pause/resume and handled portrait/landscape changes retain the active session while tracking remains available. Activity callbacks check lifecycle/generation state before updating a capture screen or scheduling new session work. If the activity is destroyed before a saved JPEG receives its manifest record, original-file recovery remains available.

Session listing reads AtomicFile recovery data and checks complete originals. A complete JPEG left behind by an interrupted manifest update remains recoverable. Export includes it with an explicit entry in `recovery.json`; it does not invent a missing pose record. Rejected originals are also exported and listed under `rejectedOriginals`, while coverage and completion counts use accepted captures only. Incomplete files and temporary writes are excluded.

ZIP export streams originals with a 64 KiB copy buffer. Compression level zero avoids spending CPU recompressing JPEG payloads. The archive contains `manifest.json` when readable, `recovery.json`, and complete original JPEGs. Camera diagnostics are embedded in the manifest. The app streams the ZIP to a user-selected document destination rather than buffering the archive in memory.

## Verification boundary

`GuidanceTest` exercises spherical coverage with mixed image orientations, free target selection and boundary hysteresis, pole/wrap geometry, all four display rotations, brief jitter versus strict shutter safety, sustained departures, repeated-shot latching, stale/repeated samples, invalid values, and clock/delivery gaps. `PoseHistoryTest` checks exposure association and trigger-pose fallbacks. `DeviceSmokeInstrumentation` is a separate explicitly invoked test runner that opens both quality modes, captures originals, checks JPEG dimensions/timestamps, commits temporary sessions, and verifies byte-identical ZIP payloads with SHA-256.

`StitchSmokeInstrumentation` imports generated images with known geometry, mixed display/EXIF rotations and synthetic GPS. It checks the public generation flow, actual output against a ground-truth sphere, metadata, byte preservation and cancellation. This synthetic scene contains pure rotation; it does not validate real camera translation or moving objects. Use actual results in [VALIDATION.md](VALIDATION.md).

## Stitch input and output

Only accepted photos with manifest poses enter generation. The latest valid accepted record for each target is selected. Paths must resolve inside the session originals directory; JPEG boundaries, byte counts, dimensions and finite poses are checked. Orphaned or rejected photos remain recoverable/exportable but are not silently assigned an invented pose. EXIF orientation normalizes decoded images, and capture-time display rotation swaps the recorded field-of-view axes for landscape shots. Legacy portrait manifests without this field remain supported.

The native pipeline extracts SIFT features at working resolution, considers overlap candidates by spherical direction (including closing-seam and cross-row neighbours), verifies a relative rotation from matched rays, and refines the rotation graph with weak sensor priors. Connected components retain an orientation anchor; unconnected sources/components are explicitly reported. It applies scalar exposure correction and feather blending. Intrinsics are fixed estimates from captured fields of view; this baseline does not yet optimize lens distortion, select content-aware seams or repair depth-dependent parallax.

Composition reads one full-resolution original at a time into a full 2:1 RGB canvas and a uint16 weight plane. It does not compose from feature thumbnails. The source sampling bound uses the smaller focal sampling axis, converted to pixels per full turn. Full detail is bounded by that estimate, the native memory budget and width 20,480; Quicker also caps width at 8,192. Widths are rounded down to multiples of 256. This is a sampling bound, not measured optical resolving power. The native budget is at most 1,200 MiB and one quarter of currently available system RAM. The sizing reserve includes the largest decoded original and 128 MiB of working space, in addition to five bytes per output pixel. Low memory reduces resolution or produces an explicit error.

The full canvas always spans 360° × 180°. Missing footprints remain neutral gray; estimated coverage is weighted by solid angle and is separate from captured target count. The master is JPEG quality 97 with 4:4:4 chroma sampling. A bounded quality-90 preview is generated for interactive viewing. The master gets GPano equirectangular/full-canvas metadata, source count, capture time and optional saved EXIF GPS. Relative yaw does not establish true north, so no absolute heading is fabricated. Metadata updates do not recompress the JPEG scan data.

```text
files/sessions/<session-id>/
  stitch-state.json
  stitch-work/<job-id>/                 # unpublished current work
  stitch-results/<completed-job-id>/
    panorama.jpg
    panorama-preview.jpg
    details.json
  panorama.json                         # atomic pointer to the published result
```

A `dataSync` foreground service owns one generation job at a time. Disk and native work never run on the UI thread. Progress notifications and observers are throttled, cancellation is cooperative, and the service has bounded wake-lock/time-out handling. Originals are never deleted. A completed result is published only after JPEG dimensions, report, metadata and files validate. The atomic result pointer is the commit point; failure/cancellation leaves the previous published sphere usable. A process restart labels unfinished work interrupted; users can regenerate from originals. Completed immutable result folders can retain older generations; automatic storage reclamation is not yet implemented.

The viewer decodes the smaller preview, bounds its texture to available GL limits and renders on demand. Drag/pinch adjusts the spherical camera; double tap resets it. The UI clearly labels preview size separately from master detail. Export streams the master through the system document picker without reducing its dimensions. The original ZIP remains an independent export and does not substitute the flattened panorama for source files.
