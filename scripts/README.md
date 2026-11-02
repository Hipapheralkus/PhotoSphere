# Local capture inspection

These optional desktop helpers inspect exported captures and experiment with registration, exposure and seams. The Android native pipeline is the supported app implementation; these scripts are diagnostics, not a separate guaranteed-quality panorama exporter.

Use Python with OpenCV, NumPy, SciPy and Pillow. Each script exposes command-line help. `generate_stitch_fixture.py` creates entirely synthetic test imagery/location; the other tools consume an explicitly selected local session or diagnostic directory. Keep real captures and derived images under ignored `artifacts/device/`. The scripts do not upload data, and no real photo fixtures are committed.

- `analyze_capture_alignment.py`: builds local SIFT overlap diagnostics.
- `refine_capture_alignment.py`: compares robust rotation/focal fits.
- `render_capture_diagnostic.py` and `test_capture_seams.py`: inspect projected source views and seam candidates.
- `analyze_photometric_balance.py`: compares bounded exposure balancing on overlaps.
- `compare_low_frequency_blending.py`: compares two-band brightness correction while retaining sharp source detail.

Experimental quality checks were developed against a portrait handheld indoor capture. Always inspect the rendering and recorded pose/EXIF conventions before drawing conclusions for a new camera, capture mode or mixed-orientation dataset. A lower feature residual does not prove physical calibration or elimination of parallax.
