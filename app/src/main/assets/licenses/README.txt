PhotoSphere - third-party licenses and notices
Snapshot verified 2026-10-23

This directory is included in the APK as assets/licenses/. The full terms and
attributions are in the accompanying text files; the summaries below do not
replace them. SOURCES.json identifies each original source, archive member and
SHA-256 checksum.

OpenCV 4.13.0
  opencv-4.13.0/LICENSE.txt
  opencv-4.13.0/components/
  The complete license directory from the official OpenCV Android SDK is
  reproduced unchanged. Its arm64-v8a and x86_64 libopencv_java4.so binaries
  match the Maven AAR used by this app byte-for-byte. The AAR itself omitted
  these notice files. The SDK set includes notices for optional components
  and alternative licenses; a notice's presence does not imply that every
  SDK component is linked into PhotoSphere.

  Components covered by this collection include:
  libjpeg-turbo / Independent JPEG Group, libpng, libtiff, OpenJPEG, OpenEXR,
  WebP, protobuf, flatbuffers, oneTBB, ITT, ADE, Android cpufeatures, Carotene,
  FLANN and KleidiCV. The original SDK also supplies IPP, SoftFloat, dlpack,
  MSCR and VASOT notices, which are preserved.

  Supplementary tagged-source notices: libwebp-COPYING.txt,
  carotene-source-notices.txt, flann-source-notices.txt,
  kleidicv-0.7.0-LICENSE.txt, kleidicv-0.7.0-copyright.txt,
  tbb-third-party-programs.txt.

  ITT is used under its BSD license option. The SDK includes both its BSD
  and GPL alternative license texts; both are retained in this collection.

This software is based in part on the work of the Independent JPEG Group.

AndroidX ExifInterface 1.4.2
  androidx-exifinterface-1.4.2-LICENSE.txt
  Apache License 2.0, copied from the published AndroidX AAR.

Kotlin standard library 2.2.10
Kotlin JDK 7/8 compatibility artifacts 1.8.20
  kotlin-stdlib-2.2.10/LICENSE.txt and COPYRIGHT.txt
  Apache License 2.0; Copyright 2010-2024 JetBrains s.r.o. and Kotlin
  Programming Language contributors.
  The applicable JVM standard-library third-party texts are also included:
  GWT (collections), Guava (unsigned numbers), ThreeTen (time), and Boost
  (mathematical functions). These mappings are documented in the Kotlin
  v2.2.10 repository's license/README.md.

JetBrains annotations 13.0
  jetbrains-annotations-13.0-copyright.txt
  Unmodified source copyright/license comment blocks from its published
  source JAR. Full Apache 2.0 terms are in kotlin-stdlib-2.2.10/LICENSE.txt.

JSpecify annotations 1.0.0
  jspecify-1.0.0-LICENSE.txt
  Apache License 2.0 from the tagged upstream source.

LLVM C++ runtime - Android NDK r28c / 28.2.13676358
  ndk-r28c-LLVM-runtime-NOTICE.txt
  Complete unmodified NOTICE from the installed NDK's LLVM toolchain,
  including libc++, libc++abi, libunwind, compiler-rt and their applicable
  legacy notices. The runtime uses Apache License 2.0 with LLVM exceptions.
  This is the LLVM toolchain's own NOTICE, not the separate NDK
  NOTICE.toolchain file for distribution tooling. The packaged runtime's
  ELF build IDs match the installed r28c runtime for both app ABIs.

Source distributions and dependency binaries were read to collect notices;
they are not bundled here. Text files from upstream are retained verbatim.
The two source-header collections and KleidiCV copyright list explicitly
identify their extraction in SOURCES.json.
