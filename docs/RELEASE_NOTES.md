# PhotoSphere 0.3.0 — simpler capture and a sphere gallery

Start a sphere, follow the guide, and open the finished result in **Gallery**. A completed capture now starts full-detail generation automatically. The gallery shows preview cards, creation progress and a one-tap sphere viewer. Less-used capture choices and original-file export live behind secondary controls.

- **Fewer stops:** the tested Pixel defaults to 33 photos instead of 48. **Capture options → Extra overlap** retains the more conservative 48-photo plan. The geometric plan adapts to the estimated camera view; visual matching still depends on scene texture and steady capture.
- **Choose what to photograph next:** turn toward any remaining direction, tap a map dot, or use the direction list. **Follow my aim** returns to automatic nearest-target selection.
- **Automatic retries:** motion rejection and known temporary camera errors retry with a fresh steady hold, up to three times. Pause remains available; persistent or storage/permission errors stop safely.
- **Steadier brightness:** a default-on capture option holds exposure and white balance after the first accepted photo. Unsupported or unsettled camera controls fall back honestly; originals record the actual lock state. RAW capture is not included.
- **Better alignment:** robust joint refinement of individual image matches and a shared focal scale improves consistency across the sphere. The fitted scale is an alignment parameter, not a physical lens calibration.
- **Cleaner joins:** constrained content-aware seams reduce broad doubling, while bounded spatial colour gains and two-band blending reduce exposure transitions. Close-range viewpoint changes can still produce local breaks.
- **Original detail and location:** full-resolution JPEG composition, quality 97 with 4:4:4 sampling, GPano spherical metadata and saved GPS. Originals remain unchanged and independently exportable.
- **Private troubleshooting:** bounded local capture/retry/stitch event history complements camera, pose and alignment reports. The prototype APK remains debuggable over an authorized USB connection. No images or diagnostics are uploaded automatically.

Keep the camera lens over one spot and move yourself around it. Turning around your body moves the lens and changes perspective, especially for close furniture. This version cannot reconstruct all depth-dependent viewpoint changes or make moving subjects simultaneous. Some seams and local breaks can remain; no positional tracking or neural scene reconstruction is claimed.

The repository and APK releases are public. Personal captures, GPS records, device logs, local screenshots, signing keys and machine-specific settings are excluded. Native build paths are replaced with neutral paths in distributed APKs. Install this update over the existing app to preserve captures; uninstalling or clearing app data removes private app files.

Stable dependencies were refreshed, including OpenCV 5.0.0.1, Kotlin 2.4.21, JUnit 6.1.3, Gradle 9.8.2 and AGP 9.5.0; build SDK/native toolchain choices and their compatibility boundaries are documented in [BUILDING.md](BUILDING.md).

See [VALIDATION.md](VALIDATION.md) for measured device results, checks and limitations.
