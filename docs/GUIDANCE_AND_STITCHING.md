# Better guidance and a full-resolution sphere

The intended result is a full **360° horizontal × 180° vertical** photograph, including above and below the camera, exported as a 2:1 equirectangular image with spherical-viewer metadata and optional GPS. The current application captures the originals and metadata needed to develop that pipeline. Positional guidance, visual registration, neural matching, stitching, and spherical image export described below are proposed work, not implemented features.

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

## Stitch from the actual originals

Start with a reproducible desktop processing pipeline consuming the existing ZIP format. This makes geometry and image-quality failures easier to inspect before introducing Android memory and thermal constraints. Port the proven components to a native background pipeline afterwards.

The proposed stages are:

1. **Read and calibrate.** Honour JPEG orientation, camera crop, focal length and available distortion parameters. Keep all original images immutable. Read `accepted` and pose-association metadata: rejected captures remain in the archive but do not satisfy target coverage. Use accepted captures as the initial set; retain rejected images for inspection or deliberate recovery. Select useful overlapping neighbours from the target plan and pose hints, including cross-row neighbours and the closing 360° pair.
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

The interoperable target is a 2:1 JPEG with GPano XMP fields describing equirectangular projection, full/cropped dimensions and viewer usage, plus EXIF location where enabled. Partial captures must retain honest crop/coverage information. Test the exported image in the user's actual Android viewers; metadata support, maximum texture sizes and resampling differ by viewer. The prototype currently exports an original-image ZIP, not this spherical JPEG. [Google spherical metadata specification](https://developers.google.com/streetview/spherical-metadata)

## Milestones and acceptance evidence

| Milestone | Evidence required before claiming success |
| --- | --- |
| Capture foundation | Both modes return original JPEGs at reported dimensions; repeated capture and close/reopen work; archive hashes equal original hashes; incomplete writes remain identifiable. Record actual device sizes and timing. |
| Exposure-time pose | The prototype has clock-checked nearest-sample association, ±1-second sample windows, and a post-capture aim/roll/speed check when association succeeds. Test deliberate motion during autofocus/exposure delay, rejection without coverage advancement, byte-preserving export of rejected originals, and honest fallback for UNKNOWN clocks or gaps. Improve the present 100 ms association bound with interpolation and exposure-duration motion analysis before claiming precise pose. |
| Visual-inertial guidance | Compare stationary rotation and measured translations near/far from textured objects; verify correction direction, drift after a full turn, tracking-loss behaviour, and recovery after detail capture. |
| Baseline complete sphere | Stitch a static scene captured around a controlled centre; close the first/last seam; cover zenith/nadir; inspect straight edges, overlap residuals and actual resolved detail. |
| Better matching/warps | Compare identical source sets against the baseline: difficult texture, nearby railings, mixed lighting, moving people and repeated patterns. Report failures and excess deformation as well as average improvement. |
| Mobile composition | Measure peak memory, processing time and thermal behaviour over repeated full spheres; cancel/restart without losing originals; inspect tile boundaries and final viewer compatibility. |

Capture profiling should separate aiming/hold time, autofocus/exposure delay, camera/JPEG latency, file sync and manifest commit. Record median and tail latency, preview frame timing and memory over a sustained sequence. The 650 ms hold is a deliberate stability requirement; it should not hide processing stalls. Current exposure-pose rejection can flag angular motion or loss of alignment near a sampled exposure, but does not measure translation or prove that the whole exposure was blur-free. Current smoke instrumentation validates capture/storage mechanics, not the future stitching-quality or 6DoF milestones.
