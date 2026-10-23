# Third-party notices

PhotoSphere bundles the license texts and attribution notices in
[`app/src/main/assets/licenses`](app/src/main/assets/licenses). Android packages
this directory as `assets/licenses/` in the APK. The readable index is
[`README.txt`](app/src/main/assets/licenses/README.txt); exact source URLs,
archive paths and SHA-256 checksums are recorded in
[`SOURCES.json`](app/src/main/assets/licenses/SOURCES.json).

| Component | Version used | License / bundled notices |
| --- | --- | --- |
| OpenCV Android | 4.13.0 | Apache 2.0, plus the upstream SDK's component notices and the supplementary notices listed below |
| AndroidX ExifInterface | 1.4.2 | Apache 2.0; license copied from the published AAR |
| Kotlin standard library | 2.2.10; JDK 7/8 compatibility artifacts 1.8.20 | Apache 2.0; JetBrains copyright and applicable JVM library notices for GWT, Guava, ThreeTen and Boost |
| JetBrains annotations | 13.0 | Apache 2.0; copyright notices from the published source archive |
| JSpecify annotations | 1.0.0 | Apache 2.0 |
| LLVM C++ runtime | Android NDK r28c / 28.2.13676358 | Apache 2.0 with LLVM exceptions and applicable legacy notices; complete LLVM toolchain `NOTICE`, including libc++, libc++abi, libunwind and compiler-rt |

The OpenCV 4.13.0 Maven AAR does **not** contain the distribution's license files.
Its notices are therefore copied explicitly from the
[official OpenCV 4.13.0 Android SDK](https://github.com/opencv/opencv/releases/tag/4.13.0).
The SDK and AAR `libopencv_java4.so` files were verified byte-for-byte identical
for both packaged ABIs, `arm64-v8a` and `x86_64`. The SDK's complete
`sdk/etc/licenses/` directory is preserved, including notices for libjpeg-turbo,
libpng, libtiff, OpenJPEG, OpenEXR, protobuf, flatbuffers, oneTBB, ITT, ADE,
cpufeatures and the SDK's other bundled components.

Supplementary notices cover WebP, Carotene, FLANN, KleidiCV 0.7.0, and oneTBB's
third-party programs. They come from the corresponding tagged upstream sources.
Carotene and FLANN notices retain the distinct source copyright/license comment
blocks; their source paths are included. The source of each file is recorded in
the manifest. The full SDK notice set includes optional components and
alternative license texts; its presence is not a claim that every SDK component
is linked into this app. ITT's BSD and GPL alternative texts are both preserved
as supplied by the SDK; this app uses its BSD license option.

This software is based in part on the work of the Independent JPEG Group.

The native stitching code written for PhotoSphere uses OpenCV APIs. No neural
model weights are bundled. Preserve these notices when distributing the APK,
and refresh the snapshot and source manifest when changing dependencies.
