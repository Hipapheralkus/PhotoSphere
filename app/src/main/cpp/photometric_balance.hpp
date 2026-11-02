#pragma once

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <utility>
#include <vector>

namespace photosphere {

struct PhotometricBalance {
    // Compact CV_32FC3 grids, one per cropped image. To apply to a pixel in the
    // original ROI, bilinearly sample with clamped borders at
    // ((x + .5) * grid.cols / roi.width - .5, (y + .5) * grid.rows / roi.height - .5).
    // This is the half-pixel convention used by cv::resize(..., INTER_LINEAR).
    std::vector<cv::Mat> gainMaps;
    double maximumGain = 1.0;
};

inline PhotometricBalance balanceExposure(std::vector<cv::Mat>& imagesBgr,
                                         const std::vector<cv::Point>& corners,
                                         const std::vector<cv::Mat>& masks) {
    if (imagesBgr.size() != corners.size() || imagesBgr.size() != masks.size())
        throw std::invalid_argument("Exposure images, corners and masks must have equal counts");
    PhotometricBalance balanced;
    if (imagesBgr.empty()) return balanced;

    // BlocksChannelsCompensator solves a dense system over all blocks. Bounding
    // the number of nodes prevents many large/wrapped ROIs from making that
    // solve unexpectedly quadratic in memory. Retained maps use <= 6 KiB.
    constexpr int maxBlocks = 512;
    if (imagesBgr.size() > maxBlocks)
        throw std::invalid_argument("Too many images for bounded exposure balancing");
    int largestDimension = 1;
    for (size_t i = 0; i < imagesBgr.size(); ++i) {
        if (imagesBgr[i].empty() || imagesBgr[i].type() != CV_8UC3 ||
            masks[i].type() != CV_8UC1 || masks[i].size() != imagesBgr[i].size())
            throw std::invalid_argument("Exposure balancing requires BGR8 images and matching 8-bit masks");
        largestDimension = std::max({largestDimension, imagesBgr[i].cols, imagesBgr[i].rows});
    }
    const auto blockCount = [&](int side) {
        int64_t count = 0;
        for (const cv::Mat& image : imagesBgr)
            count += static_cast<int64_t>((image.cols + side - 1) / side) *
                     ((image.rows + side - 1) / side);
        return count;
    };
    int blockSide = 32;
    while (blockCount(blockSide) > maxBlocks && blockSide < largestDimension)
        blockSide += 8;

    std::vector<cv::UMat> smoothed;
    std::vector<std::pair<cv::UMat, uchar>> reliableMasks;
    smoothed.reserve(imagesBgr.size());
    reliableMasks.reserve(imagesBgr.size());
    const cv::Mat kernel = cv::Mat::ones(3, 3, CV_8UC1);
    for (size_t i = 0; i < imagesBgr.size(); ++i) {
        cv::UMat smooth, stable;
        cv::GaussianBlur(imagesBgr[i], smooth, cv::Size(3, 3), 0.8);
        // Clipped highlights and deep shadows cannot reliably identify exposure
        // ratios. Erosion also excludes remap boundary/interpolation pixels.
        cv::inRange(smooth, cv::Scalar(12, 12, 12), cv::Scalar(244, 244, 244), stable);
        cv::bitwise_and(stable, masks[i], stable);
        cv::erode(stable, stable, kernel);
        smoothed.push_back(smooth);
        reliableMasks.emplace_back(stable, 255);
    }

    cv::detail::BlocksChannelsCompensator compensator(blockSide, blockSide, 1);
    compensator.setNrGainsFilteringIterations(2);
    // Reject strongly different overlap colors (moving objects/parallax) before
    // the channel gain solve. This does not repair the geometric disagreement.
    compensator.setSimilarityThreshold(0.3);
    compensator.feed(corners, smoothed, reliableMasks);
    compensator.getMatGains(balanced.gainMaps);
    smoothed.clear();
    reliableMasks.clear();
    if (balanced.gainMaps.size() != imagesBgr.size())
        throw std::runtime_error("Exposure balancing did not return every source image");

    // Solve in JPEG color space, then bound correction. Local adjustment should
    // remove seams without the previous scalar solver's near-2x amplification.
    constexpr float minimumGain = 0.70f;
    constexpr float maximumGain = 1.35f;
    for (size_t i = 0; i < imagesBgr.size(); ++i) {
        cv::Mat& grid = balanced.gainMaps[i];
        if (grid.empty() || grid.type() != CV_32FC3)
            throw std::runtime_error("Exposure balancing returned an invalid gain grid");
        for (int y = 0; y < grid.rows; ++y) {
            auto* row = grid.ptr<cv::Vec3f>(y);
            for (int x = 0; x < grid.cols; ++x) {
                for (int channel = 0; channel < 3; ++channel) {
                    float& gain = row[x][channel];
                    gain = std::isfinite(gain) ? std::clamp(gain, minimumGain, maximumGain) : 1.f;
                    balanced.maximumGain = std::max(balanced.maximumGain, static_cast<double>(gain));
                }
            }
        }
        // Only this one low-resolution ROI is expanded at a time. Original JPEG
        // bytes and the source images used by final composition remain untouched.
        cv::Mat expanded;
        cv::resize(grid, expanded, imagesBgr[i].size(), 0, 0, cv::INTER_LINEAR);
        for (int y = 0; y < imagesBgr[i].rows; ++y) {
            auto* pixels = imagesBgr[i].ptr<cv::Vec3b>(y);
            const auto* gains = expanded.ptr<cv::Vec3f>(y);
            for (int x = 0; x < imagesBgr[i].cols; ++x)
                for (int channel = 0; channel < 3; ++channel)
                    pixels[x][channel] = cv::saturate_cast<uchar>(pixels[x][channel] * gains[x][channel]);
        }
    }
    return balanced;
}

}  // namespace photosphere
