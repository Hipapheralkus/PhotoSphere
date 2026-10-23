package app.photosphere

import kotlin.math.*

/** Degrees; yaw turns right, pitch looks up, roll tilts the phone clockwise. */
data class Pose(
    val yaw: Double,
    val pitch: Double,
    val roll: Double,
    val angularSpeed: Double,
    /** Sensor timestamp, in the elapsedRealtimeNanos clock domain. */
    val timestampNs: Long,
    val rotationVector: FloatArray = floatArrayOf(),
)

data class CaptureTarget(val id: Int, val yaw: Double, val pitch: Double)

data class GuidanceState(
    val deltaYaw: Double,
    val deltaPitch: Double,
    val angularError: Double,
    val holdProgress: Double,
    val ready: Boolean,
    val sensorsFresh: Boolean,
    val aligned: Boolean,
    val level: Boolean,
    val still: Boolean,
)

fun wrapDegrees(degrees: Double): Double = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

internal data class CameraAngles(val yaw: Double, val pitch: Double, val roll: Double)

/** Pure matrix conversion for a rear camera with a portrait viewfinder. */
internal object OrientationMath {
    fun fromRotationMatrix(matrix: FloatArray): CameraAngles {
        require(matrix.size == 9)
        val x = -matrix[2].toDouble()
        val y = -matrix[5].toDouble()
        val z = -matrix[8].toDouble()
        val yaw = atan2(x, y)
        val pitch = asin(z.coerceIn(-1.0, 1.0))
        val upX = matrix[1].toDouble()
        val upY = matrix[4].toDouble()
        val upZ = matrix[7].toDouble()
        val upDotRight = upX * cos(yaw) - upY * sin(yaw)
        val upDotUp = -upX * sin(pitch) * sin(yaw) - upY * sin(pitch) * cos(yaw) + upZ * cos(pitch)
        return CameraAngles(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(atan2(upDotRight, upDotUp)))
    }
}

object CapturePlan {
    /** Portrait FOV, measured for the selected JPEG crop. Generous overlap absorbs aiming error. */
    fun generate(horizontalFov: Double, verticalFov: Double): List<CaptureTarget> {
        require(horizontalFov.isFinite() && horizontalFov in 15.0..150.0)
        require(verticalFov.isFinite() && verticalFov in 15.0..150.0)
        val plan = mutableListOf<CaptureTarget>()
        val stepsToPole = ceil(90.0 / (verticalFov * 0.55)).toInt().coerceAtLeast(1)
        val rowStep = 90.0 / stepsToPole
        var lastYaw = 0.0
        var clockwise = true
        fun addRow(pitch: Double) {
            val count = ceil(360.0 * cos(Math.toRadians(pitch)) / (horizontalFov * 0.55))
                .toInt().coerceAtLeast(3)
            val sign = if (clockwise) 1.0 else -1.0
            val firstYaw = lastYaw
            repeat(count) { index ->
                lastYaw = wrapDegrees(firstYaw + sign * index * 360.0 / count)
                plan += CaptureTarget(plan.size, lastYaw, pitch)
            }
            clockwise = !clockwise
        }
        addRow(0.0)
        for (row in 1 until stepsToPole) addRow(row * rowStep)
        plan += CaptureTarget(plan.size, lastYaw, 90.0)
        for (row in 1 until stepsToPole) addRow(-row * rowStep)
        plan += CaptureTarget(plan.size, lastYaw, -90.0)
        return plan
    }

    /** A rectangular pinhole view, useful for coverage diagnostics and tests. */
    fun covers(target: CaptureTarget, yaw: Double, pitch: Double, horizontalFov: Double, verticalFov: Double): Boolean {
        val p = Math.toRadians(pitch)
        val tp = Math.toRadians(target.pitch)
        val dy = Math.toRadians(wrapDegrees(yaw - target.yaw))
        val forward = sin(tp) * sin(p) + cos(tp) * cos(p) * cos(dy)
        if (forward <= 0.0) return false
        val right = cos(p) * sin(dy)
        val up = cos(tp) * sin(p) - sin(tp) * cos(p) * cos(dy)
        return abs(right / forward) <= tan(Math.toRadians(horizontalFov / 2.0)) + 1e-10 &&
            abs(up / forward) <= tan(Math.toRadians(verticalFov / 2.0)) + 1e-10
    }
}

/** Stateful gate. Call markCaptureStarted immediately when accepting ready, then reset for a retake. */
class Guidance(
    private val aimTolerance: Double = 3.0,
    private val rollTolerance: Double = 3.0,
    private val speedTolerance: Double = 2.0,
    private val holdMs: Long = 650L,
    private val maxSensorAgeMs: Long = 250L,
) {
    private var activeTarget: CaptureTarget? = null
    private var stableSinceMs: Long? = null
    private var captureStarted = false
    private var previousNowMs: Long? = null
    private var previousSensorNs: Long? = null

    init {
        require(holdMs > 0 && maxSensorAgeMs > 0)
        require(aimTolerance > 0 && rollTolerance > 0 && speedTolerance >= 0)
    }

    fun reset() {
        activeTarget = null
        stableSinceMs = null
        captureStarted = false
        previousNowMs = null
        previousSensorNs = null
    }

    fun markCaptureStarted() { captureStarted = true; stableSinceMs = null }

    fun evaluate(pose: Pose, target: CaptureTarget, nowMs: Long): GuidanceState {
        if (activeTarget != target) {
            reset()
            activeTarget = target
        }
        val ageMs = nowMs - pose.timestampNs / 1_000_000L
        val validAngles = pose.yaw.isFinite() && pose.pitch.isFinite() && pose.roll.isFinite() && pose.pitch in -90.0..90.0
        val timeWentBack = previousNowMs?.let { nowMs < it } == true ||
            previousSensorNs?.let { pose.timestampNs < it } == true
        val sensorGap = previousSensorNs?.let { pose.timestampNs - it > maxSensorAgeMs * 1_000_000L } == true
        val fresh = pose.timestampNs > 0 && ageMs in -50L..maxSensorAgeMs && !timeWentBack && validAngles
        val dy = wrapDegrees(target.yaw - pose.yaw)
        val p = Math.toRadians(pose.pitch)
        val tp = Math.toRadians(target.pitch)
        val dot = sin(p) * sin(tp) + cos(p) * cos(tp) * cos(Math.toRadians(dy))
        val error = if (validAngles) Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))) else Double.POSITIVE_INFINITY
        val aligned = error <= aimTolerance
        val level = abs(target.pitch) >= 75.0 || (pose.roll.isFinite() && abs(wrapDegrees(pose.roll)) <= rollTolerance)
        val still = pose.angularSpeed.isFinite() && pose.angularSpeed in 0.0..speedTolerance
        if (sensorGap) stableSinceMs = null
        if (fresh && aligned && level && still && !captureStarted) {
            if (stableSinceMs == null) stableSinceMs = nowMs
        } else stableSinceMs = null
        val progress = stableSinceMs?.let { ((nowMs - it).toDouble() / holdMs).coerceIn(0.0, 1.0) } ?: 0.0
        previousNowMs = nowMs
        previousSensorNs = pose.timestampNs
        return GuidanceState(
            deltaYaw = if (abs(target.pitch) >= 89.999) 0.0 else dy,
            deltaPitch = target.pitch - pose.pitch,
            angularError = error,
            holdProgress = progress,
            ready = progress >= 1.0 && !captureStarted,
            sensorsFresh = fresh,
            aligned = aligned,
            level = level,
            still = still,
        )
    }
}
