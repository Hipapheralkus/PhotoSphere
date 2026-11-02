// PhotoSphere adapter: compile the pinned upstream CPU sources using public AAR headers.
// OpenCV's internal instrumentation, verbose logging, Eigen and CUDA are not enabled.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <set>
#include <sstream>
#include <utility>
#include <vector>
#include <opencv2/core.hpp>
#include <opencv2/core/utility.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/stitching/detail/util.hpp>
#define ENABLE_LOG 0
#define LOGLN(...) ((void)0)
#define CV_INSTRUMENT_REGION() ((void)0)
