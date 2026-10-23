package app.photosphere

import kotlin.math.abs

data class PoseAssociation(
    val pose: Pose,
    val samples: List<Pose>,
    val source: String,
    val offsetNs: Long?,
)

/** Bounded, main-thread sensor history. Camera2 UNKNOWN timestamps must never be compared. */
class PoseHistory(private val capacity: Int = 2000) {
    private val samples = ArrayDeque<Pose>()
    fun clear() = samples.clear()
    fun add(pose: Pose) {
        if (samples.lastOrNull()?.timestampNs?.let { pose.timestampNs <= it } == true) return
        samples.addLast(pose)
        while (samples.size > capacity) samples.removeFirst()
    }
    fun associate(trigger: Pose, exposureNs: Long, realtime: Boolean): PoseAssociation {
        val nearest = if (realtime && exposureNs > 0) samples.minByOrNull { abs(it.timestampNs - exposureNs) } else null
        val precise = nearest != null && abs(nearest.timestampNs - exposureNs) <= 100_000_000L
        val selected = if (precise) nearest!! else trigger
        val center = if (precise) exposureNs else trigger.timestampNs
        return PoseAssociation(
            selected,
            samples.filter { abs(it.timestampNs - center) <= 1_000_000_000L },
            if (precise) "sensor_timestamp_nearest" else "capture_request_only",
            if (precise) selected.timestampNs - exposureNs else null,
        )
    }
}
