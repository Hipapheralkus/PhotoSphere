# Third-party notices

PhotoSphere bundles the license texts and attribution notices in
[`app/src/main/assets/licenses`](app/src/main/assets/licenses). Android packages
this directory as `assets/licenses/` in the APK. The readable index is
[`README.txt`](app/src/main/assets/licenses/README.txt); exact source URLs,
archive paths and SHA-256 checksums are recorded in
[`SOURCES.json`](app/src/main/assets/licenses/SOURCES.json).

| Component | Version used | License / bundled notices |
| --- | --- | --- |
| OpenCV Android | Maven 5.0.0.1; upstream source 5.0.0 | Apache 2.0, plus the upstream SDK's component notices and the supplementary notices listed below |
| AndroidX annotations | 1.11.0 | Apache 2.0; license copied from the published JVM JAR |
| AndroidX ExifInterface | 1.4.2 | Apache 2.0; license copied from the published AAR |
| Kotlin standard library | 2.4.21; JDK 7/8 compatibility artifacts 2.4.21 | Apache 2.0; JetBrains copyright and applicable JVM library notices for GWT, Guava, ThreeTen and Boost |
| JetBrains annotations | 26.1.0 | Apache 2.0; copyright notices from the published source archive |
| JSpecify annotations | 1.0.1 | Apache 2.0 |
| LLVM C++ runtime | Android NDK r30 / 30.0.16248370 | Apache 2.0 with LLVM exceptions and applicable legacy notices; complete LLVM toolchain `NOTICE`, including libc++, libc++abi, libunwind and compiler-rt |

The OpenCV 5.0.0.1 Maven AAR does **not** contain the distribution's license files.
Its notices are therefore copied explicitly from the
[official OpenCV 5.0.0 Android SDK, corrected for 16 KiB pages](https://github.com/opencv/opencv/releases/download/5.0.0/opencv-5.0.0-android-sdk-16kb-page-fix.zip).
The SDK and AAR `libopencv_java5.so` files were verified byte-for-byte identical
for both packaged ABIs, `arm64-v8a` and `x86_64`. The SDK's complete
`sdk/etc/licenses/` directory is preserved, including notices for libjpeg-turbo,
libpng, libtiff, OpenJPEG, protobuf, flatbuffers, oneTBB, ITT, Annoy,
cpufeatures and the SDK's other bundled components.

Supplementary notices cover WebP, Carotene, FLANN, KleidiCV 26.03, and oneTBB's
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

The app also compiles three unchanged OpenCV 5.0.0 stitching CPU source files (`seam_finders.cpp`, `exposure_compensate.cpp`, `util.cpp`) from pinned commit `40738fb16ceddb5fb3fea747585f7ce6abb0605b`. Their BSD-style notices are bundled in `licenses/opencv-5.0.0/stitching-source-notices.txt`; source hashes and the public-header build adapter are documented under `app/src/main/cpp/third_party/opencv_stitching/`.

JVM unit tests use JUnit Jupiter 6.1.3 under the [Eclipse Public License 2.0](https://github.com/junit-team/junit-framework/blob/r6.1.3/LICENSE.md). JUnit and its test-only dependencies are not included in the Android APK.
