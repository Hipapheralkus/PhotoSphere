#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/features.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/flann.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include "photometric_balance.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <numeric>
#include <queue>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
constexpr double PI = 3.14159265358979323846;
constexpr int64_t MIB = 1024LL * 1024LL;
constexpr int64_t WORKING_RESERVE = 128LL * MIB;
constexpr double MATCH_TOLERANCE_DEGREES = 0.60;
constexpr int MAX_FRAMES = 400;
constexpr int FEATURE_LONG_SIDE = 1280;
constexpr int THUMB_LONG_SIDE = 160;
std::mutex stitchMutex;

double radians(double degrees) { return degrees * PI / 180.0; }
double degrees(double radiansValue) { return radiansValue * 180.0 / PI; }
double bounded(double value, double low, double high) { return std::max(low, std::min(value, high)); }
double angularDistance(const cv::Vec3d& a, const cv::Vec3d& b) { return std::acos(bounded(a.dot(b), -1.0, 1.0)); }
cv::Vec3d unit(cv::Vec3d v) { return v * (1.0 / cv::norm(v)); }
cv::Vec3d column(const cv::Matx33d& matrix, int col) { return {matrix(0, col), matrix(1, col), matrix(2, col)}; }

struct Cancelled final : std::runtime_error { Cancelled() : std::runtime_error("Stitching cancelled") {} };

class Progress {
public:
    Progress(JNIEnv* environment, jobject listener) : env(environment), callback(listener) {
        if (!callback) throw std::runtime_error("Missing stitch progress callback");
        const jclass type = env->GetObjectClass(callback);
        method = env->GetMethodID(type, "onProgress", "(ILjava/lang/String;)Z");
        env->DeleteLocalRef(type);
        if (!method) { env->ExceptionClear(); throw std::runtime_error("Invalid stitch progress callback"); }
    }
    void report(int percent, const std::string& message) {
        jstring text = env->NewStringUTF(message.c_str());
        if (!text) throw std::runtime_error("Cannot allocate progress message");
        const jboolean keepGoing = env->CallBooleanMethod(callback, method, percent, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) { env->ExceptionClear(); throw std::runtime_error("Stitch progress callback failed"); }
        if (!keepGoing) throw Cancelled();
    }
private:
    JNIEnv* env;
    jobject callback;
    jmethodID method = nullptr;
};

std::string javaString(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Missing stitch argument");
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) throw std::runtime_error("Cannot read stitch argument");
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

bool exists(const std::string& path) { return access(path.c_str(), F_OK) == 0; }

/** Read only JPEG segment headers. EXIF orientation does not change its pixel count. */
cv::Size jpegSize(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    auto byte = [&input]() -> int { return input.get(); };
    if (!input || byte() != 0xff || byte() != 0xd8) throw std::runtime_error("An input is not a readable original JPEG");
    for (int segments = 0; segments < 10000 && input; ++segments) {
        int prefix;
        do { prefix = byte(); } while (input && prefix != 0xff);
        int marker;
        do { marker = byte(); } while (input && marker == 0xff);
        if (!input || marker == 0xda || marker == 0xd9) break;
        if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
        const int high = byte();
        const int low = byte();
        if (high < 0 || low < 0) break;
        const int length = high * 256 + low;
        if (length < 2) break;
        if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
            byte(); // sample precision
            const int hh = byte(), hl = byte(), wh = byte(), wl = byte();
            if (hh < 0 || hl < 0 || wh < 0 || wl < 0) break;
            const int height = hh * 256 + hl, width = wh * 256 + wl;
            if (width <= 0 || height <= 0) break;
            return {width, height};
        }
        input.seekg(length - 2, std::ios::cur);
    }
    throw std::runtime_error("Cannot read the dimensions of an original JPEG");
}

cv::Matx33d poseRotation(double yawDegrees, double pitchDegrees, double rollDegrees) {
    // Right-handed world: +X right, +Y down, +Z along the session's zero-yaw view.
    const double y = radians(yawDegrees), p = radians(pitchDegrees), r = radians(rollDegrees);
    const cv::Matx33d yaw(std::cos(y), 0, std::sin(y), 0, 1, 0, -std::sin(y), 0, std::cos(y));
    const cv::Matx33d pitch(1, 0, 0, 0, std::cos(p), -std::sin(p), 0, std::sin(p), std::cos(p));
    const cv::Matx33d roll(std::cos(r), -std::sin(r), 0, std::sin(r), std::cos(r), 0, 0, 0, 1);
    return yaw * pitch * roll;
}

cv::Matx33d projectRotation(const cv::Matx33d& matrix) {
    cv::Mat singular, u, vt;
    cv::SVD::compute(cv::Mat(matrix), singular, u, vt);
    if (cv::determinant(u * vt) < 0) u.col(2) *= -1.0;
    const cv::Mat product = u * vt;
    cv::Matx33d result;
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) result(row, col) = product.at<double>(row, col);
    return result;
}

double rotationDifference(const cv::Matx33d& a, const cv::Matx33d& b) {
    const cv::Matx33d difference = a.t() * b;
    return std::acos(bounded((difference(0, 0) + difference(1, 1) + difference(2, 2) - 1.0) * 0.5, -1.0, 1.0));
}

struct Frame {
    std::string path;
    double hfov = 0, vfov = 0;
    cv::Size sourceSize, orientedSourceSize, workSize;
    cv::Matx33d prior = cv::Matx33d::eye(), rotation = cv::Matx33d::eye();
    std::vector<cv::KeyPoint> keypoints;
    std::vector<cv::Vec3d> rays;
    cv::Mat descriptors, colorThumb;
    std::unique_ptr<cv::flann::Index> index;
    double exposureGain = 1.0;
    int component = -1;
};

struct Observation { cv::Vec3d a, b; int indexA = 0, indexB = 0; };
struct Edge {
    int a = 0, b = 0;
    cv::Matx33d bToA = cv::Matx33d::eye();
    std::vector<Observation> inliers;
    double medianError = 0, exposureRatio = 0, weight = 1;
};

double requiredNumber(const cv::FileNode& node, const char* name) {
    const cv::FileNode entry = node[name];
    if (entry.empty() || (!entry.isInt() && !entry.isReal())) throw std::runtime_error(std::string("Missing image field: ") + name);
    const double value = static_cast<double>(entry);
    if (!std::isfinite(value)) throw std::runtime_error("Non-finite camera orientation or field of view");
    return value;
}

std::vector<Frame> parseFrames(const std::string& json, int64_t& memoryBudget, int64_t& largestSourceBytes) {
    // org.json legally escapes '/' as '\/', while OpenCV's parser rejects that
    // escape. Consume escape pairs so a literal escaped backslash stays intact.
    std::string compatibleJson;
    compatibleJson.reserve(json.size());
    bool inString = false;
    for (size_t i = 0; i < json.size(); ++i) {
        const char character = json[i];
        if (inString && character == '\\' && i + 1 < json.size()) {
            const char escaped = json[++i];
            if (escaped != '/') compatibleJson.push_back('\\');
            compatibleJson.push_back(escaped);
        } else {
            if (character == '"') inString = !inString;
            compatibleJson.push_back(character);
        }
    }
    cv::FileStorage storage(compatibleJson, cv::FileStorage::READ | cv::FileStorage::MEMORY | cv::FileStorage::FORMAT_JSON);
    if (!storage.isOpened()) throw std::runtime_error("Cannot parse capture inputs");
    const cv::FileNode images = storage["images"];
    if (!images.isSeq() || images.size() < 2 || images.size() > MAX_FRAMES) throw std::runtime_error("Stitching requires 2 to 400 original photographs");
    const cv::FileNode budget = storage["memoryBudgetBytes"];
    if (!budget.empty()) {
        if (!budget.isInt() && !budget.isReal()) throw std::runtime_error("Invalid memory budget");
        const double requestedBudget = static_cast<double>(budget);
        if (!std::isfinite(requestedBudget) || requestedBudget < 0 || requestedBudget > static_cast<double>(std::numeric_limits<int64_t>::max()) / 2)
            throw std::runtime_error("Invalid memory budget");
        memoryBudget = static_cast<int64_t>(requestedBudget);
    }
    memoryBudget = std::min<int64_t>(memoryBudget, 1200LL * MIB);
    if (memoryBudget <= WORKING_RESERVE) throw std::runtime_error("Insufficient memory budget for original-resolution stitching");
    std::vector<Frame> frames;
    frames.reserve(images.size());
    for (const cv::FileNode& node : images) {
        Frame frame;
        frame.path = static_cast<std::string>(node["path"]);
        if (frame.path.empty()) throw std::runtime_error("An original JPEG path is missing");
        const double yaw = requiredNumber(node, "yaw"), pitch = requiredNumber(node, "pitch"), roll = requiredNumber(node, "roll");
        frame.hfov = requiredNumber(node, "hfov"); frame.vfov = requiredNumber(node, "vfov");
        if (std::abs(pitch) > 90 || frame.hfov < 15 || frame.hfov > 155 || frame.vfov < 15 || frame.vfov > 155)
            throw std::runtime_error("Camera pose or field of view is outside the supported range");
        frame.sourceSize = jpegSize(frame.path);
        const int64_t sourceBytes = static_cast<int64_t>(frame.sourceSize.width) * frame.sourceSize.height * 3;
        largestSourceBytes = std::max(largestSourceBytes, sourceBytes);
        frame.prior = frame.rotation = poseRotation(yaw, pitch, roll);
        frames.push_back(std::move(frame));
    }
    return frames;
}

void extractFeatures(std::vector<Frame>& frames, int longSide, int featureLimit, Progress& progress) {
    const auto sift = cv::SIFT::create(featureLimit, 3, 0.025, 10, 1.6);
    for (size_t i = 0; i < frames.size(); ++i) {
        progress.report(3 + static_cast<int>(17 * i / frames.size()), "Finding visual detail in photo " + std::to_string(i + 1) + " of " + std::to_string(frames.size()));
        Frame& frame = frames[i];
        // JPEG's reduced decoder is used only for matching. Composition reloads the full original.
        const int sourceLongSide = std::max(frame.sourceSize.width, frame.sourceSize.height);
        int reduction = 8;
        while (reduction > 1 && sourceLongSide / reduction < longSide) reduction /= 2;
        const int mode = reduction == 8 ? cv::IMREAD_REDUCED_COLOR_8 : reduction == 4 ? cv::IMREAD_REDUCED_COLOR_4 :
            reduction == 2 ? cv::IMREAD_REDUCED_COLOR_2 : cv::IMREAD_COLOR;
        cv::Mat image = cv::imread(frame.path, mode);
        if (image.empty()) throw std::runtime_error("Cannot decode an original photograph for visual matching");
        frame.orientedSourceSize = (image.cols > image.rows) == (frame.sourceSize.width > frame.sourceSize.height) ? frame.sourceSize :
            cv::Size(frame.sourceSize.height, frame.sourceSize.width);
        const double scale = std::min(1.0, static_cast<double>(longSide) / std::max(image.cols, image.rows));
        if (scale < 1.0) cv::resize(image, image, cv::Size(), scale, scale, cv::INTER_AREA);
        frame.workSize = image.size();
        cv::Mat gray;
        cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
        sift->detectAndCompute(gray, cv::noArray(), frame.keypoints, frame.descriptors);
        // Equal-strength features can make SIFT return slightly more than its requested count.
        if (frame.descriptors.rows > featureLimit) {
            frame.descriptors = frame.descriptors.rowRange(0, featureLimit).clone();
            frame.keypoints.resize(featureLimit);
        }
        const double thumbScale = std::min(1.0, static_cast<double>(THUMB_LONG_SIDE) / std::max(image.cols, image.rows));
        cv::resize(image, frame.colorThumb, cv::Size(), thumbScale, thumbScale, cv::INTER_AREA);
        const double fx = image.cols / (2.0 * std::tan(radians(frame.hfov / 2))), fy = image.rows / (2.0 * std::tan(radians(frame.vfov / 2)));
        const double cx = (image.cols - 1) * 0.5, cy = (image.rows - 1) * 0.5;
        frame.rays.reserve(frame.keypoints.size());
        for (const cv::KeyPoint& keypoint : frame.keypoints) frame.rays.push_back(unit({(keypoint.pt.x - cx) / fx, (keypoint.pt.y - cy) / fy, 1.0}));
        if (frame.descriptors.rows >= 12) frame.index = std::make_unique<cv::flann::Index>(frame.descriptors, cv::flann::KDTreeIndexParams(4));
    }
}

std::vector<std::pair<int, int>> candidatePairs(const std::vector<Frame>& frames) {
    const int count = static_cast<int>(frames.size());
    std::vector<std::vector<std::pair<double, int>>> neighbours(count);
    for (int a = 0; a < count; ++a) for (int b = a + 1; b < count; ++b) {
        auto radius = [](const Frame& f) { return std::atan(std::hypot(std::tan(radians(f.hfov / 2)), std::tan(radians(f.vfov / 2)))); };
        const double distance = angularDistance(column(frames[a].prior, 2), column(frames[b].prior, 2));
        if (distance < radius(frames[a]) + radius(frames[b])) {
            neighbours[a].emplace_back(distance, b);
            neighbours[b].emplace_back(distance, a);
        }
    }
    std::vector<std::pair<int, int>> pairs;
    for (int i = 0; i < count; ++i) {
        auto& near = neighbours[i];
        std::sort(near.begin(), near.end());
        for (size_t j = 0; j < std::min<size_t>(10, near.size()); ++j) pairs.emplace_back(std::min(i, near[j].second), std::max(i, near[j].second));
    }
    std::sort(pairs.begin(), pairs.end());
    pairs.erase(std::unique(pairs.begin(), pairs.end()), pairs.end());
    return pairs;
}

cv::Matx33d fittedRotation(const std::vector<Observation>& observations, const std::vector<int>& indices) {
    cv::Matx33d covariance = cv::Matx33d::zeros();
    for (int index : indices) {
        const auto& match = observations[index];
        for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) covariance(row, col) += match.a[row] * match.b[col];
    }
    return projectRotation(covariance);
}

double median(std::vector<double> values) {
    if (values.empty()) return 0;
    const size_t centre = values.size() / 2;
    std::nth_element(values.begin(), values.begin() + centre, values.end());
    return values[centre];
}

double patchLuminance(const Frame& frame, int keypointIndex) {
    const cv::Point2f point = frame.keypoints[keypointIndex].pt;
    const int x = cvRound(point.x * frame.colorThumb.cols / frame.workSize.width);
    const int y = cvRound(point.y * frame.colorThumb.rows / frame.workSize.height);
    double sum = 0;
    int count = 0;
    for (int row = std::max(0, y - 2); row <= std::min(frame.colorThumb.rows - 1, y + 2); ++row) {
        const auto* pixels = frame.colorThumb.ptr<cv::Vec3b>(row);
        for (int col = std::max(0, x - 2); col <= std::min(frame.colorThumb.cols - 1, x + 2); ++col) {
            const auto pixel = pixels[col];
            sum += 0.114 * pixel[0] + 0.587 * pixel[1] + 0.299 * pixel[2];
            ++count;
        }
    }
    return count ? sum / count : 0;
}

bool matchPair(std::vector<Frame>& frames, int indexA, int indexB, Edge& edge) {
    Frame& a = frames[indexA]; Frame& b = frames[indexB];
    if (!a.index || !b.index) return false;
    cv::Mat aIndices, aDistances, bIndices, bDistances;
    b.index->knnSearch(a.descriptors, aIndices, aDistances, 2, cv::flann::SearchParams(48));
    a.index->knnSearch(b.descriptors, bIndices, bDistances, 2, cv::flann::SearchParams(48));
    std::vector<Observation> observations;
    const double priorCosine = std::cos(radians(15.0));
    for (int i = 0; i < a.descriptors.rows; ++i) {
        const int j = aIndices.at<int>(i, 0);
        if (j < 0 || j >= b.descriptors.rows || bIndices.at<int>(j, 0) != i) continue;
        if (aDistances.at<float>(i, 0) >= 0.5625f * aDistances.at<float>(i, 1) ||
            bDistances.at<float>(j, 0) >= 0.5625f * bDistances.at<float>(j, 1)) continue;
        if ((a.prior * a.rays[i]).dot(b.prior * b.rays[j]) < priorCosine) continue;
        observations.push_back({a.rays[i], b.rays[j], i, j});
    }
    if (observations.size() < 12) return false;
    std::mt19937 random(static_cast<uint32_t>(indexA * 73856093u) ^ static_cast<uint32_t>(indexB * 19349663u));
    std::uniform_int_distribution<int> choose(0, static_cast<int>(observations.size()) - 1);
    std::vector<int> best;
    const double inlierCosine = std::cos(radians(MATCH_TOLERANCE_DEGREES));
    for (int iteration = 0; iteration < 180; ++iteration) {
        int first = choose(random), second = choose(random), third = choose(random);
        if (first == second || first == third || second == third) continue;
        if (cv::norm(observations[first].b.cross(observations[second].b)) < 0.01) continue;
        const cv::Matx33d rotation = fittedRotation(observations, {first, second, third});
        std::vector<int> inliers;
        for (size_t i = 0; i < observations.size(); ++i) if (observations[i].a.dot(rotation * observations[i].b) >= inlierCosine) inliers.push_back(static_cast<int>(i));
        if (inliers.size() > best.size()) best = std::move(inliers);
        if (best.size() > observations.size() * 0.9 && iteration > 25) break;
    }
    if (best.size() < 12 || best.size() < observations.size() * 0.25) return false;
    cv::Matx33d rotation;
    for (int refine = 0; refine < 3; ++refine) {
        rotation = fittedRotation(observations, best);
        best.clear();
        for (size_t i = 0; i < observations.size(); ++i) if (observations[i].a.dot(rotation * observations[i].b) >= inlierCosine) best.push_back(static_cast<int>(i));
        if (best.size() < 12) return false;
    }
    if (rotationDifference(a.prior.t() * b.prior, rotation) > radians(15.0)) return false;
    auto spread = [&observations, &best](const Frame& frame, bool first) {
        float left = std::numeric_limits<float>::max(), top = left, right = 0, bottom = 0;
        for (int index : best) {
            const auto point = frame.keypoints[first ? observations[index].indexA : observations[index].indexB].pt;
            left = std::min(left, point.x); top = std::min(top, point.y); right = std::max(right, point.x); bottom = std::max(bottom, point.y);
        }
        return (right - left) * (bottom - top) / frame.workSize.area();
    };
    if (spread(a, true) < 0.005 || spread(b, false) < 0.005) return false;
    edge.a = indexA; edge.b = indexB; edge.bToA = rotation;
    std::vector<double> errors, exposureRatios;
    for (int index : best) {
        const Observation& match = observations[index];
        edge.inliers.push_back(match);
        errors.push_back(degrees(angularDistance(match.a, rotation * match.b)));
        const double la = patchLuminance(a, match.indexA), lb = patchLuminance(b, match.indexB);
        if (la >= 15 && lb >= 15 && la <= 240 && lb <= 240) exposureRatios.push_back(std::log(la / lb));
    }
    edge.medianError = median(errors);
    edge.exposureRatio = exposureRatios.size() >= 8 ? bounded(median(exposureRatios), -std::log(2.0), std::log(2.0)) : 0.0;
    edge.weight = std::min(2.0, std::sqrt(edge.inliers.size() / 30.0)) / (1.0 + edge.medianError);
    return true;
}

int refineGraph(std::vector<Frame>& frames, const std::vector<Edge>& edges, Progress& progress) {
    const int count = static_cast<int>(frames.size());
    std::vector<std::vector<int>> adjacency(count);
    for (size_t i = 0; i < edges.size(); ++i) { adjacency[edges[i].a].push_back(static_cast<int>(i)); adjacency[edges[i].b].push_back(static_cast<int>(i)); }
    std::vector<bool> anchor(count, false);
    int componentCount = 0;
    for (int first = 0; first < count; ++first) {
        if (frames[first].component >= 0 || adjacency[first].empty()) continue;
        std::queue<int> pending;
        pending.push(first); frames[first].component = componentCount; anchor[first] = true;
        while (!pending.empty()) {
            const int current = pending.front(); pending.pop();
            for (int edgeIndex : adjacency[current]) {
                const auto& edge = edges[edgeIndex];
                const int next = edge.a == current ? edge.b : edge.a;
                if (frames[next].component < 0) { frames[next].component = componentCount; pending.push(next); }
            }
        }
        ++componentCount;
    }
    std::vector<cv::Matx33d> updates(count);
    std::vector<double> gains(count, 0), gainUpdates(count, 0);
    for (int iteration = 0; iteration < 90; ++iteration) {
        if (iteration % 10 == 0) progress.report(45 + iteration / 20, "Refining visual alignment and exposure across the sphere");
        for (int i = 0; i < count; ++i) {
            if (anchor[i] || adjacency[i].empty()) { updates[i] = frames[i].prior; gainUpdates[i] = 0; continue; }
            cv::Matx33d sum = frames[i].prior * 0.025;
            double total = 0.025, exposure = 0;
            for (int edgeIndex : adjacency[i]) {
                const Edge& edge = edges[edgeIndex];
                const bool isA = edge.a == i;
                const int neighbour = isA ? edge.b : edge.a;
                const cv::Matx33d proposed = frames[neighbour].rotation * (isA ? edge.bToA.t() : edge.bToA);
                sum += proposed * edge.weight;
                exposure += edge.weight * (gains[neighbour] + (isA ? -edge.exposureRatio : edge.exposureRatio));
                total += edge.weight;
            }
            updates[i] = projectRotation(projectRotation(sum) * 0.7 + frames[i].rotation * 0.3);
            gainUpdates[i] = bounded(0.7 * exposure / total + 0.3 * gains[i], -std::log(2.0), std::log(2.0));
        }
        for (int i = 0; i < count; ++i) { frames[i].rotation = updates[i]; gains[i] = gainUpdates[i]; }
    }
    for (int i = 0; i < count; ++i) frames[i].exposureGain = std::exp(gains[i]);
    return componentCount;
}

cv::Matx33d rotationStep(const cv::Vec3d& vector) {
    const double angle = cv::norm(vector);
    if (angle < 1e-12) return cv::Matx33d::eye();
    const cv::Vec3d axis = vector * (1.0 / angle);
    const cv::Matx33d cross(0, -axis[2], axis[1], axis[2], 0, -axis[0], -axis[1], axis[0], 0);
    return cv::Matx33d::eye() + cross * std::sin(angle) + cross * cross * (1 - std::cos(angle));
}

cv::Vec3d rotationVector(const cv::Matx33d& rotation) {
    const double angle = std::acos(bounded((rotation(0, 0) + rotation(1, 1) + rotation(2, 2) - 1) * 0.5, -1.0, 1.0));
    const cv::Vec3d antisymmetric(rotation(2, 1) - rotation(1, 2), rotation(0, 2) - rotation(2, 0), rotation(1, 0) - rotation(0, 1));
    return antisymmetric * (angle < 1e-7 ? 0.5 : angle / (2 * std::sin(angle)));
}

/** Joint robust ray adjustment avoids averaging inconsistent pair rotations equally. */
double refineRays(std::vector<Frame>& frames, std::vector<Edge>& edges, Progress& progress) {
    const int count = static_cast<int>(frames.size());
    std::vector<int> offset(count, -1), seenComponents;
    int variables = 0;
    for (int i = 0; i < count; ++i) {
        frames[i].rotation = frames[i].prior;
        if (frames[i].component < 0) continue;
        if (std::find(seenComponents.begin(), seenComponents.end(), frames[i].component) == seenComponents.end())
            seenComponents.push_back(frames[i].component);
        else { offset[i] = variables; variables += 3; }
    }
    const int focalOffset = variables++;
    double logFocal = 0, damping = 0.001;
    auto rayAtScale = [](const cv::Vec3d& source, double focal, cv::Vec3d* derivative) {
        const cv::Vec3d point(source[0] / (source[2] * focal), source[1] / (source[2] * focal), 1);
        const double length = cv::norm(point);
        const cv::Vec3d ray = point * (1 / length);
        if (derivative) {
            const cv::Vec3d raw(-point[0], -point[1], 0);
            *derivative = (raw - ray * ray.dot(raw)) * (1 / length);
        }
        return ray;
    };
    auto evaluate = [&](const std::vector<cv::Matx33d>& rotations, double focalLog, double robustScale,
                        cv::Mat* hessian, cv::Mat* gradient) {
        const double focal = std::exp(focalLog);
        double cost = 0;
        auto row = [&](double residual, const std::array<int, 7>& indices, const std::array<double, 7>& jacobian, int size) {
            const double relative = residual / robustScale;
            const double robustWeight = 1 / std::sqrt(1 + relative * relative);
            cost += 2 * robustScale * robustScale * (std::sqrt(1 + relative * relative) - 1);
            if (!hessian) return;
            for (int a = 0; a < size; ++a) {
                gradient->at<double>(indices[a]) += robustWeight * jacobian[a] * residual;
                for (int b = 0; b < size; ++b)
                    hessian->at<double>(indices[a], indices[b]) += robustWeight * jacobian[a] * jacobian[b];
            }
        };
        for (const Edge& edge : edges) {
            const double pairWeight = std::sqrt(std::min(1.0, 100.0 / edge.inliers.size()));
            for (const Observation& match : edge.inliers) {
                cv::Vec3d derivativeA, derivativeB;
                const cv::Vec3d a = rotations[edge.a] * rayAtScale(match.a, focal, &derivativeA);
                const cv::Vec3d b = rotations[edge.b] * rayAtScale(match.b, focal, &derivativeB);
                const cv::Vec3d focalDerivative = rotations[edge.a] * derivativeA - rotations[edge.b] * derivativeB;
                const cv::Matx33d ja(0, a[2], -a[1], -a[2], 0, a[0], a[1], -a[0], 0);
                const cv::Matx33d jb(0, -b[2], b[1], b[2], 0, -b[0], -b[1], b[0], 0);
                for (int dimension = 0; dimension < 3; ++dimension) {
                    std::array<int, 7> indices{}; std::array<double, 7> jacobian{};
                    int size = 0;
                    for (int k = 0; k < 3; ++k) {
                        if (offset[edge.a] >= 0) { indices[size] = offset[edge.a] + k; jacobian[size++] = ja(dimension, k) * pairWeight; }
                        if (offset[edge.b] >= 0) { indices[size] = offset[edge.b] + k; jacobian[size++] = jb(dimension, k) * pairWeight; }
                    }
                    indices[size] = focalOffset; jacobian[size++] = focalDerivative[dimension] * pairWeight;
                    row((a[dimension] - b[dimension]) * pairWeight, indices, jacobian, size);
                }
            }
        }
        for (int i = 0; i < count; ++i) if (offset[i] >= 0) {
            const cv::Vec3d delta = rotationVector(rotations[i] * frames[i].prior.t());
            for (int k = 0; k < 3; ++k) {
                const double priorWeight = k == 1 ? 0.05 : 0.35;
                row(delta[k] * priorWeight, {offset[i] + k}, {priorWeight}, 1);
            }
        }
        row(focalLog * 0.1, {focalOffset}, {0.1}, 1);
        return cost;
    };
    std::vector<cv::Matx33d> rotations(count), candidate(count);
    for (int i = 0; i < count; ++i) rotations[i] = frames[i].prior;
    for (int stage = 0; stage < 2; ++stage) {
        damping = 0.001;
        const double robustScale = stage == 0 ? 0.02 : 0.007;
        for (int iteration = 0; iteration < 45; ++iteration) {
            if (iteration % 5 == 0) progress.report(48, "Refining individual visual matches and lens scale");
            cv::Mat hessian = cv::Mat::zeros(variables, variables, CV_64F);
            cv::Mat gradient = cv::Mat::zeros(variables, 1, CV_64F), step;
            const double currentCost = evaluate(rotations, logFocal, robustScale, &hessian, &gradient);
            for (int i = 0; i < variables; ++i) hessian.at<double>(i, i) += damping * std::max(1.0, hessian.at<double>(i, i));
            if (!cv::solve(hessian, -gradient, step, cv::DECOMP_CHOLESKY)) { damping *= 10; continue; }
            candidate = rotations;
            for (int i = 0; i < count; ++i) if (offset[i] >= 0) {
                cv::Vec3d change(step.at<double>(offset[i]), step.at<double>(offset[i] + 1), step.at<double>(offset[i] + 2));
                const double length = cv::norm(change);
                if (length > radians(4)) change *= radians(4) / length;
                cv::Vec3d delta = rotationVector(rotationStep(change) * rotations[i] * frames[i].prior.t());
                delta[0] = bounded(delta[0], -radians(5), radians(5));
                delta[1] = bounded(delta[1], -radians(25), radians(25));
                delta[2] = bounded(delta[2], -radians(5), radians(5));
                candidate[i] = rotationStep(delta) * frames[i].prior;
            }
            const double proposedFocal = bounded(logFocal + bounded(step.at<double>(focalOffset), -0.04, 0.04), std::log(0.85), std::log(1.20));
            const double nextCost = evaluate(candidate, proposedFocal, robustScale, nullptr, nullptr);
            if (nextCost < currentCost) {
                rotations.swap(candidate); logFocal = proposedFocal; damping = std::max(1e-7, damping * 0.4);
                if (currentCost - nextCost < 1e-7 * std::max(1.0, currentCost)) break;
            } else {
                damping *= 5;
                if (damping > 1e6) break;
            }
        }
    }
    const double focal = std::exp(logFocal);
    for (int i = 0; i < count; ++i) {
        frames[i].rotation = rotations[i];
        frames[i].hfov = degrees(2 * std::atan(std::tan(radians(frames[i].hfov / 2)) / focal));
        frames[i].vfov = degrees(2 * std::atan(std::tan(radians(frames[i].vfov / 2)) / focal));
    }
    for (Edge& edge : edges) for (Observation& match : edge.inliers) {
        match.a = rayAtScale(match.a, focal, nullptr); match.b = rayAtScale(match.b, focal, nullptr);
    }
    return focal;
}

struct SeamPatch { cv::Mat weight, gain; cv::Point corner; cv::Size size; };
struct SeamPlan {
    int width = 0, height = 0, padding = 0;
    int64_t estimatedPeakBytes = 0, retainedBytes = 0;
    double maximumGain = 1;
    double lowFrequencySigma = 0, maximumLowFrequencyCorrection = 0, rmsLowFrequencyCorrection = 0;
    cv::Mat lowFrequencyCorrection;
    std::vector<SeamPatch> patches;
};

SeamPlan prepareSeams(const std::vector<Frame>& frames, int64_t memoryBudget, Progress& progress) {
    SeamPlan plan;
    plan.width = std::max(64, std::min(768, static_cast<int>(768 * std::sqrt(48.0 / frames.size())) / 16 * 16));
    // Includes graph-cut storage, the source low-pass temporaries and the small
    // broad/narrow RGB canvases. No full-resolution float canvas is allocated.
    const int64_t maximumArea = std::min<int64_t>(4000000, (memoryBudget - 64LL * MIB) / 112);
    if (maximumArea <= 0) throw std::runtime_error("Not enough memory for image boundary optimization");
    std::vector<cv::Mat> masks, scores;
    std::vector<cv::Point> corners;
    cv::Mat directions, bestScore;
    int64_t totalArea = 0;
    for (;;) {
        plan.height = plan.width / 2; plan.padding = plan.width / 6;
        const int canvasWidth = plan.width + 2 * plan.padding;
        directions.create(plan.height, canvasWidth, CV_32FC3);
        bestScore = cv::Mat::zeros(plan.height, canvasWidth, CV_32FC1);
        for (int y = 0; y < plan.height; ++y) for (int x = 0; x < canvasWidth; ++x) {
            const double longitude = (x - plan.padding + 0.5) * 2 * PI / plan.width - PI;
            const double latitude = PI / 2 - (y + 0.5) * PI / plan.height;
            directions.at<cv::Vec3f>(y, x) = cv::Vec3f(std::cos(latitude) * std::sin(longitude), -std::sin(latitude), std::cos(latitude) * std::cos(longitude));
        }
        masks.clear(); scores.clear(); corners.clear(); totalArea = 0;
        for (size_t i = 0; i < frames.size(); ++i) {
            if (i % 6 == 0) progress.report(50, "Planning image boundaries across the sphere");
            const Frame& frame = frames[i];
            const cv::Matx33d inverse = frame.rotation.t();
            const double tanH = std::tan(radians(frame.hfov / 2)), tanV = std::tan(radians(frame.vfov / 2));
            const double hLimit = 1 - 1.0 / frame.orientedSourceSize.width, vLimit = 1 - 1.0 / frame.orientedSourceSize.height;
            cv::Mat mask = cv::Mat::zeros(plan.height, canvasWidth, CV_8UC1);
            cv::Mat score = cv::Mat::zeros(plan.height, canvasWidth, CV_32FC1);
            for (int y = 0; y < plan.height; ++y) for (int x = 0; x < canvasWidth; ++x) {
                const cv::Vec3f world = directions.at<cv::Vec3f>(y, x);
                const cv::Vec3d camera = inverse * cv::Vec3d(world[0], world[1], world[2]);
                if (camera[2] <= 0) continue;
                const double horizontal = std::abs(camera[0] / (camera[2] * tanH));
                const double vertical = std::abs(camera[1] / (camera[2] * tanV));
                if (horizontal >= hLimit || vertical >= vLimit) continue;
                mask.at<uchar>(y, x) = 255;
                const double z2 = camera[2] * camera[2];
                const float value = static_cast<float>(z2 * z2 * z2 * z2 * bounded(std::min(1 - horizontal, 1 - vertical) / 0.12, 0, 1));
                score.at<float>(y, x) = value;
                bestScore.at<float>(y, x) = std::max(bestScore.at<float>(y, x), value);
            }
            cv::Rect bounds = cv::boundingRect(mask);
            if (bounds.empty()) throw std::runtime_error("Seam resolution cannot represent an image footprint");
            bounds = (cv::Rect(bounds.x - 2, bounds.y - 2, bounds.width + 4, bounds.height + 4) & cv::Rect(0, 0, canvasWidth, plan.height));
            masks.push_back(mask(bounds).clone()); scores.push_back(score(bounds).clone()); corners.push_back(bounds.tl());
            totalArea += bounds.area();
        }
        if (totalArea <= maximumArea) break;
        const int smaller = std::max(64, static_cast<int>(plan.width * std::sqrt(static_cast<double>(maximumArea) / totalArea) * 0.97) / 16 * 16);
        if (smaller >= plan.width || plan.width <= 64) throw std::runtime_error("Not enough memory for image boundary optimization");
        plan.width = smaller;
    }
    plan.estimatedPeakBytes = totalArea * 112 + 64LL * MIB;
    std::vector<cv::Mat> images;
    images.reserve(frames.size());
    for (size_t i = 0; i < frames.size(); ++i) {
        progress.report(51 + static_cast<int>(3 * i / frames.size()), "Preparing exposure and seam detail");
        const Frame& frame = frames[i];
        cv::Mat image = cv::imread(frame.path, cv::IMREAD_REDUCED_COLOR_4);
        if (image.empty()) throw std::runtime_error("Cannot decode a photograph for seam optimization");
        if (std::max(image.cols, image.rows) > 1024) {
            const double shrink = 1024.0 / std::max(image.cols, image.rows);
            cv::resize(image, image, cv::Size(), shrink, shrink, cv::INTER_AREA);
        }
        const cv::Matx33d inverse = frame.rotation.t();
        const double fx = image.cols / (2 * std::tan(radians(frame.hfov / 2))), fy = image.rows / (2 * std::tan(radians(frame.vfov / 2)));
        cv::Mat mapX(masks[i].size(), CV_32FC1), mapY(masks[i].size(), CV_32FC1);
        for (int y = 0; y < mapX.rows; ++y) for (int x = 0; x < mapX.cols; ++x) {
            const cv::Vec3f world = directions.at<cv::Vec3f>(corners[i].y + y, corners[i].x + x);
            const cv::Vec3d camera = inverse * cv::Vec3d(world[0], world[1], world[2]);
            const double z = std::max(1e-6, camera[2]);
            mapX.at<float>(y, x) = static_cast<float>(fx * camera[0] / z + (image.cols - 1) * 0.5);
            mapY.at<float>(y, x) = static_cast<float>(fy * camera[1] / z + (image.rows - 1) * 0.5);
        }
        cv::Mat warped;
        cv::remap(image, warped, mapX, mapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
        images.push_back(std::move(warped));
    }
    directions.release();
    progress.report(55, "Balancing overlapping photographs");
    auto balance = photosphere::balanceExposure(images, corners, masks);
    plan.maximumGain = balance.maximumGain;
    std::vector<cv::UMat> floatImages, seamMasks;
    for (size_t i = 0; i < images.size(); ++i) {
        cv::UMat floating, restricted;
        images[i].convertTo(floating, CV_32FC3);
        cv::Mat allowed = masks[i].clone();
        // Preserve each view's central region. Otherwise a featureless ceiling can
        // win an unrestricted graph cut and erase a nearby light from another view.
        for (int y = 0; y < allowed.rows; ++y) for (int x = 0; x < allowed.cols; ++x)
            if (scores[i].at<float>(y, x) < 0.78f * bestScore.at<float>(corners[i].y + y, corners[i].x + x)) allowed.at<uchar>(y, x) = 0;
        allowed.copyTo(restricted);
        floatImages.push_back(std::move(floating)); seamMasks.push_back(std::move(restricted));
    }
    std::vector<cv::Mat>().swap(images); std::vector<cv::Mat>().swap(scores); bestScore.release();
    progress.report(57, "Choosing seams that preserve image detail");
    cv::detail::GraphCutSeamFinder seamFinder(cv::detail::GraphCutSeamFinderBase::COST_COLOR_GRAD);
    seamFinder.find(floatImages, corners, seamMasks);
    plan.patches.resize(frames.size());
    for (size_t i = 0; i < frames.size(); ++i) {
        SeamPatch& patch = plan.patches[i];
        patch.corner = corners[i]; patch.size = masks[i].size(); patch.gain = balance.gainMaps[i];
        cv::Mat expanded;
        cv::dilate(seamMasks[i].getMat(cv::ACCESS_READ), expanded, cv::Mat::ones(3, 3, CV_8U));
        expanded.convertTo(patch.weight, CV_32FC1, 1.0 / 255);
        cv::GaussianBlur(patch.weight, patch.weight, cv::Size(5, 5), 0.7);
        plan.retainedBytes += patch.weight.total() * patch.weight.elemSize() + patch.gain.total() * patch.gain.elemSize();
    }
    // A narrow seam preserves detail but exposes low-frequency exposure steps.
    // Build a two-band correction from the individual sources, not by blurring
    // the finished panorama (which would retain halos from those existing steps).
    // Original detail minus each source's low-pass remains selected by the seam;
    // the low-pass itself is averaged smoothly over the full camera overlaps.
    progress.report(58, "Smoothing exposure across the complete overlaps");
    const cv::Size canvas(plan.width + 2 * plan.padding, plan.height);
    cv::Mat broadSum = cv::Mat::zeros(canvas, CV_32FC3);
    cv::Mat narrowSum = cv::Mat::zeros(canvas, CV_32FC3);
    cv::Mat broadWeight = cv::Mat::zeros(canvas, CV_32FC1);
    cv::Mat narrowWeight = cv::Mat::zeros(canvas, CV_32FC1);
    plan.lowFrequencySigma = std::max(1.0, plan.width * (12.0 / 768.0));
    for (size_t i = 0; i < frames.size(); ++i) {
        progress.report(58, "Smoothing exposure across the complete overlaps");
        const Frame& frame = frames[i];
        const SeamPatch& patch = plan.patches[i];
        cv::Mat normalizedMask, lowPass = floatImages[i].getMat(cv::ACCESS_READ).clone();
        masks[i].convertTo(normalizedMask, CV_32FC1, 1.0 / 255);
        lowPass.setTo(cv::Scalar(), masks[i] == 0);
        cv::GaussianBlur(lowPass, lowPass, cv::Size(), plan.lowFrequencySigma, plan.lowFrequencySigma, cv::BORDER_CONSTANT);
        cv::GaussianBlur(normalizedMask, normalizedMask, cv::Size(), plan.lowFrequencySigma, plan.lowFrequencySigma, cv::BORDER_CONSTANT);
        const cv::Matx33d inverse = frame.rotation.t();
        const double tanH = std::tan(radians(frame.hfov / 2)), tanV = std::tan(radians(frame.vfov / 2));
        for (int y = 0; y < patch.size.height; ++y) {
            const int gy = patch.corner.y + y;
            const double latitude = PI / 2 - (gy + 0.5) * PI / plan.height;
            const double cosine = std::cos(latitude), worldY = -std::sin(latitude);
            auto* broad = broadSum.ptr<cv::Vec3f>(gy); auto* narrow = narrowSum.ptr<cv::Vec3f>(gy);
            auto* bw = broadWeight.ptr<float>(gy); auto* nw = narrowWeight.ptr<float>(gy);
            const auto* blurred = lowPass.ptr<cv::Vec3f>(y); const auto* normalizer = normalizedMask.ptr<float>(y);
            const auto* mask = masks[i].ptr<uchar>(y); const auto* seamWeight = patch.weight.ptr<float>(y);
            for (int x = 0; x < patch.size.width; ++x) {
                if (!mask[x] || normalizer[x] < 1e-6f) continue;
                const int gx = patch.corner.x + x;
                const double longitude = (gx - plan.padding + 0.5) * 2 * PI / plan.width - PI;
                const cv::Vec3d camera = inverse * cv::Vec3d(cosine * std::sin(longitude), worldY, cosine * std::cos(longitude));
                if (camera[2] <= 0) continue;
                // Match the full-resolution renderer's edge and incidence terms.
                const double edge = std::min(1 - std::abs(camera[0] / (camera[2] * tanH)), 1 - std::abs(camera[1] / (camera[2] * tanV)));
                const double feather = bounded(edge / 0.25, 0.0, 1.0);
                const float fullWeight = static_cast<float>(feather * feather * (3 - 2 * feather) * camera[2] * camera[2]);
                const float selectedWeight = seamWeight[x] < 1e-5f ? 0.f :
                    std::max(1, cvRound(255 * seamWeight[x] * fullWeight)) / 255.f;
                const cv::Vec3f color = blurred[x] / normalizer[x];
                broad[gx] += color * fullWeight; bw[gx] += fullWeight;
                narrow[gx] += color * selectedWeight; nw[gx] += selectedWeight;
            }
        }
        // Each source is released as soon as its contribution has been reduced.
        floatImages[i].release();
    }
    plan.lowFrequencyCorrection = cv::Mat::zeros(canvas, CV_32FC3);
    double correctionSquares = 0; uint64_t correctionSamples = 0;
    for (int y = 0; y < canvas.height; ++y) for (int x = 0; x < canvas.width; ++x) {
        const float bw = broadWeight.at<float>(y, x), nw = narrowWeight.at<float>(y, x);
        if (bw < 1e-6f || nw < 1e-6f) continue;
        cv::Vec3f& correction = plan.lowFrequencyCorrection.at<cv::Vec3f>(y, x);
        correction = broadSum.at<cv::Vec3f>(y, x) / bw - narrowSum.at<cv::Vec3f>(y, x) / nw;
        for (int channel = 0; channel < 3; ++channel) {
            correction[channel] = std::clamp(correction[channel], -80.f, 80.f);
            if (x >= plan.padding && x < plan.padding + plan.width) {
                plan.maximumLowFrequencyCorrection = std::max(plan.maximumLowFrequencyCorrection, static_cast<double>(std::abs(correction[channel])));
                correctionSquares += correction[channel] * correction[channel]; ++correctionSamples;
            }
        }
    }
    plan.rmsLowFrequencyCorrection = correctionSamples ? std::sqrt(correctionSquares / correctionSamples) : 0;
    plan.retainedBytes += plan.lowFrequencyCorrection.total() * plan.lowFrequencyCorrection.elemSize();
    progress.report(59, "Seams and exposure corrections are ready");
    return plan;
}

float sampleWeight(const cv::Mat& matrix, double x, double y) {
    if (x < -0.5 || y < -0.5 || x > matrix.cols - 0.5 || y > matrix.rows - 0.5) return 0;
    x = bounded(x, 0, matrix.cols - 1); y = bounded(y, 0, matrix.rows - 1);
    const int left = static_cast<int>(x), top = static_cast<int>(y), right = std::min(left + 1, matrix.cols - 1), bottom = std::min(top + 1, matrix.rows - 1);
    const double dx = x - left, dy = y - top;
    return static_cast<float>((1 - dy) * ((1 - dx) * matrix.at<float>(top, left) + dx * matrix.at<float>(top, right)) +
        dy * ((1 - dx) * matrix.at<float>(bottom, left) + dx * matrix.at<float>(bottom, right)));
}

cv::Vec3f sampleColor(const cv::Mat& matrix, double x, double y) {
    const double gx = bounded(x, 0, matrix.cols - 1), gy = bounded(y, 0, matrix.rows - 1);
    const int left = static_cast<int>(gx), top = static_cast<int>(gy), right = std::min(left + 1, matrix.cols - 1), bottom = std::min(top + 1, matrix.rows - 1);
    const float dx = static_cast<float>(gx - left), dy = static_cast<float>(gy - top);
    return (matrix.at<cv::Vec3f>(top, left) * (1 - dx) + matrix.at<cv::Vec3f>(top, right) * dx) * (1 - dy) +
        (matrix.at<cv::Vec3f>(bottom, left) * (1 - dx) + matrix.at<cv::Vec3f>(bottom, right) * dx) * dy;
}

cv::Vec3f sampleGain(const SeamPatch& patch, double x, double y) {
    return sampleColor(patch.gain, (x + 0.5) * patch.gain.cols / patch.size.width - 0.5,
                       (y + 0.5) * patch.gain.rows / patch.size.height - 0.5);
}

struct RenderStats { double coverage = 0, pixelCoverage = 0; uint64_t coveredPixels = 0; };

RenderStats compose(const std::vector<Frame>& frames, const SeamPlan& seams, int width, cv::Mat& panorama, Progress& progress) {
    const int height = width / 2;
    panorama = cv::Mat(height, width, CV_8UC3, cv::Scalar(32, 32, 32));
    cv::Mat weights = cv::Mat::zeros(height, width, CV_16UC1);
    std::vector<double> sinLongitude(width), cosLongitude(width), sinLatitude(height), cosLatitude(height);
    std::vector<uint32_t> coveredByRow(height, 0);
    for (int x = 0; x < width; ++x) {
        const double longitude = (x + 0.5) * (2 * PI / width) - PI;
        sinLongitude[x] = std::sin(longitude); cosLongitude[x] = std::cos(longitude);
    }
    for (int y = 0; y < height; ++y) {
        const double latitude = PI / 2 - (y + 0.5) * (PI / height);
        sinLatitude[y] = std::sin(latitude); cosLatitude[y] = std::cos(latitude);
    }
    for (size_t index = 0; index < frames.size(); ++index) {
        const Frame& frame = frames[index];
        progress.report(60 + static_cast<int>(34 * index / frames.size()), "Blending original photo " + std::to_string(index + 1) + " of " + std::to_string(frames.size()));
        const SeamPatch& seam = seams.patches[index];
        // Default imread applies EXIF orientation. This is the full JPEG, not the matching thumbnail.
        const cv::Mat original = cv::imread(frame.path, cv::IMREAD_COLOR);
        if (original.empty()) throw std::runtime_error("Cannot decode a full-resolution original photograph");
        const double fx = original.cols / (2 * std::tan(radians(frame.hfov / 2))), fy = original.rows / (2 * std::tan(radians(frame.vfov / 2)));
        const double cx = (original.cols - 1) * 0.5, cy = (original.rows - 1) * 0.5;
        const cv::Matx33d inverse = frame.rotation.t();
        const cv::Vec3d forward = column(frame.rotation, 2);
        const double centrePitch = std::asin(bounded(-forward[1], -1.0, 1.0));
        const double centreYaw = std::atan2(forward[0], forward[2]);
        const double radius = std::atan(std::hypot(std::tan(radians(frame.hfov / 2)), std::tan(radians(frame.vfov / 2))));
        // A spherical cap encloses the rectangular footprint, including its edge extrema.
        const int firstRow = std::max(0, static_cast<int>(std::floor((PI / 2 - std::min(PI / 2, centrePitch + radius)) * height / PI)) - 1);
        const int lastRow = std::min(height, static_cast<int>(std::ceil((PI / 2 - std::max(-PI / 2, centrePitch - radius)) * height / PI)) + 1);
        const bool includesPole = std::abs(centrePitch) + radius >= PI / 2;
        const double halfLongitude = includesPole ? PI : std::asin(bounded(std::sin(radius) / std::cos(centrePitch), -1.0, 1.0));
        const int firstColumn = includesPole ? 0 : static_cast<int>(std::floor((centreYaw - halfLongitude + PI) * width / (2 * PI))) - 1;
        const int columnCount = includesPole ? width : std::min(width, static_cast<int>(std::ceil(halfLongitude * width / PI)) + 4);
        for (int stripe = firstRow; stripe < lastRow; stripe += 128) {
            const int stripeEnd = std::min(lastRow, stripe + 128);
            cv::parallel_for_(cv::Range(stripe, stripeEnd), [&](const cv::Range& rows) {
                for (int y = rows.start; y < rows.end; ++y) {
                    auto* pixels = panorama.ptr<cv::Vec3b>(y);
                    auto* weightRow = weights.ptr<uint16_t>(y);
                    uint32_t newCoverage = 0;
                    const double worldY = -sinLatitude[y], latitudeCosine = cosLatitude[y];
                    for (int step = 0; step < columnCount; ++step) {
                        int x = (firstColumn + step) % width;
                        if (x < 0) x += width;
                        const double seamX = (x + 0.5) * seams.width / width + seams.padding - 0.5 - seam.corner.x;
                        const double seamY = (y + 0.5) * seams.height / height - 0.5 - seam.corner.y;
                        const float seamWeight = sampleWeight(seam.weight, seamX, seamY);
                        if (seamWeight < 1e-5f) continue;
                        const double worldX = sinLongitude[x] * latitudeCosine, worldZ = cosLongitude[x] * latitudeCosine;
                        const double cameraZ = inverse(2, 0) * worldX + inverse(2, 1) * worldY + inverse(2, 2) * worldZ;
                        if (cameraZ <= 0) continue;
                        const double u = fx * (inverse(0, 0) * worldX + inverse(0, 1) * worldY + inverse(0, 2) * worldZ) / cameraZ + cx;
                        const double v = fy * (inverse(1, 0) * worldX + inverse(1, 1) * worldY + inverse(1, 2) * worldZ) / cameraZ + cy;
                        if (u < 0 || v < 0 || u >= original.cols - 1 || v >= original.rows - 1) continue;
                        const double edge = std::min(1.0 - std::abs((u - cx) / (original.cols * 0.5)), 1.0 - std::abs((v - cy) / (original.rows * 0.5)));
                        const double feather = bounded(edge / 0.25, 0.0, 1.0);
                        const int weight = std::max(1, cvRound(255.0 * seamWeight * feather * feather * (3 - 2 * feather) * cameraZ * cameraZ));
                        const int oldWeight = weightRow[x], totalWeight = oldWeight + weight;
                        const int left = static_cast<int>(u), top = static_cast<int>(v);
                        const double du = u - left, dv = v - top;
                        const auto* upper = original.ptr<cv::Vec3b>(top);
                        const auto* lower = original.ptr<cv::Vec3b>(top + 1);
                        const cv::Vec3f gain = sampleGain(seam, seamX, seamY);
                        if (oldWeight == 0) ++newCoverage;
                        for (int channel = 0; channel < 3; ++channel) {
                            const double sampled = ((1 - dv) * ((1 - du) * upper[left][channel] + du * upper[left + 1][channel]) +
                                dv * ((1 - du) * lower[left][channel] + du * lower[left + 1][channel])) * gain[channel];
                            const double adjusted = bounded(sampled, 0.0, 255.0);
                            pixels[x][channel] = cv::saturate_cast<uchar>((pixels[x][channel] * oldWeight + adjusted * weight) / totalWeight);
                        }
                        weightRow[x] = static_cast<uint16_t>(std::min(65535, totalWeight));
                    }
                    coveredByRow[y] += newCoverage;
                }
            });
            const double fraction = static_cast<double>(stripeEnd - firstRow) / std::max(1, lastRow - firstRow);
            progress.report(60 + static_cast<int>(34 * (index + fraction) / frames.size()), "Blending original photo " + std::to_string(index + 1) + " of " + std::to_string(frames.size()));
        }
    }
    for (int stripe = 0; stripe < height; stripe += 128) {
        progress.report(94, "Finishing smooth exposure transitions");
        cv::parallel_for_(cv::Range(stripe, std::min(height, stripe + 128)), [&](const cv::Range& rows) {
            for (int y = rows.start; y < rows.end; ++y) {
                auto* pixels = panorama.ptr<cv::Vec3b>(y);
                const auto* coverage = weights.ptr<uint16_t>(y);
                const double sy = (y + 0.5) * seams.height / height - 0.5;
                for (int x = 0; x < width; ++x) {
                    if (!coverage[x]) continue; // Missing directions remain honest neutral fill.
                    const double sx = (x + 0.5) * seams.width / width + seams.padding - 0.5;
                    const cv::Vec3f correction = sampleColor(seams.lowFrequencyCorrection, sx, sy);
                    for (int channel = 0; channel < 3; ++channel)
                        pixels[x][channel] = cv::saturate_cast<uchar>(pixels[x][channel] + correction[channel]);
                }
            }
        });
    }
    RenderStats stats;
    double weightedCovered = 0, weightedTotal = 0;
    for (int y = 0; y < height; ++y) {
        stats.coveredPixels += coveredByRow[y];
        weightedCovered += coveredByRow[y] * cosLatitude[y];
        weightedTotal += width * cosLatitude[y];
    }
    stats.pixelCoverage = static_cast<double>(stats.coveredPixels) / (static_cast<double>(width) * height);
    stats.coverage = weightedTotal > 0 ? weightedCovered / weightedTotal : 0;
    return stats;
}

class OutputFile {
public:
    explicit OutputFile(std::string finalName) : path(std::move(finalName)), temporary(path + ".partial.jpg") {
        if (path.empty() || exists(path) || exists(temporary)) throw std::runtime_error("Output path already exists or is invalid");
    }
    ~OutputFile() { if (ownsTemporary) std::remove(temporary.c_str()); if (ownsFinal && !retained) std::remove(path.c_str()); }
    void write(const cv::Mat& image, int quality, int samplingFactor) {
        const int descriptor = open(temporary.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600);
        if (descriptor < 0) throw std::runtime_error("Cannot create stitch output file");
        close(descriptor); ownsTemporary = true;
        if (!cv::imwrite(temporary, image, {cv::IMWRITE_JPEG_QUALITY, quality,
            cv::IMWRITE_JPEG_SAMPLING_FACTOR, samplingFactor})) throw std::runtime_error("Cannot encode stitched JPEG");
        const int synced = open(temporary.c_str(), O_RDONLY);
        if (synced < 0) throw std::runtime_error("Cannot sync stitched JPEG");
        const int syncResult = fsync(synced); close(synced);
        if (syncResult != 0) throw std::runtime_error("Cannot persist stitched JPEG");
    }
    void commit() {
        // Android SELinux forbids app-data hard links. Modern kernels can atomically
        // rename without replacement; older filesystems use our exclusive reservation.
#if defined(SYS_renameat2)
        constexpr unsigned int renameNoReplace = 1;
        if (syscall(SYS_renameat2, AT_FDCWD, temporary.c_str(), AT_FDCWD, path.c_str(), renameNoReplace) == 0) {
            ownsFinal = true; ownsTemporary = false;
            return;
        }
        const int renameError = errno;
        if (renameError != ENOSYS && renameError != EINVAL && renameError != EOPNOTSUPP && renameError != EPERM) {
            throw std::runtime_error(std::string("Cannot finalize stitched JPEG: ") + std::strerror(renameError));
        }
#endif
        // Output paths live in this job's private directory. O_EXCL proves that this
        // zero-byte reservation belongs to us, so replacing it cannot overwrite a
        // pre-existing panorama. A crash leaves an unpublished, invalid empty file.
        const int reservation = open(path.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
        if (reservation < 0) throw std::runtime_error(std::string("Cannot reserve stitched JPEG destination: ") + std::strerror(errno));
        ownsFinal = true;
        close(reservation);
        if (std::rename(temporary.c_str(), path.c_str()) != 0)
            throw std::runtime_error(std::string("Cannot finalize reserved stitched JPEG: ") + std::strerror(errno));
        ownsTemporary = false;
    }
    void keep() { retained = true; }
    const std::string path;
private:
    std::string temporary;
    bool ownsTemporary = false, ownsFinal = false, retained = false;
};

jstring errorResult(JNIEnv* env, const char* status, const char* message) noexcept {
    // No OpenCV calls or heap allocation in this error path: parser diagnostics
    // can include the entire input, exceeding FileStorage's string writer limit.
    // A fixed ASCII buffer also lets bad_alloc be reported safely through JNI.
    char json[4096];
    size_t used = 0;
    auto append = [&](const char* text) {
        while (*text && used + 1 < sizeof(json)) json[used++] = *text++;
    };
    append("{\"status\":\""); append(status); append("\",\"message\":\"");
    constexpr char hex[] = "0123456789abcdef";
    size_t length = 0;
    if (message) {
        for (; length < 480 && message[length]; ++length) {
            const unsigned char value = static_cast<unsigned char>(message[length]);
            if (value == '"' || value == '\\') { json[used++] = '\\'; json[used++] = static_cast<char>(value); }
            else if (value < 0x20 || value >= 0x7f) {
                append("\\u00"); json[used++] = hex[value >> 4]; json[used++] = hex[value & 15];
            } else json[used++] = static_cast<char>(value);
        }
        if (length == 480 && message[length]) append("...");
    }
    append("\"}"); json[used] = '\0';
    return env->NewStringUTF(json);
}

std::string runStitch(const std::string& framesJson, const std::string& outputPath, const std::string& previewPath, int requestedWidth, Progress& progress) {
    if (outputPath == previewPath) throw std::runtime_error("Full sphere and preview need separate output paths");
    if (requestedWidth < 512 || requestedWidth > 20480) throw std::runtime_error("Output width must be between 512 and 20480 pixels");
    OutputFile output(outputPath), previewOutput(previewPath);
    progress.report(0, "Checking original photographs and memory budget");
    int64_t memoryBudget = 1200LL * MIB, largestSourceBytes = 0;
    std::vector<Frame> frames = parseFrames(framesJson, memoryBudget, largestSourceBytes);
    // EXIF quarter-turn normalization can briefly keep a second decoded image alive.
    const int64_t compositionWorkspace = std::max<int64_t>(WORKING_RESERVE, largestSourceBytes + 32LL * MIB);
    const int64_t availableOutput = memoryBudget - largestSourceBytes - compositionWorkspace;
    if (availableOutput < 5LL * 512 * 256) throw std::runtime_error("Not enough available memory to decode an original and compose a sphere");
    const int maximumWidth = static_cast<int>(std::floor(std::sqrt(2.0 * availableOutput / 5.0) / 256.0)) * 256;
    int width = std::min(requestedWidth - requestedWidth % 2, maximumWidth);
    if (width < 512) throw std::runtime_error("Available memory supports less than the minimum sphere size");
    // SIFT builds Gaussian/DoG pyramids. Budget this phase separately, before allocating
    // any panorama. The per-feature allowance includes descriptors, four FLANN trees,
    // bearings, keypoints and the bounded neighbour graph's retained correspondences.
    const int featureLongSide = std::min(FEATURE_LONG_SIDE, static_cast<int>(std::sqrt(memoryBudget * 0.40 / 256.0)));
    const int64_t adjustmentVariables = static_cast<int64_t>(frames.size()) * 3 + 1;
    const int64_t adjustmentWorkspace = 3 * adjustmentVariables * adjustmentVariables * sizeof(double);
    const int64_t matchingWorkspace = 256LL * featureLongSide * featureLongSide + 16LL * MIB + adjustmentWorkspace;
    const int64_t thumbnailBytes = static_cast<int64_t>(frames.size()) * THUMB_LONG_SIDE * THUMB_LONG_SIDE * 3;
    const int featureLimit = static_cast<int>(std::min<int64_t>(2400,
        (memoryBudget - matchingWorkspace - thumbnailBytes) / (static_cast<int64_t>(frames.size()) * 3072)));
    if (featureLongSide < 256 || featureLimit < 128) throw std::runtime_error("Memory budget is too small to visually register this many photographs");
    const int64_t matchingEstimate = matchingWorkspace + thumbnailBytes + static_cast<int64_t>(frames.size()) * featureLimit * 3072;
    std::vector<std::string> warnings;
    if (width < requestedWidth) warnings.emplace_back("Output resolution was reduced to respect the available memory budget.");
    cv::setNumThreads(std::max(1, std::min(3, static_cast<int>(std::thread::hardware_concurrency()) - 1)));
    extractFeatures(frames, featureLongSide, featureLimit, progress);
    double supportedWidth = 20480;
    for (const Frame& frame : frames) {
        const double fx = frame.orientedSourceSize.width / (2.0 * std::tan(radians(frame.hfov / 2)));
        const double fy = frame.orientedSourceSize.height / (2.0 * std::tan(radians(frame.vfov / 2)));
        supportedWidth = std::min(supportedWidth, 2.0 * PI * std::min(fx, fy));
    }
    int sourceSupportedWidth = static_cast<int>(std::floor(supportedWidth / 256.0)) * 256;
    if (sourceSupportedWidth < 512) throw std::runtime_error("The source photographs do not support the minimum sphere resolution");
    if (width > sourceSupportedWidth) {
        width = sourceSupportedWidth;
        warnings.emplace_back("Output resolution was reduced to the angular sampling supported by the original photographs.");
    }
    const auto candidates = candidatePairs(frames);
    std::vector<Edge> edges;
    for (size_t i = 0; i < candidates.size(); ++i) {
        progress.report(20 + static_cast<int>(24 * i / std::max<size_t>(1, candidates.size())), "Verifying overlap " + std::to_string(i + 1) + " of " + std::to_string(candidates.size()));
        Edge edge;
        if (matchPair(frames, candidates[i].first, candidates[i].second, edge)) edges.push_back(std::move(edge));
    }
    if (edges.empty()) throw std::runtime_error("No visually verified overlap was found. Capture additional overlapping, textured views; sensor poses alone were not labelled as alignment.");
    const int components = refineGraph(frames, edges, progress);
    const double focalScale = refineRays(frames, edges, progress);
    sourceSupportedWidth = static_cast<int>(std::floor(std::min(20480.0, supportedWidth * focalScale) / 256.0)) * 256;
    if (sourceSupportedWidth < 512) throw std::runtime_error("Refined lens sampling does not support the minimum sphere resolution");
    if (width > sourceSupportedWidth) {
        width = sourceSupportedWidth;
        warnings.emplace_back("Output resolution was reduced to the visually refined lens sampling.");
    }
    if (focalScale > 1.199 || focalScale < 0.851)
        warnings.emplace_back("Lens-scale refinement reached its allowed limit; handheld parallax or uncertain calibration may still affect geometry.");
    if (std::abs(focalScale - 1) > 0.08)
        warnings.emplace_back("The fitted lens scale may be absorbing movement around the photographer rather than the lens. It is not a physical camera calibration.");
    int matchedImages = 0;
    size_t inlierCount = 0;
    double residualSum = 0, maxCorrection = 0;
    for (const Edge& edge : edges) for (const Observation& match : edge.inliers) {
        residualSum += degrees(angularDistance(frames[edge.a].rotation * match.a, frames[edge.b].rotation * match.b));
        ++inlierCount;
    }
    for (const Frame& frame : frames) {
        if (frame.component >= 0) ++matchedImages;
        maxCorrection = std::max(maxCorrection, degrees(rotationDifference(frame.prior, frame.rotation)));
    }
    const double meanResidual = inlierCount ? residualSum / inlierCount : 0;
    if (matchedImages < static_cast<int>(frames.size())) warnings.emplace_back(std::to_string(frames.size() - matchedImages) + " photographs lack verified visual overlap and use their recorded orientation. Their alignment is unverified.");
    if (components > 1) warnings.emplace_back("The visual overlap graph has " + std::to_string(components) + " separate components. Recorded orientations position those components relative to each other.");
    if (meanResidual > 0.5) warnings.emplace_back("Visual alignment residual is elevated; inspect seams for parallax, moving subjects, or inaccurate lens calibration.");
    warnings.emplace_back("Rotation refinement and selective seams cannot fully correct depth-dependent parallax. Nearby objects or moving subjects may still show breaks at joins.");
    // Descriptor indices and thumbnails are not retained while full originals are decoded.
    for (Frame& frame : frames) {
        frame.index.reset(); frame.descriptors.release(); frame.colorThumb.release();
        std::vector<cv::KeyPoint>().swap(frame.keypoints); std::vector<cv::Vec3d>().swap(frame.rays);
    }
    const int matchedPairs = static_cast<int>(edges.size());
    std::vector<Edge>().swap(edges);
    const SeamPlan seams = prepareSeams(frames, memoryBudget, progress);
    const int64_t finalWorkspace = std::max<int64_t>(compositionWorkspace, seams.retainedBytes + largestSourceBytes + 32LL * MIB);
    const int64_t finalAvailable = memoryBudget - largestSourceBytes - finalWorkspace;
    const int finalMaximumWidth = static_cast<int>(std::floor(std::sqrt(std::max(0.0, 2.0 * finalAvailable / 5.0)) / 256.0)) * 256;
    if (width > finalMaximumWidth) {
        width = finalMaximumWidth;
        warnings.emplace_back("Output resolution was reduced to include retained seam and exposure maps in the memory budget.");
    }
    if (width < 512) throw std::runtime_error("Not enough memory for original-resolution composition with seam maps");
    const int64_t compositionEstimate = 5LL * width * (width / 2) + largestSourceBytes + finalWorkspace;
    const int64_t estimatedPeak = std::max({compositionEstimate, matchingEstimate, seams.estimatedPeakBytes});
    cv::Mat panorama;
    const RenderStats stats = compose(frames, seams, width, panorama, progress);
    if (stats.coverage < 0.9995) warnings.emplace_back("Uncaptured directions remain neutral gray. The reported coverage is measured from rendered image footprints, not target counts.");
    progress.report(95, "Encoding the full-resolution sphere");
    output.write(panorama, 97, cv::IMWRITE_JPEG_SAMPLING_FACTOR_444);
    progress.report(97, "Creating the interactive preview");
    const int previewWidth = std::min(4096, width);
    cv::Mat preview;
    if (previewWidth == width) preview = panorama;
    else cv::resize(panorama, preview, cv::Size(previewWidth, previewWidth / 2), 0, 0, cv::INTER_AREA);
    previewOutput.write(preview, 90, cv::IMWRITE_JPEG_SAMPLING_FACTOR_420);
    progress.report(99, "Saving the sphere and coverage report");
    output.commit(); previewOutput.commit();
    cv::FileStorage report("", cv::FileStorage::WRITE | cv::FileStorage::MEMORY | cv::FileStorage::FORMAT_JSON);
    report << "status" << "complete" << "projection" << "equirectangular"
           << "alignmentMode" << "robust_joint_ray_rotation_and_focal_refinement"
           << "focalScale" << focalScale
           << "width" << width << "height" << width / 2 << "requestedWidth" << requestedWidth
           << "sourceSupportedWidth" << sourceSupportedWidth
           << "previewWidth" << previewWidth << "previewHeight" << previewWidth / 2
           << "jpegQuality" << 97 << "jpegChromaSubsampling" << "4:4:4"
           << "previewJpegQuality" << 90 << "previewJpegChromaSubsampling" << "4:2:0"
           << "outputPath" << outputPath << "previewPath" << previewPath
           << "inputImages" << static_cast<int>(frames.size()) << "candidatePairs" << static_cast<int>(candidates.size())
           << "visuallyMatchedImages" << matchedImages << "visuallyMatchedPairs" << matchedPairs
           << "visualComponents" << components << "verifiedCorrespondences" << static_cast<int>(inlierCount)
           << "meanVisualResidualDegrees" << meanResidual << "maxPoseCorrectionDegrees" << maxCorrection
           << "coverageFraction" << stats.coverage << "pixelCoverageFraction" << stats.pixelCoverage
           << "coveredPixels" << static_cast<double>(stats.coveredPixels)
           << "estimatedPeakBytes" << static_cast<double>(estimatedPeak)
           << "compositionEstimatedPeakBytes" << static_cast<double>(compositionEstimate)
           << "matchingEstimatedPeakBytes" << static_cast<double>(matchingEstimate)
           << "seamEstimatedPeakBytes" << static_cast<double>(seams.estimatedPeakBytes)
           << "seamRetainedBytes" << static_cast<double>(seams.retainedBytes)
           << "seamCanvasWidth" << seams.width << "maximumPhotometricGain" << seams.maximumGain
           << "lowFrequencyBlendSigmaPixels" << seams.lowFrequencySigma
           << "maximumLowFrequencyCorrection" << seams.maximumLowFrequencyCorrection
           << "rmsLowFrequencyCorrection" << seams.rmsLowFrequencyCorrection
           << "matchingLongSide" << featureLongSide << "maximumFeaturesPerImage" << featureLimit
           << "memoryBudgetBytes" << static_cast<double>(memoryBudget)
           << "composition" << "original_resolution_rgb8_constrained_graphcut_two_band_blend"
           << "exposureModel" << "bounded_spatial_rgb_gain_grid_and_low_frequency_overlap_blend"
           << "neutralFillRgb" << "[" << 32 << 32 << 32 << "]"
           << "warnings" << "[";
    for (const std::string& warning : warnings) report << warning;
    report << "]" << "frames" << "[";
    for (size_t i = 0; i < frames.size(); ++i) {
        const Frame& frame = frames[i];
        const cv::Scalar meanGain = cv::mean(seams.patches[i].gain);
        report << "{" << "index" << static_cast<int>(i) << "visuallyMatched" << (frame.component >= 0 ? 1 : 0)
               << "component" << frame.component << "exposureGain" << (meanGain[0] + meanGain[1] + meanGain[2]) / 3
               << "exposureRgbGain" << "[" << meanGain[2] << meanGain[1] << meanGain[0] << "]"
               << "rotation" << "[";
        for (double value : frame.rotation.val) report << value;
        report << "]" << "}";
    }
    report << "]";
    const std::string json = report.releaseAndGetString();
    progress.report(100, "Sphere saved");
    output.keep(); previewOutput.keep();
    return json;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_photosphere_NativeStitcher_stitch(
    JNIEnv* env, jclass, jstring framesJson, jstring outputPath, jstring previewPath, jint outputWidth, jobject callback) {
    try {
        std::unique_lock<std::mutex> lock(stitchMutex, std::try_to_lock);
        if (!lock.owns_lock()) throw std::runtime_error("Another sphere is already being processed");
        Progress progress(env, callback);
        const std::string result = runStitch(javaString(env, framesJson), javaString(env, outputPath), javaString(env, previewPath), outputWidth, progress);
        return env->NewStringUTF(result.c_str());
    } catch (const Cancelled& error) { return errorResult(env, "cancelled", error.what()); }
      catch (const cv::Exception& error) { return errorResult(env, "error", error.err.c_str()); }
      catch (const std::bad_alloc&) { return errorResult(env, "error", "Not enough memory for this sphere. Retry with a smaller output size."); }
      catch (const std::exception& error) { return errorResult(env, "error", error.what()); }
      catch (...) { return errorResult(env, "error", "Unexpected native image processing failure"); }
}
