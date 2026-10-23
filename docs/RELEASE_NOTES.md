# PhotoSphere 0.2.0 — on-device stitching and viewing

Captured originals can now become an interactive Photo Sphere on the phone. Open **Your captures → Create Photo Sphere**, choose output detail, then **Create**. When it finishes, choose **View sphere**; drag to look around, pinch to zoom and use **Export JPEG** to save the full-resolution spherical image.

- Visual feature matching refines recorded camera orientations, balances exposure and blends full-resolution originals onto a 360° × 180° canvas.
- Full detail uses source sampling and available memory, up to 20,480 × 10,240. Quicker caps width at 8,192. These are limits, not a guarantee of resolved detail.
- JPEG quality 97 with 4:4:4 colour sampling, GPano spherical metadata and saved GPS. There is no 10 MB file-size cap.
- A separate small preview keeps viewing responsive. Portrait/landscape, drag, pinch and reset are supported.
- Background progress and cancellation; failed or cancelled replacements preserve originals and the previous completed sphere.
- Partial captures remain partial: missing directions stay gray. At least two overlapping accepted originals are required.

This first stitching implementation uses a rotational model and feather blending. Nearby objects may still misalign if the lens moves, and moving subjects can leave seams. Positional tracking, neural matching, depth correction and advanced seam blending are not implemented. Keep the lens near one point and capture every planned direction for a full sphere.

The debug APK supports Android 11 or newer and can update the previous prototype while keeping captures. It includes native processing libraries and is substantially larger than the capture-only release. Export originals before uninstalling or clearing app data. Processing stays on the phone; the app has no network permission or upload service.

Local unit tests, build and lint pass. A 48-photo synthetic sphere passed visual alignment, full coverage, GPS/GPano, immutable-original, cancellation and interactive-viewer checks on an Android emulator. Physical Pixel stitching and external gallery compatibility remain to be tested. Hosted GitHub Actions remains blocked before runner startup by the account payment/spending-limit restriction. See [VALIDATION.md](VALIDATION.md) for exact scope.
