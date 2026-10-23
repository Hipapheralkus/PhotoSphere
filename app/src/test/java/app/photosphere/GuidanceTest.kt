package app.photosphere

import org.junit.Assert.*
import org.junit.Test

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
                assertTrue("Uncovered $yaw,$pitch at FOV $horizontal,$vertical", plan.any {
                    CapturePlan.covers(it, yaw.toDouble(), pitch.toDouble(), horizontal * 0.85, vertical * 0.85)
                })
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
            assertTrue("Mixed-orientation hole at $yaw,$pitch", plan.any { target ->
                val roll = if (target.id % 2 == 0) 4.0 else 94.0
                CapturePlan.covers(target, yaw.toDouble(), pitch.toDouble(), 48.0 * 0.85, 65.0 * 0.85, roll)
            })
        }
    }
}
