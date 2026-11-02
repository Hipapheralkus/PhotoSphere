# OpenCV stitching CPU support

The OpenCV Android Maven binary provides stitching headers but does not export the seam and exposure implementations used here. These three unchanged OpenCV 5.0.0 source files are compiled against the 5.0.0 public headers and binary shipped in the corrected Android Maven artifact `org.opencv:opencv:5.0.0.1`. `SOURCES.json` pins the upstream release commit and SHA-256 of each file.

`precomp.hpp` is a PhotoSphere adapter replacing OpenCV's private build precompiled header. It uses public core/geometry/imgproc/stitching headers and disables internal profiling/logging; Eigen and CUDA implementations are not enabled. The upstream algorithms are unchanged. Source copyright/licence headers remain intact and their notice is also bundled in app assets as `licenses/opencv-5.0.0/stitching-source-notices.txt`.
