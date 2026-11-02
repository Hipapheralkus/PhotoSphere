# Better guidance and a full-resolution sphere

Version 0.3 implements **360° horizontal × 180° vertical** output, including above and below the camera, as a 2:1 equirectangular JPEG with spherical-viewer metadata and optional saved GPS. Capture, on-device visual registration/composition, export and interactive viewing are implemented. Positional guidance, neural matching, local parallax correction and tiled multiband blending below remain proposed work.

## Implemented stitching baseline

Completed captures start generation automatically; saved captures can be generated from Gallery. The app validates accepted originals and their poses, normalizes JPEG EXIF orientation and accounts for landscape field-of-view axes. SIFT features and mutual ratio matching run on working images up to 1280 pixels on the long side. Pose-overlap candidates include neighbouring rows and the closing 360° seam. A robust three-ray rotation fit rejects inconsistent matches; bounded robust joint ray optimization then refines rotations and a shared focal scale with sensor/gravity priors. A fitted focal scale is not a physical calibration, particularly when the lens moved through space. Low-resolution overlap patches estimate bounded spatial RGB gains and constrained graph-cut seams.

The final renderer decodes the full originals one at a time, bilinearly projects their pixels into a complete spherical canvas and blends narrowly around selected seam boundaries. A two-band exposure blend then removes broad tonal steps between views: it filters each source with a mask-normalized Gaussian at seam resolution, averages that low-frequency colour across complete overlaps, and subtracts the low-frequency blend selected by the narrow seams. The resulting compact correction field adjusts photographed output pixels while preserving original-resolution detail. Its blur scale is equivalent to sigma 12 at a 768-pixel-wide seam sphere, and each RGB correction is limited to ±80 JPEG channel values. This processing uses captured pixels and does not fill missing directions or reconstruct scene depth.

Composition keeps an RGB8 panorama and uint16 weight plane, with separate memory estimates for matching, seam/exposure preparation and composition. The two-band correction field adds at most 4.5 MiB; retained seam weights and gain grids are also accounted for. This baseline uses a bounded full canvas rather than tiled multiband composition. The output resolution is source- and memory-limited. The ordinary full-detail flow caps width at 20,480; a quicker internal validation mode caps it at 8,192. A smaller preview supports fast drag/pinch viewing while export retains master dimensions.

The visual graph must contain verified overlap. Isolated photographs or disconnected groups retain pose-assisted placement with explicit warnings. Missing directions remain neutral gray, with measured footprint coverage reported separately from target completion. GPS is copied only when a saved valid location exists; GPano metadata describes the complete canvas and does not invent a compass heading. Cancelled/failed replacements preserve originals and the previous completed sphere.

The current method assumes rotation around one projection centre. It does not refine a full calibrated lens/distortion model, remove moving people or correct depth-dependent parallax. Content-aware seam selection reduces broad doubling, and the two-band blend smooths exposure changes; neither removes geometric discontinuities caused by translating the camera. Synthetic ground-truth tests exercise rotation, mixed JPEG orientation, coverage and metadata. Real-scene quality still requires controlled captures and inspection; see [VALIDATION.md](VALIDATION.md).

## What capture guidance does now

The photographer can explore in any direction. The app chooses the closest remaining spherical target and applies a switching margin so the target does not jump between neighbours at a boundary. The plan contains horizon, upper/lower and pole coverage, but its list order does not force clockwise movement or completion of one row before another.

Portrait and both landscape orientations can be mixed within one capture. The efficient plan uses an orientation-independent inscribed footprint and 33 directions on the tested Pixel. Extra overlap retains 48 directions. The coverage bound depends on the estimated camera field of view; a narrower view falls back to the conservative latitude plan. Screen rotation updates the level guide without moving the sphere's yaw anchor. Hold progress and recent pose history reset when screen orientation changes, and an in-flight image spanning that change is retained as rejected. Final stitching must still use each JPEG's orientation, crop and raw rotation vector to reconstruct its actual footprint.

The level/aim/stillness indicators tolerate small deviations with separate entry/exit thresholds and a 120 ms exit debounce. The shutter remains strict: aim within 3°, viewfinder roll within 4° except near the poles, and angular speed at most 3°/s. Good samples accumulate 650 ms of hold time; a brief bad sample freezes the fill, a sustained deviation resets it, and capture never fires while the current pose fails these checks. This prevents display flicker from becoming a reason to accept an unsafe image. Freshness and sensor-gap checks remain immediate.

These are orientation controls. They do not yet detect camera translation, blur, missing visual overlap or stitching errors.

## Keep the optical centre in one place

The most valuable guidance improvement is to help the photographer rotate around the lens's effective projection centre. Turning the phone around the photographer's body moves the camera and changes the relative positions of nearby and distant objects. A single rotational panorama model cannot align every depth in that situation. [OpenCV's homography explanation](https://docs.opencv.org/3.4.4/d9/dab/tutorial_homography.html)

A useful approximation is angular parallax ≈ lateral displacement / scene distance, in radians. For example, a 2 cm shift relative to an object 1 m away produces about 1.15° of parallax; at 10 m it is about 0.115°. These are geometric examples, not measured tracking accuracy. They explain why indoor scenes and foreground railings need much tighter position control than distant landscapes.

Accelerometer integration alone is not a reliable basis for centimetre-scale return-to-position instructions over a complete sphere. The current orientation/gyro gate cannot distinguish holding the lens fixed from slowly moving it sideways. Its “keep the lens in one place” instruction is currently a human technique requirement.

## Add visual-inertial position guidance

An ARCore engineering spike should establish whether its tracking can share the camera with the JPEG resolutions and capture controls this app needs. Google documents shared access and occasional high-resolution still capture, but compatible simultaneous streams depend on the device. The existing Camera2 mode tests do not validate an ARCore configuration. SharedCamera also documents a restriction on using a hardware depth sensor; do not assume every depth-assisted configuration remains available. [Shared camera access](https://developers.google.com/ar/develop/java/camera-sharing), [SharedCamera API](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera)

If the spike succeeds, the proposed guidance loop is:

1. Establish a tracked start position and an anchor in a textured part of the scene. Model the offset between ARCore's camera pose and the selected capture lens where necessary.
2. Express current camera pose relative to the anchor, not a one-time copied world coordinate. Preserve the relative relationship as the tracking system refines its map. [ARCore anchors](https://developers.google.com/ar/develop/anchors)
3. Show separate rotation and position corrections: turn/tilt/roll toward the next view, and move the lens left/right, up/down, or forward/back toward the capture centre.
4. Gate the hold timer on tracking state, image support, sensor freshness, estimated displacement and uncertainty. Weak tracking pauses automatic capture rather than presenting a precise movement instruction.
5. Check consistency against overlapping saved views and the return to the first view. Detect drift or relocalization jumps and request a recovery view before accepting more images.

Near objects should tighten the allowed displacement. Use reliable scene distance and observed residual parallax when available; a universal “within 2 cm” threshold would give misleading confidence. Pose history must be associated with the exposure timestamp. The prototype now chooses the nearest orientation sample within 100 ms only when Camera2 reports the compatible `REALTIME` clock, and otherwise explicitly retains the trigger pose. Visual-inertial integration still needs more accurate timestamp association and its own camera-pose history. Higher-resolution still capture may interrupt preview/tracking, so test timestamp alignment and tracking recovery as part of the same spike.

If simultaneous tracking and detail capture is unreliable, a candidate fallback is to track while aiming, pause tracking for the still, then reacquire and register the new frame visually. That fallback needs measurement; it is not a guarantee that tracking remains continuous.

## Improve stitching from the actual originals

The native mobile baseline now consumes existing saved sessions directly. Use exported originals and reproducible test scenes to evaluate the following improvements before adding their runtime cost to the phone pipeline.

The longer-term pipeline is:

1. **Read and calibrate.** Honour each JPEG's orientation, camera crop, focal length and available distortion parameters. Portrait and landscape files may occur in any capture order. Keep all original images immutable. Read `accepted` and pose-association metadata: rejected captures remain in the archive but do not satisfy target coverage. Use accepted captures as the initial set; retain rejected images for inspection or deliberate recovery. Select overlapping neighbours from actual viewing directions and image footprints, including cross-row neighbours and the closing 360° pair; consecutive filenames need not depict neighbouring views. Pose roll is normalized to the current viewfinder, so preserve the distinction between that UI measurement and the original device rotation vector.
2. **Match and reject.** Extract features on working-resolution copies, match overlap regions, and use geometric verification to reject inconsistent correspondences. Preserve the coordinate transform back to the original images.
3. **Optimize globally.** Refine camera rotations and appropriate intrinsic parameters across the whole overlap graph. Include loop closure, horizon and pole coverage checks. Sensor poses are initial estimates, not unquestionable ground truth. A pure-rotation model is the baseline; substantial translation needs a model that accounts for scene structure.
4. **Handle residual local errors.** Test constrained local warps or depth-aware reprojection where supporting evidence is strong. Preserve straight structures and reject folds or excessive stretching. Local warps can reduce moderate alignment errors, but cannot recover unseen surfaces or make inconsistent moving subjects simultaneous. [APAP stitching research](https://openaccess.thecvf.com/content_cvpr_2013/html/Zaragoza_As-Projective-As-Possible_Image_Stitching_2013_CVPR_paper.html)
5. **Choose seams and balance colour.** Prefer sharp, stationary content; compensate exposure and colour differences; route seams away from difficult foreground boundaries and moving subjects. Multiband blending smooths residual transitions after geometric alignment. OpenCV's detailed sample provides separable estimation, seam, exposure and composition stages suitable for a baseline. [OpenCV detailed stitching pipeline](https://docs.opencv.org/4.13.0/d9/dd8/samples_2cpp_2stitching_detailed_8cpp-example.html)
6. **Compose at source-supported resolution.** Reproject the original JPEGs into output tiles. Working-resolution matching must not silently become working-resolution export. Use bounded caches and tiled multiband blending instead of allocating a full panorama and every source image at once.

A flattened, already stitched reference is useful for cataloguing seams and creating a regression image set. It does not contain all of the independent overlapping views needed to redo the original alignment. It therefore cannot replace a new capture dataset for evaluating the pipeline.

## Where neural matching fits

An optional experiment is **ALIKED or DISK feature extraction followed by LightGlue matching**, compared with a classical feature baseline on the same captures. LightGlue operates on sparse features; it is not a complete panorama generator. Keep geometric verification, global optimization, seam selection and final reprojection explicit. Desktop matching benchmarks do not establish Android speed. [LightGlue project](https://github.com/cvg/LightGlue)

LightGlue's own code and pretrained matcher weights are Apache-2.0. Its maintainers list DISK under Apache-2.0 and ALIKED under BSD-3-Clause. They specifically distinguish SuperPoint's more restrictive licensing, including its weights and inference code. The original Magic Leap SuperPoint release restricts use to noncommercial research. Select and pin the exact extractor, implementation and weights before bundling them; the matcher's licence does not replace their licences. [LightGlue licensing](https://github.com/cvg/LightGlue#license), [SuperPoint licence](https://github.com/magicleap/SuperPointPretrainedNetwork/blob/master/LICENSE)

Neural features may improve match coverage in difficult scenes. They cannot create missing overlap, remove all parallax, or reconstruct an occluded surface from a single flattened panorama. Accept the experiment only if it improves verified alignment or completion rate on this project's images at an acceptable runtime and memory cost.

## Resolution, memory and compatibility

Output quality is determined by captured angular detail, focus, motion, alignment and compression. Ten megabytes is a file size, not a resolution limit. Overlap means the sum of source megapixels is not the number of unique panorama pixels.

The following are illustrative output sizes, not promised detail levels:

| Equirectangular image | Pixel count | One uncompressed RGBA buffer |
| --- | --- | --- |
| 16,384 × 8,192 | 134.2 MP | 512 MiB |
| 32,768 × 16,384 | 536.9 MP | 2 GiB |

Blending pyramids, working buffers and source images need additional memory. This is why a mobile implementation should process tiles, cache only necessary decoded regions, and generate a smaller interactive preview separately. Choose final dimensions from actual source sampling and measured detail rather than upscaling to a large marketing number.

The implemented export is a 2:1 JPEG with GPano XMP fields describing equirectangular projection, full/cropped dimensions and viewer usage, plus saved EXIF location where available. Partial captures retain the full spherical canvas, leave unphotographed areas gray and report estimated coverage. Test the exported image in the user's actual Android viewers; metadata support, maximum texture sizes and resampling differ by viewer. An original-image ZIP is also available. [Google spherical metadata specification](https://developers.google.com/streetview/spherical-metadata)

## Milestones and acceptance evidence

| Milestone | Evidence required before claiming success |
| --- | --- |
| Capture foundation | Both modes return original JPEGs at reported dimensions; repeated capture and close/reopen work; archive hashes equal original hashes; incomplete writes remain identifiable. Record actual device sizes and timing. |
| Responsive guidance | Turn left/right/up/down and confirm the target follows intent without boundary flicker. Test brief jitter, sustained movement, repeated/stale samples, and a bad pose at full hold progress. Check portrait/landscape changes preserve the yaw anchor, reset the hold, reject in-flight orientation changes, and keep actual photographed coverage complete. |
| Exposure-time pose | The prototype has clock-checked nearest-sample association, ±1-second sample windows, and a post-capture aim/roll/speed check when association succeeds. Test deliberate motion during autofocus/exposure delay, rejection without coverage advancement, byte-preserving export of rejected originals, and honest fallback for UNKNOWN clocks or gaps. Improve the present 100 ms association bound with interpolation and exposure-duration motion analysis before claiming precise pose. |
| Visual-inertial guidance | Compare stationary rotation and measured translations near/far from textured objects; verify correction direction, drift after a full turn, tracking-loss behaviour, and recovery after detail capture. |
| Baseline complete sphere | Stitch a static scene captured around a controlled centre; close the first/last seam; cover zenith/nadir; inspect straight edges, overlap residuals and actual resolved detail. |
| Better matching/warps | Compare identical source sets against the baseline: difficult texture, nearby railings, mixed lighting, moving people and repeated patterns. Report failures and excess deformation as well as average improvement. |
| Mobile composition | Measure peak memory, processing time and thermal behaviour over repeated full spheres; cancel/restart without losing originals; inspect tile boundaries and final viewer compatibility. |

Capture profiling should separate aiming/hold time, autofocus/exposure delay, camera/JPEG latency, file sync and manifest commit. Record median and tail latency, preview frame timing and memory over a sustained sequence. The 650 ms of qualifying hold time is a deliberate stability requirement; brief outliers pause its accumulation, and the timer should not hide processing stalls. Current exposure-pose rejection can flag angular motion or loss of alignment near a sampled exposure, but does not measure translation or prove that the whole exposure was blur-free. Separate synthetic stitching tests validate reconstruction against known geometry; they do not establish handheld real-scene quality or 6DoF tracking.
