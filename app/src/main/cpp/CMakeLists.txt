cmake_minimum_required(VERSION 3.22.1)
project(photosphere_stitch LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(OpenCV REQUIRED CONFIG)

add_library(photosphere_stitch SHARED photosphere_stitch.cpp
    third_party/opencv_stitching/seam_finders.cpp
    third_party/opencv_stitching/exposure_compensate.cpp
    third_party/opencv_stitching/util.cpp)
target_compile_options(photosphere_stitch PRIVATE -O3 -Wall -Wextra)
# Keep useful source locations without publishing the builder's local account/path.
# Clang applies file-prefix-map to debug information and __FILE__ in assertions.
foreach(build_home "$ENV{USERPROFILE}" "$ENV{HOME}")
    if(NOT "${build_home}" STREQUAL "")
        file(TO_CMAKE_PATH "${build_home}" normalized_build_home)
        target_compile_options(photosphere_stitch PRIVATE "-ffile-prefix-map=${normalized_build_home}=/build-user")
    endif()
endforeach()
target_compile_options(photosphere_stitch PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=/photosphere/src")
if(TARGET OpenCV::opencv_java5)
    target_link_libraries(photosphere_stitch PRIVATE OpenCV::opencv_java5)
elseif(TARGET opencv_java5)
    target_link_libraries(photosphere_stitch PRIVATE opencv_java5)
elseif(TARGET OpenCV::opencv_java4)
    target_link_libraries(photosphere_stitch PRIVATE OpenCV::opencv_java4)
elseif(TARGET opencv_java4)
    target_link_libraries(photosphere_stitch PRIVATE opencv_java4)
else()
    target_include_directories(photosphere_stitch PRIVATE ${OpenCV_INCLUDE_DIRS})
    target_link_libraries(photosphere_stitch PRIVATE ${OpenCV_LIBS})
endif()
