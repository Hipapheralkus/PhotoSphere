package app.photosphere

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class GuidanceTest {
    private fun pose(now: Long, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0, speed: Double = 0.0) =
        Pose(yaw, pitch, roll, speed, now * 1_000_000L)
    private val horizon = CaptureTarget(0, 0.0, 0.0)

    private fun hold(gate: Guidance, target: CaptureTarget, start: Long, end: Long, yaw: Double = 0.0) {
        for (now in start..end step 100L) gate.evaluate(pose(now, yaw = yaw), target, now)
    }

    @Test fun planCoversSphereIncludingPolesAndWrapWithSafetyMargin() {
        listOf(30.0 to 45.0, 48.0 to 65.0, 65.0 to 85.0, 80.0 to 110.0).forEach { (horizontal, vertical) ->
            val plan = CapturePlan.generate(horizontal, vertical)
            assertEquals(plan.size, plan.map { it.id }.toSet().size)
            assertTrue(plan.any { it.pitch == 90.0 })
            assertTrue(plan.any { it.pitch == -90.0 })
            for (pitch in -90..90 step 3) for (yaw in -180 until 180 step 3) {
                assertTrue(plan.any {
                    CapturePlan.covers(it, yaw.toDouble(), pitch.toDouble(), horizontal * 0.85, vertical * 0.85)
                }, "Uncovered $yaw,$pitch at FOV $horizontal,$vertical")
            }
        }
    }

    @Test fun wraparoundUsesShortDirection() {
        val state = Guidance().evaluate(pose(1000, yaw = 179.0), CaptureTarget(0, -179.0, 0.0), 1000)
        assertEquals(2.0, state.deltaYaw, 0.00001)
        assertEquals(2.0, state.angularError, 0.00001)
    }

    @Test fun polesDoNotRequireArbitraryYawOrRoll() {
        val state = Guidance().evaluate(pose(1000, yaw = 120.0, pitch = 89.0, roll = 80.0), CaptureTarget(0, 0.0, 90.0), 1000)
        assertEquals(1.0, state.angularError, 0.00001)
        assertEquals(0.0, state.deltaYaw, 0.0)
        assertTrue(state.level)
    }

    @Test fun requiresContinuousHoldAndLatchesOnceCaptureStarts() {
        val gate = Guidance()
        assertFalse(gate.evaluate(pose(1000), horizon, 1000).ready)
        hold(gate, horizon, 1100, 1600)
        assertFalse(gate.evaluate(pose(1649), horizon, 1649).ready)
        assertTrue(gate.evaluate(pose(1650), horizon, 1650).ready)
        gate.markCaptureStarted()
        assertFalse(gate.evaluate(pose(3000), horizon, 3000).ready)
        assertFalse(gate.evaluate(pose(4000), horizon, 4000).ready)
        gate.reset()
        assertFalse(gate.evaluate(pose(5000), horizon, 5000).ready)
        hold(gate, horizon, 5100, 5600)
        assertTrue(gate.evaluate(pose(5650), horizon, 5650).ready)
    }

    @Test fun briefAimJitterFreezesProgressWithoutFlashingTheDisplayOrFiring() {
        val gate = Guidance()
        hold(gate, horizon, 1000, 1500)
        val before = gate.evaluate(pose(1500), horizon, 1500).holdProgress
        val outlier = gate.evaluate(pose(1600, yaw = 3.3), horizon, 1600)
        assertEquals(before, outlier.holdProgress, 0.0)
        assertTrue(outlier.aligned)
        assertTrue(outlier.settling)
        assertFalse(outlier.captureSafe)
        assertFalse(outlier.ready)
        assertEquals(before, gate.evaluate(pose(1650, yaw = 2.9), horizon, 1650).holdProgress, 0.0)
        assertFalse(gate.evaluate(pose(1750), horizon, 1750).ready)
        assertTrue(gate.evaluate(pose(1800), horizon, 1800).ready)
        val unsafeAtFullProgress = gate.evaluate(pose(1810, speed = 7.0), horizon, 1810)
        assertTrue(unsafeAtFullProgress.still)
        assertFalse(unsafeAtFullProgress.ready)
    }

    @Test fun sustainedDepartureResetsProgressAndDebouncesExitIndicators() {
        val gate = Guidance()
        hold(gate, horizon, 1000, 1500)
        val brief = gate.evaluate(pose(1600, yaw = 5.0, roll = 7.0, speed = 7.0), horizon, 1600)
        assertTrue(brief.aligned && brief.level && brief.still)
        assertFalse(brief.captureSafe)
        val departed = gate.evaluate(pose(1720, yaw = 5.0, roll = 7.0, speed = 7.0), horizon, 1720)
        assertFalse(departed.aligned || departed.level || departed.still)
        assertEquals(0.0, departed.holdProgress, 0.0)
        assertEquals(0.0, gate.evaluate(pose(1800), horizon, 1800).holdProgress, 0.0)
        hold(gate, horizon, 1900, 2400)
        assertTrue(gate.evaluate(pose(2450), horizon, 2450).ready)
    }

    @Test fun hysteresisBandsPreserveIndicatorsButNeverRelaxTheShutterGate() {
        val gate = Guidance()
        hold(gate, horizon, 1000, 1500)
        val near = gate.evaluate(pose(1600, yaw = 4.0, roll = 5.0, speed = 4.0), horizon, 1600)
        assertTrue(near.aligned && near.level && near.still)
        assertFalse(near.captureSafe)
        val sustained = gate.evaluate(pose(1720, yaw = 4.0, roll = 5.0, speed = 4.0), horizon, 1720)
        assertTrue(sustained.aligned && sustained.level && sustained.still)
        assertFalse(sustained.ready)
        assertEquals(0.0, sustained.holdProgress, 0.0)
        gate.reset()
        val cannotEnter = gate.evaluate(pose(2000, yaw = 4.0, roll = 5.0, speed = 4.0), horizon, 2000)
        assertFalse(cannotEnter.aligned || cannotEnter.level || cannotEnter.still)
    }

    @Test fun staleFutureAndBackwardsClocksCannotCompleteHold() {
        val gate = Guidance()
        gate.evaluate(pose(1000), horizon, 1000)
        hold(gate, horizon, 1100, 1600)
        val stale = gate.evaluate(pose(1200), horizon, 1800)
        assertFalse(stale.sensorsFresh)
        assertFalse(stale.ready)
        assertEquals(0.0, gate.evaluate(pose(1900), horizon, 1900).holdProgress, 0.0)
        assertFalse(gate.evaluate(pose(5000), horizon, 2000).sensorsFresh)
        assertFalse(gate.evaluate(pose(1800), horizon, 1800).sensorsFresh)
    }

    @Test fun changingTargetsStartsANewHold() {
        val gate = Guidance()
        gate.evaluate(pose(1000), horizon, 1000)
        hold(gate, horizon, 1100, 1600)
        assertTrue(gate.evaluate(pose(1650), horizon, 1650).ready)
        gate.markCaptureStarted()
        val next = CaptureTarget(1, 30.0, 0.0)
        assertEquals(0.0, gate.evaluate(pose(2000, yaw = 30.0), next, 2000).holdProgress, 0.0)
        hold(gate, next, 2100, 2600, yaw = 30.0)
        assertTrue(gate.evaluate(pose(2650, yaw = 30.0), next, 2650).ready)
    }

    @Test fun invalidSensorValuesAreNeverReady() {
        val gate = Guidance()
        val state = gate.evaluate(pose(1000, yaw = Double.NaN), horizon, 1000)
        assertFalse(state.sensorsFresh)
        assertFalse(state.ready)
        assertFalse(gate.evaluate(pose(2000, speed = Double.POSITIVE_INFINITY), horizon, 2000).still)
    }

    @Test fun sensorDeliveryGapRestartsHoldEvenWhenNewestPoseIsFresh() {
        val gate = Guidance()
        gate.evaluate(pose(1000), horizon, 1000)
        val afterGap = gate.evaluate(pose(1700), horizon, 1700)
        assertTrue(afterGap.sensorsFresh)
        assertEquals(0.0, afterGap.holdProgress, 0.0)
        assertFalse(afterGap.ready)
    }

    @Test fun repeatedPoseWithoutNewSensorSamplesCannotAdvanceTheHold() {
        val gate = Guidance()
        hold(gate, horizon, 1000, 1500)
        val previous = gate.evaluate(pose(1500), horizon, 1500).holdProgress
        assertEquals(previous, gate.evaluate(pose(1500), horizon, 1650).holdProgress, 0.0)
        assertFalse(gate.evaluate(pose(1500), horizon, 1700).ready)
    }

    @Test fun portraitRearCameraMatrixHasCorrectYawPitchAndRollSigns() {
        val north = OrientationMath.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f))
        assertEquals(0.0, north.yaw, 0.0001)
        assertEquals(0.0, north.pitch, 0.0001)
        assertEquals(0.0, north.roll, 0.0001)
        val east = OrientationMath.fromRotationMatrix(floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f))
        assertEquals(90.0, east.yaw, 0.0001)
        val up = OrientationMath.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, -0.5f, -0.8660254f, 0f, 0.8660254f, -0.5f))
        assertEquals(30.0, up.pitch, 0.0001)
        assertEquals(0.0, up.roll, 0.0001)
        val clockwise = OrientationMath.fromRotationMatrix(floatArrayOf(0.8660254f, 0.5f, 0f, 0f, 0f, -1f, -0.5f, 0.8660254f, 0f))
        assertEquals(30.0, clockwise.roll, 0.0001)
        val down = OrientationMath.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        assertEquals(-90.0, down.pitch, 0.0001)
        val zenith = OrientationMath.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f))
        assertEquals(90.0, zenith.pitch, 0.0001)
    }

    @Test fun allDisplayRotationsNormalizeLevelWithoutChangingOpticalDirection() {
        val matrices = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f),
        )
        matrices.forEachIndexed { rotation, matrix ->
            val angles = OrientationMath.fromRotationMatrix(matrix, rotation)
            assertEquals(0.0, angles.yaw, 0.0001)
            assertEquals(0.0, angles.pitch, 0.0001)
            assertEquals(0.0, angles.roll, 0.0001)
        }
        val slightlyTiltedLandscape = floatArrayOf(0.05233596f, -0.9986295f, 0f, 0f, 0f, -1f, 0.9986295f, 0.05233596f, 0f)
        assertEquals(3.0, OrientationMath.fromRotationMatrix(slightlyTiltedLandscape, 1).roll, 0.0001)
    }

    @Test fun targetSelectionAllowsLeftRightUpDownAndSkipsCapturedDirections() {
        val targets = listOf(horizon, CaptureTarget(1, 30.0, 0.0), CaptureTarget(2, -30.0, 0.0),
            CaptureTarget(3, 0.0, 30.0), CaptureTarget(4, 0.0, -30.0))
        assertEquals(2, TargetSelector.select(pose(1000, yaw = -29.0), targets, setOf(0), 1)?.id)
        assertEquals(1, TargetSelector.select(pose(1000, yaw = 29.0), targets, setOf(0), 2)?.id)
        assertEquals(3, TargetSelector.select(pose(1000, pitch = 29.0), targets, setOf(0), 1)?.id)
        assertEquals(4, TargetSelector.select(pose(1000, pitch = -29.0), targets, setOf(0), 3)?.id)
        assertEquals(0, TargetSelector.select(pose(1000), targets, setOf(1), 1)?.id)
        assertNull(TargetSelector.select(pose(1000), targets, targets.map { it.id }.toSet(), 0))
    }

    @Test fun targetSelectionUsesSphericalDistancesAndBoundaryHysteresis() {
        val boundary = listOf(CaptureTarget(0, -20.0, 0.0), CaptureTarget(1, 20.0, 0.0))
        assertEquals(0, TargetSelector.select(pose(1000, yaw = 1.0), boundary, emptySet(), 0)?.id)
        assertEquals(1, TargetSelector.select(pose(1000, yaw = 8.0), boundary, emptySet(), 0)?.id)
        val seam = listOf(CaptureTarget(0, 179.0, 0.0), CaptureTarget(1, -150.0, 0.0))
        assertEquals(0, TargetSelector.select(pose(1000, yaw = -179.0), seam, emptySet(), null)?.id)
        val poles = listOf(CaptureTarget(0, 0.0, 90.0), CaptureTarget(1, 150.0, 75.0))
        assertEquals(0, TargetSelector.select(pose(1000, yaw = 150.0, pitch = 89.0), poles, emptySet(), 1)?.id)
    }

    @Test fun mixedPortraitLandscapeCapturePlanRemainsCovered() {
        val plan = CapturePlan.generate(48.0, 65.0)
        assertEquals(plan, CapturePlan.generate(65.0, 48.0))
        for (pitch in -90..90 step 3) for (yaw in -180 until 180 step 3) {
            assertTrue(plan.any { target ->
                val roll = if (target.id % 2 == 0) 4.0 else 94.0
                CapturePlan.covers(target, yaw.toDouble(), pitch.toDouble(), 48.0 * 0.85, 65.0 * 0.85, roll)
            }, "Mixed-orientation hole at $yaw,$pitch")
        }
    }

    @Test fun efficientPixelPlanUses33NaturalStableDirectionsAndExtraKeeps48() {
        val efficient = CapturePlan.generate(56.23, 70.72)
        val extra = CapturePlan.generate(56.23, 70.72, CaptureOverlap.EXTRA)
        assertEquals(33, efficient.size)
        assertEquals(48, extra.size)
        assertEquals(CaptureTarget(0, 0.0, 0.0), efficient.first())
        assertEquals(efficient.indices.toList(), efficient.map { it.id })
        assertEquals(efficient, CapturePlan.generate(70.72, 56.23))
        assertTrue(efficient.any { it.pitch == 90.0 })
        assertTrue(efficient.any { it.pitch == -90.0 })
        assertEquals(efficient.size, efficient.map { it.yaw to it.pitch }.toSet().size)
        // Smaller lenses keep their conservative layout instead of accepting holes to save shots.
        assertEquals(74, CapturePlan.generate(47.0, 60.0).size)
        assertEquals(CapturePlan.generate(47.0, 60.0, CaptureOverlap.EXTRA), CapturePlan.generate(47.0, 60.0))
        assertTrue(CapturePlan.generate(53.3, 70.0).size > 33)
        assertEquals(33, CapturePlan.generate(53.5, 70.0).size)
    }

    @Test fun efficientSphericalVoronoiVerticesCertifyItsContinuousCoverageBound() {
        // Every supporting convex-hull face normal is a spherical Voronoi vertex. The
        // farthest of these certifies the continuous covering radius, not just sampled pixels.
        val radius = coveringRadius(CapturePlan.generate(56.23, 70.72))
        assertEquals(22.690480376, radius, .000001)
        assertTrue(radius + 3.0 + 1.0 < 56.23 / 2.0)
        assertTrue(coveringRadius(CapturePlan.generate(56.23, 70.72, CaptureOverlap.EXTRA)) < radius)
    }

    @Test fun denseSphereCertificateCoversAnyOrientationAndAnyThreeDegreeAimError() {
        val cases = listOf(Triple(30.0, 45.0, CaptureOverlap.EFFICIENT),
            Triple(47.0, 60.0, CaptureOverlap.EFFICIENT), Triple(56.23, 70.72, CaptureOverlap.EFFICIENT),
            Triple(56.23, 70.72, CaptureOverlap.EXTRA), Triple(65.0, 85.0, CaptureOverlap.EFFICIENT))
        for ((horizontal, vertical, overlap) in cases) {
            val directions = CapturePlan.generate(horizontal, vertical, overlap).map(::direction)
            var worstSample = 0.0
            for (pitch in -90..90) for (yaw in -180 until 180) {
                val point = direction(yaw.toDouble(), pitch.toDouble())
                val nearest = directions.maxOf { it.dot(point) }
                worstSample = max(worstSample, Math.toDegrees(acos(nearest.coerceIn(-1.0, 1.0))))
            }
            // Every unsampled point is within sqrt(.5^2+.5^2) degrees of this grid.
            // Spherical distance is Lipschitz, so this certifies the regions between samples.
            // A cone of half the shorter FOV is inside every rectangular frame, for ANY roll.
            assertTrue(worstSample + sqrt(.5) <= min(horizontal, vertical) / 2.0 - 3.0,
                "Coverage certificate failed for $horizontal/$vertical/$overlap")
        }
    }

    @Test fun actualFramesRemainCoveredWithMixedOrientationRollAndIndependentAimErrors() {
        for ((horizontal, vertical) in listOf(56.23 to 70.72, 47.0 to 60.0)) {
            val plan = CapturePlan.generate(horizontal, vertical)
            repeat(4) { scenario ->
                val frames = plan.map { target ->
                    val landscape = when (scenario) { 0 -> false; 1 -> true; 2 -> target.id % 2 == 0; else -> target.id % 3 != 0 }
                    val moved = displaced(target, 3.0, target.id * 137.50776 + scenario * 90.0)
                    val roll = if (abs(target.pitch) >= 75.0) wrapDegrees(target.id * 47.0 + scenario * 61.0)
                        else if ((target.id + scenario) % 2 == 0) 4.0 else -4.0
                    TestFrame(moved, if (landscape) vertical else horizontal, if (landscape) horizontal else vertical, roll)
                }
                for (pitch in -90..90 step 3) for (yaw in -180 until 180 step 3) {
                    assertTrue(frames.any {
                        CapturePlan.covers(it.target, yaw.toDouble(), pitch.toDouble(), it.hfov, it.vfov, it.roll)
                    }, "Capture-error hole at $yaw/$pitch, FOV $horizontal/$vertical, scenario $scenario")
                }
            }
        }
    }

    @Test fun pixelPlansHaveConnectedOverlapGraphsWithAtLeastThreeRobustNeighbours() {
        for (overlap in CaptureOverlap.entries) {
            val directions = CapturePlan.generate(56.23, 70.72, overlap).map(::direction)
            // Even if both frames aim 3 degrees apart, these edges retain at least 12 degrees
            // of common angular extent inside their roll-independent inscribed cones.
            val maximumSeparation = 56.23 - 2.0 * 3.0 - 12.0
            val neighbours = directions.mapIndexed { index, vector -> directions.indices.filter { other ->
                other != index && Math.toDegrees(acos(vector.dot(directions[other]).coerceIn(-1.0, 1.0))) <= maximumSeparation
            } }
            assertTrue(neighbours.all { it.size >= 3 }, "Insufficient overlapping neighbours for $overlap")
            val visited = mutableSetOf(0)
            val pending = java.util.ArrayDeque<Int>().apply { add(0) }
            while (pending.isNotEmpty()) neighbours[pending.removeFirst()].forEach { if (visited.add(it)) pending.add(it) }
            assertEquals(directions.size, visited.size, "Overlap graph is disconnected for $overlap")
        }
    }

    private data class TestFrame(val target: CaptureTarget, val hfov: Double, val vfov: Double, val roll: Double)
    private data class TestVector(val x: Double, val y: Double, val z: Double) {
        fun dot(other: TestVector) = x * other.x + y * other.y + z * other.z
        operator fun minus(other: TestVector) = TestVector(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Double) = TestVector(x * scale, y * scale, z * scale)
        operator fun plus(other: TestVector) = TestVector(x + other.x, y + other.y, z + other.z)
        fun cross(other: TestVector) = TestVector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    }

    private fun direction(target: CaptureTarget) = direction(target.yaw, target.pitch)
    private fun direction(yawDegrees: Double, pitchDegrees: Double): TestVector {
        val yaw = Math.toRadians(yawDegrees); val pitch = Math.toRadians(pitchDegrees)
        return TestVector(cos(pitch) * sin(yaw), cos(pitch) * cos(yaw), sin(pitch))
    }

    private fun displaced(target: CaptureTarget, distanceDegrees: Double, bearingDegrees: Double): CaptureTarget {
        val yaw = Math.toRadians(target.yaw); val pitch = Math.toRadians(target.pitch)
        val distance = Math.toRadians(distanceDegrees); val bearing = Math.toRadians(bearingDegrees)
        val right = TestVector(cos(yaw), -sin(yaw), 0.0)
        val up = TestVector(-sin(pitch) * sin(yaw), -sin(pitch) * cos(yaw), cos(pitch))
        val shifted = direction(target) * cos(distance) + (right * sin(bearing) + up * cos(bearing)) * sin(distance)
        return target.copy(yaw = Math.toDegrees(atan2(shifted.x, shifted.y)), pitch = Math.toDegrees(asin(shifted.z.coerceIn(-1.0, 1.0))))
    }

    private fun coveringRadius(plan: List<CaptureTarget>): Double {
        val vectors = plan.map(::direction)
        var maximum = 0.0
        var supportingFaces = 0
        for (a in vectors.indices) for (b in a + 1 until vectors.size) for (c in b + 1 until vectors.size) {
            var normal = (vectors[b] - vectors[a]).cross(vectors[c] - vectors[a])
            val norm = sqrt(normal.dot(normal))
            if (norm < 1e-10) continue
            normal *= 1.0 / norm
            if (normal.dot(vectors[a]) < 0.0) normal *= -1.0
            val distance = normal.dot(vectors[a])
            if (vectors.all { normal.dot(it) <= distance + 1e-9 }) {
                supportingFaces++
                maximum = max(maximum, Math.toDegrees(acos(distance.coerceIn(-1.0, 1.0))))
            }
        }
        assertTrue(supportingFaces > 0, "No enclosing spherical hull found")
        return maximum
    }
}
