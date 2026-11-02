package app.photosphere

import kotlin.math.*

/** Degrees; yaw turns right, pitch looks up, roll tilts the current viewfinder clockwise. */
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
    /** Strict instantaneous safety, independent of the forgiving display indicators. */
    val captureSafe: Boolean,
    /** A brief outlier is freezing progress while the display remains steady. */
    val settling: Boolean,
)

fun wrapDegrees(degrees: Double): Double = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

internal data class CameraAngles(val yaw: Double, val pitch: Double, val roll: Double)

/** Pure rear-camera math. Display rotation is the Surface constant 0..3, not an angle. */
internal object OrientationMath {
    fun fromRotationMatrix(matrix: FloatArray, displayRotation: Int = 0): CameraAngles {
        val angles = DoubleArray(3)
        writeRotationMatrix(matrix, displayRotation, angles)
        return CameraAngles(angles[0], angles[1], angles[2])
    }

    /** Reuses the destination for sensor callbacks instead of allocating temporary angles. */
    fun writeRotationMatrix(matrix: FloatArray, displayRotation: Int, angles: DoubleArray) {
        require(matrix.size == 9)
        require(angles.size >= 3 && displayRotation in 0..3)
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
        angles[0] = Math.toDegrees(yaw)
        angles[1] = Math.toDegrees(pitch)
        // Display.ROTATION_90 compensates a physical counter-clockwise turn of the phone.
        // Add its clockwise rendering rotation to the physical clockwise roll.
        angles[2] = wrapDegrees(Math.toDegrees(atan2(upDotRight, upDotUp)) + displayRotation * 90.0)
    }
}

object TargetSelector {
    /** Select any uncaptured direction. A margin prevents target changes at cell boundaries. */
    fun select(pose: Pose, targets: List<CaptureTarget>, capturedIds: Set<Int>, previousId: Int?): CaptureTarget? {
        if (!pose.yaw.isFinite() || !pose.pitch.isFinite() || pose.pitch !in -90.0..90.0) return null
        val pitch = Math.toRadians(pose.pitch)
        val sinPitch = sin(pitch)
        val cosPitch = cos(pitch)
        var nearest: CaptureTarget? = null
        var previous: CaptureTarget? = null
        var bestDot = -2.0
        var previousDot = -2.0
        var index = 0
        while (index < targets.size) {
            val target = targets[index++]
            if (target.id in capturedIds) continue
            val targetPitch = Math.toRadians(target.pitch)
            val dot = sinPitch * sin(targetPitch) + cosPitch * cos(targetPitch) *
                cos(Math.toRadians(wrapDegrees(target.yaw - pose.yaw)))
            if (target.id == previousId) { previous = target; previousDot = dot }
            if (dot > bestDot) { nearest = target; bestDot = dot }
        }
        val candidate = nearest ?: return null
        val incumbent = previous ?: return candidate
        if (candidate.id == incumbent.id) return candidate
        val bestError = Math.toDegrees(acos(bestDot.coerceIn(-1.0, 1.0)))
        val oldError = Math.toDegrees(acos(previousDot.coerceIn(-1.0, 1.0)))
        return if (oldError - bestError >= 8.0 ||
            (bestError <= 3.0 && oldError > 4.5) ||
            (oldError >= 35.0 && oldError - bestError >= 2.0)) candidate else incumbent
    }
}

enum class CaptureOverlap { EFFICIENT, EXTRA }

object CapturePlan {
    // The 12 icosahedron vertices plus its 20 face centres have this exact maximum
    // nearest-direction distance. One extra horizon target makes "Start here" natural.
    private const val DUAL_COVERING_RADIUS = 22.690480376
    private const val CAPTURE_AIM_ERROR = 3.0
    private const val ADDITIONAL_CONE_MARGIN = 1.0

    /**
     * The shorter FOV inscribes a circular cone that survives any portrait/landscape or roll
     * choice, including unrestricted pole roll. The 33-direction layout is used only when this
     * cone covers its spherical cells after 3 degrees of aim error and another degree of margin.
     * Extra overlap retains the denser latitude plan; narrow lenses use it in both modes.
     */
    fun generate(horizontalFov: Double, verticalFov: Double, overlap: CaptureOverlap = CaptureOverlap.EFFICIENT): List<CaptureTarget> {
        require(horizontalFov.isFinite() && horizontalFov in 15.0..150.0)
        require(verticalFov.isFinite() && verticalFov in 15.0..150.0)
        val safeFov = min(horizontalFov, verticalFov)
        val extra = latitudePlan(safeFov)
        return if (overlap == CaptureOverlap.EFFICIENT && extra.size > 33 &&
            safeFov / 2.0 >= DUAL_COVERING_RADIUS + CAPTURE_AIM_ERROR + ADDITIONAL_CONE_MARGIN) {
            efficientDirections
        } else extra
    }

    private val efficientDirections: List<CaptureTarget> by lazy {
        // With a vertex at each pole, the remaining icosahedron vertices form two
        // five-point rings at +/-atan(1/2), staggered by 36 degrees.
        val vertices = mutableListOf(Vector(0.0, 0.0, 1.0), Vector(0.0, 0.0, -1.0))
        val latitude = atan(0.5)
        for (hemisphere in listOf(1.0, -1.0)) repeat(5) { index ->
            val yaw = Math.toRadians(index * 72.0 + if (hemisphere > 0.0) 0.0 else 36.0)
            vertices += Vector(cos(latitude) * sin(yaw), cos(latitude) * cos(yaw), hemisphere * sin(latitude))
        }
        val centres = mutableListOf<Vector>()
        val adjacentDot = 1.0 / sqrt(5.0)
        for (a in vertices.indices) for (b in a + 1 until vertices.size) for (c in b + 1 until vertices.size) {
            if (abs(vertices[a].dot(vertices[b]) - adjacentDot) < 1e-9 &&
                abs(vertices[a].dot(vertices[c]) - adjacentDot) < 1e-9 &&
                abs(vertices[b].dot(vertices[c]) - adjacentDot) < 1e-9) {
                centres += (vertices[a] + vertices[b] + vertices[c]).normalized()
            }
        }
        check(centres.size == 20)
        val directions = (vertices + centres).map { vector ->
            val yaw = if (abs(vector.z) > 1.0 - 1e-10) 0.0 else Math.toDegrees(atan2(vector.x, vector.y))
            CaptureTarget(0, wrapDegrees(yaw), Math.toDegrees(asin(vector.z.coerceIn(-1.0, 1.0))))
        }.sortedWith(compareBy<CaptureTarget> { abs(it.pitch) }.thenByDescending { it.pitch }.thenBy { it.yaw })
        (listOf(CaptureTarget(0, 0.0, 0.0)) + directions).mapIndexed { index, target -> target.copy(id = index) }
    }

    private data class Vector(val x: Double, val y: Double, val z: Double) {
        fun dot(other: Vector) = x * other.x + y * other.y + z * other.z
        operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)
        fun normalized(): Vector = sqrt(dot(this)).let { Vector(x / it, y / it, z / it) }
    }

    /** Conservative legacy latitude spacing, retained as the explicit Extra overlap choice. */
    private fun latitudePlan(safeFov: Double): List<CaptureTarget> {
        val plan = mutableListOf<CaptureTarget>()
        val stepsToPole = ceil(90.0 / (safeFov * 0.55)).toInt().coerceAtLeast(1)
        val rowStep = 90.0 / stepsToPole
        var lastYaw = 0.0
        var clockwise = true
        fun addRow(pitch: Double) {
            val count = ceil(360.0 * cos(Math.toRadians(pitch)) / (safeFov * 0.55))
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
    fun covers(target: CaptureTarget, yaw: Double, pitch: Double, horizontalFov: Double, verticalFov: Double, rollDegrees: Double = 0.0): Boolean {
        val p = Math.toRadians(pitch)
        val tp = Math.toRadians(target.pitch)
        val dy = Math.toRadians(wrapDegrees(yaw - target.yaw))
        val forward = sin(tp) * sin(p) + cos(tp) * cos(p) * cos(dy)
        if (forward <= 0.0) return false
        val worldRight = cos(p) * sin(dy)
        val worldUp = cos(tp) * sin(p) - sin(tp) * cos(p) * cos(dy)
        val roll = Math.toRadians(rollDegrees)
        val right = worldRight * cos(roll) - worldUp * sin(roll)
        val up = worldRight * sin(roll) + worldUp * cos(roll)
        return abs(right / forward) <= tan(Math.toRadians(horizontalFov / 2.0)) + 1e-10 &&
            abs(up / forward) <= tan(Math.toRadians(verticalFov / 2.0)) + 1e-10
    }
}

/** Stateful gate. Call markCaptureStarted immediately when accepting ready, then reset for a retake. */
class Guidance(
    private val aimTolerance: Double = 3.0,
    private val rollTolerance: Double = 4.0,
    private val speedTolerance: Double = 3.0,
    private val holdMs: Long = 650L,
    private val maxSensorAgeMs: Long = 250L,
    private val outlierGraceMs: Long = 120L,
) {
    private var activeTarget: CaptureTarget? = null
    private var heldMs = 0L
    private var captureStarted = false
    private var previousNowMs = -1L
    private var previousSensorNs = -1L
    private var previousSafe = false
    private var unsafeSinceMs = -1L
    private val aimIndicator = StableIndicator(aimTolerance, aimTolerance * 1.5)
    private val rollIndicator = StableIndicator(rollTolerance, rollTolerance * 1.5)
    private val speedIndicator = StableIndicator(speedTolerance, speedTolerance * 2.0)

    /** Only the UI gets hysteresis/debounce; the shutter always uses the strict thresholds. */
    private inner class StableIndicator(private val enter: Double, private val exit: Double) {
        private var value = false
        private var outsideSinceMs = -1L
        fun reset() { value = false; outsideSinceMs = -1L }
        fun update(measurement: Double, nowMs: Long): Boolean {
            if (measurement <= enter) { value = true; outsideSinceMs = -1L }
            else if (measurement <= exit) outsideSinceMs = -1L
            else if (value) {
                if (outsideSinceMs < 0) outsideSinceMs = nowMs
                if (nowMs - outsideSinceMs >= outlierGraceMs) value = false
            }
            return value
        }
    }

    init {
        require(holdMs > 0 && maxSensorAgeMs > 0 && outlierGraceMs > 0)
        require(aimTolerance > 0 && rollTolerance > 0 && speedTolerance >= 0)
    }

    fun reset() {
        activeTarget = null
        resetStability()
        captureStarted = false
        previousNowMs = -1L
        previousSensorNs = -1L
    }

    private fun resetStability() {
        heldMs = 0L
        previousSafe = false
        unsafeSinceMs = -1L
        aimIndicator.reset()
        rollIndicator.reset()
        speedIndicator.reset()
    }

    fun markCaptureStarted() { captureStarted = true; heldMs = 0L; previousSafe = false; unsafeSinceMs = -1L }

    fun evaluate(pose: Pose, target: CaptureTarget, nowMs: Long): GuidanceState {
        if (activeTarget != target) {
            reset()
            activeTarget = target
        }
        val ageMs = nowMs - pose.timestampNs / 1_000_000L
        val validAngles = pose.yaw.isFinite() && pose.pitch.isFinite() && pose.roll.isFinite() && pose.pitch in -90.0..90.0
        val timeWentBack = (previousNowMs >= 0 && nowMs < previousNowMs) ||
            (previousSensorNs >= 0 && pose.timestampNs < previousSensorNs)
        val sensorGap = previousSensorNs >= 0 && pose.timestampNs - previousSensorNs > maxSensorAgeMs * 1_000_000L
        val fresh = pose.timestampNs > 0 && ageMs in -50L..maxSensorAgeMs && !timeWentBack && validAngles
        val dy = wrapDegrees(target.yaw - pose.yaw)
        val p = Math.toRadians(pose.pitch)
        val tp = Math.toRadians(target.pitch)
        val dot = sin(p) * sin(tp) + cos(p) * cos(tp) * cos(Math.toRadians(dy))
        val error = if (validAngles) Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))) else Double.POSITIVE_INFINITY
        val rollError = if (abs(target.pitch) >= 75.0) 0.0 else abs(wrapDegrees(pose.roll))
        val speed = if (pose.angularSpeed.isFinite() && pose.angularSpeed >= 0.0) pose.angularSpeed else Double.POSITIVE_INFINITY
        val safe = fresh && error <= aimTolerance && rollError <= rollTolerance && speed <= speedTolerance
        if (!fresh || sensorGap) resetStability()
        val aligned = fresh && aimIndicator.update(error, nowMs)
        val level = fresh && rollIndicator.update(rollError, nowMs)
        val still = fresh && speedIndicator.update(speed, nowMs)
        if (fresh && !captureStarted) {
            if (safe) {
                if (unsafeSinceMs >= 0 && nowMs - unsafeSinceMs >= outlierGraceMs) heldMs = 0L
                unsafeSinceMs = -1L
                if (previousSafe && !sensorGap) {
                    val wallDelta = (nowMs - previousNowMs).coerceAtLeast(0L)
                    val sampleDelta = ((pose.timestampNs - previousSensorNs) / 1_000_000L).coerceAtLeast(0L)
                    heldMs = (heldMs + min(wallDelta, sampleDelta)).coerceAtMost(holdMs)
                }
            } else {
                if (unsafeSinceMs < 0) unsafeSinceMs = nowMs
                if (nowMs - unsafeSinceMs >= outlierGraceMs) heldMs = 0L
            }
        }
        val progress = (heldMs.toDouble() / holdMs).coerceIn(0.0, 1.0)
        val settling = fresh && !safe && unsafeSinceMs >= 0 && nowMs - unsafeSinceMs < outlierGraceMs && aligned && level && still
        previousSafe = safe && !captureStarted
        previousNowMs = nowMs
        previousSensorNs = pose.timestampNs
        return GuidanceState(
            deltaYaw = if (abs(target.pitch) >= 89.999) 0.0 else dy,
            deltaPitch = target.pitch - pose.pitch,
            angularError = error,
            holdProgress = progress,
            ready = progress >= 1.0 && safe && !captureStarted,
            sensorsFresh = fresh,
            aligned = aligned,
            level = level,
            still = still,
            captureSafe = safe,
            settling = settling,
        )
    }
}
