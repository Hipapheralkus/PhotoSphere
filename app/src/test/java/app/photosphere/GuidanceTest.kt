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

    @Test fun departureRollAndMotionRestartHold() {
        val gate = Guidance()
        gate.evaluate(pose(1000), horizon, 1000)
        gate.evaluate(pose(1500, yaw = 5.0), horizon, 1500)
        assertEquals(0.0, gate.evaluate(pose(1600), horizon, 1600).holdProgress, 0.0)
        hold(gate, horizon, 1700, 2100)
        assertFalse(gate.evaluate(pose(2200), horizon, 2200).ready)
        assertTrue(gate.evaluate(pose(2250), horizon, 2250).ready)
        assertEquals(0.0, gate.evaluate(pose(2300, roll = 4.0), horizon, 2300).holdProgress, 0.0)
        gate.evaluate(pose(2400), horizon, 2400)
        assertEquals(0.0, gate.evaluate(pose(3000, speed = 3.0), horizon, 3000).holdProgress, 0.0)
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
}
