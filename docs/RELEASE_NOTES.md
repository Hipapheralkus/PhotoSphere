# PhotoSphere 0.1.1 — guided capture prototype

- Smoother pointing guidance and a steady-hold ring that tolerates brief jitter.
- Automatic portrait and landscape layouts, including rotation during a capture.
- Turn left, right, up, or down; the guide selects the nearest remaining direction.
- Full-resolution camera JPEGs, optional GPS, motion/camera metadata, and original-preserving ZIP export.
- Camera capability checks and honest resolution fallback on each phone.

Install the debug APK on Android 11 or newer. This update can replace the first prototype without deleting existing captures. Export originals before uninstalling or clearing app data.

The initial Pixel 10 Pro XL / Android 17 test captured 4080 × 3072 JPEGs in both modes. Larger output was not exposed by the selected public camera configuration. Capture and save took approximately 1.3 seconds in four test samples; autofocus reached its deadline in that scene. These are functional samples, not a general speed or sharpness guarantee.

This release captures and exports source images. Stitching, positional drift tracking and neural matching are not implemented yet. Keep the lens near one fixed point as you turn. The app has no network permission or upload service.

Local build, unit tests and Android lint are checked. The hosted GitHub Actions job could not start because GitHub reported an account payment or spending-limit issue. See docs/VALIDATION.md for the exact validation scope.
