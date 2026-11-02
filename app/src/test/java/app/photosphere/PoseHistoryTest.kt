package app.photosphere

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PoseHistoryTest {
    private fun p(time: Long, yaw: Double = 0.0) = Pose(yaw, 0.0, 0.0, 0.0, time)

    @Test fun associatesNearestSampleOnlyInCompatibleClockDomain() {
        val history = PoseHistory()
        history.add(p(1_000_000_000L, 0.0))
        history.add(p(1_100_000_000L, 8.0))
        val trigger = p(1_000_000_000L)
        val matched = history.associate(trigger, 1_105_000_000L, true)
        assertEquals(8.0, matched.pose.yaw, .001)
        assertEquals(-5_000_000L, matched.offsetNs)
        assertEquals("sensor_timestamp_nearest", matched.source)
        assertEquals("capture_request_only", history.associate(trigger, 1_105_000_000L, false).source)
        assertEquals(0.0, history.associate(trigger, 1_105_000_000L, false).pose.yaw, .001)
    }

    @Test fun staleSampleCannotPretendToBeExposureTimePose() {
        val history = PoseHistory()
        history.add(p(1_000_000_000L))
        val result = history.associate(p(900_000_000L), 1_500_000_000L, true)
        assertEquals("capture_request_only", result.source)
        assertNull(result.offsetNs)
    }

    @Test fun historyIsBoundedAndIgnoresOutOfOrderSamples() {
        val history = PoseHistory(2)
        history.add(p(1_000_000_000L)); history.add(p(1_010_000_000L)); history.add(p(1_020_000_000L))
        history.add(p(1_000_000_000L))
        val result = history.associate(p(1_020_000_000L), 1_020_000_000L, true)
        assertEquals(2, result.samples.size)
        assertEquals(1_010_000_000L, result.samples.first().timestampNs)
    }
}
