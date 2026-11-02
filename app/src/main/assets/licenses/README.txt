PhotoSphere - third-party licenses and notices
Snapshot verified 2026-11-02

This directory is included in the APK as assets/licenses/. The full terms and
attributions are in the accompanying text files; the summaries below do not
replace them. SOURCES.json identifies each original source, archive member and
SHA-256 checksum.

OpenCV Android Maven 5.0.0.1 / upstream source 5.0.0
  opencv-5.0.0/LICENSE.txt
  opencv-5.0.0/components/
  The complete license directory from the official OpenCV 5.0.0 Android SDK
  (16kb-page-fix archive) is reproduced unchanged. Its arm64-v8a and x86_64
  libopencv_java5.so binaries
  match the Maven AAR used by this app byte-for-byte. The AAR itself omitted
  these notice files. The SDK set includes notices for optional components
  and alternative licenses; a notice's presence does not imply that every
  SDK component is linked into PhotoSphere.

  Components covered by this collection include:
  libjpeg-turbo / Independent JPEG Group, libpng, libtiff, OpenJPEG,
  WebP, protobuf, flatbuffers, oneTBB, ITT, Android cpufeatures, Carotene,
  FLANN and KleidiCV. The original SDK also supplies Annoy, IPP, SoftFloat,
  dlpack, MSCR and Rubik font notices, which are preserved.

  Supplementary tagged-source notices: libwebp-COPYING.txt,
  carotene-source-notices.txt, flann-source-notices.txt,
  kleidicv-26.03-LICENSE.txt, kleidicv-26.03-copyright.txt,
  tbb-third-party-programs.txt.

  opencv-5.0.0/stitching-source-notices.txt covers the three unmodified
  stitching CPU sources compiled by the app from the pinned 5.0.0 source
  commit 40738fb16ceddb5fb3fea747585f7ce6abb0605b.

  ITT is used under its BSD license option. The SDK includes both its BSD
  and GPL alternative license texts; both are retained in this collection.

This software is based in part on the work of the Independent JPEG Group.

AndroidX annotations 1.11.0
  androidx-annotation-1.11.0-LICENSE.txt
  Apache License 2.0, copied from the published AndroidX JVM JAR.

AndroidX ExifInterface 1.4.2
  androidx-exifinterface-1.4.2-LICENSE.txt
  Apache License 2.0, copied from the published AndroidX AAR.

Kotlin standard library 2.4.21
Kotlin JDK 7/8 compatibility artifacts 2.4.21
  kotlin-stdlib-2.4.21/LICENSE.txt and COPYRIGHT.txt
  Apache License 2.0; Copyright 2010-2024 JetBrains s.r.o. and Kotlin
  Programming Language contributors.
  The applicable JVM standard-library third-party texts are also included:
  GWT (collections), Guava (unsigned numbers), ThreeTen (time), and Boost
  (mathematical functions). These mappings are documented in the Kotlin
  v2.4.21 repository's license/README.md.

JetBrains annotations 26.1.0
  jetbrains-annotations-26.1.0-copyright.txt
  Unmodified source copyright/license comment blocks from its published
  source JAR. Full Apache 2.0 terms are in kotlin-stdlib-2.4.21/LICENSE.txt.

JSpecify annotations 1.0.1
  jspecify-1.0.1-LICENSE.txt
  Apache License 2.0 from the tagged upstream source.

LLVM C++ runtime - Android NDK r30 / 30.0.16248370
  ndk-r30-LLVM-runtime-NOTICE.txt
  Complete unmodified NOTICE from the installed NDK's LLVM toolchain,
  including libc++, libc++abi, libunwind, compiler-rt and their applicable
  legacy notices. The runtime uses Apache License 2.0 with LLVM exceptions.
  This is the LLVM toolchain's own NOTICE, not the separate NDK
  NOTICE.toolchain file for distribution tooling. The configured native
  toolchain is NDK30.0.16248370; APK runtime identity is validated separately.

Source distributions and dependency binaries were read to collect notices;
they are not bundled here. Text files from upstream are retained verbatim.
The two source-header collections and KleidiCV copyright list explicitly
identify their extraction in SOURCES.json.
