package app.photosphere

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

enum class StitchQuality { QUICK, FULL }

enum class StitchStatus { QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

data class PanoramaResult(
    val sessionId: String,
    val jobId: String,
    val image: File,
    val preview: File,
    val report: File,
    val width: Int,
    val height: Int,
    val sourceCount: Int,
    val coverageFraction: Double,
    val createdAtMs: Long,
    val warnings: List<String>,
    val visuallyMatchedImages: Int,
    val visuallyMatchedPairs: Int,
    val quality: StitchQuality,
    val estimatedPeakBytes: Long,
    val pixelCoverageFraction: Double,
)

data class StitchJobState(
    val sessionId: String,
    val jobId: String,
    val quality: StitchQuality,
    val status: StitchStatus,
    val progress: Int,
    val message: String,
    val result: PanoramaResult? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean get() = status == StitchStatus.QUEUED ||
        status == StitchStatus.RUNNING || status == StitchStatus.CANCELLING
}

/** Widths are source-supported full-sphere widths, not a promise that all directions were captured. */
data class StitchPlan(
    val sessionId: String,
    val sourceCount: Int,
    val targetCount: Int,
    val completePlanned: Boolean,
    val widthFull: Int,
    val widthQuick: Int,
    val memoryBudgetBytes: Long,
    val estimatedPeakBytesFull: Long,
    val estimatedPeakBytesQuick: Long,
    val estimatedOutputBytesFull: Long,
    val estimatedOutputBytesQuick: Long,
    val requiredFreeBytesFull: Long,
    val requiredFreeBytesQuick: Long,
    val warnings: List<String>,
) {
    fun width(quality: StitchQuality): Int = if (quality == StitchQuality.FULL) widthFull else widthQuick
    fun requiredFreeBytes(quality: StitchQuality): Long =
        if (quality == StitchQuality.FULL) requiredFreeBytesFull else requiredFreeBytesQuick
}

/** Uses EXIF-normalized JPEG dimensions and the matching current-viewfinder field-of-view axes. */
internal data class StitchSampling(val width: Int, val height: Int, val hfov: Double, val vfov: Double)

internal object StitchSizing {
    const val MIB = 1024L * 1024L
    const val WORKING_RESERVE = 128L * MIB
    const val MAX_BUDGET = 1200L * MIB
    const val MIN_WIDTH = 512

    data class Sizes(val full: Int, val quick: Int, val sourceWidth: Int, val memoryWidth: Int, val largestSourceBytes: Long)

    fun choose(images: List<StitchSampling>, budget: Long): Sizes {
        require(images.isNotEmpty()) { "No original images are available" }
        var minFocal = Double.POSITIVE_INFINITY
        var largestSource = 0L
        images.forEach { image ->
            require(image.width > 0 && image.height > 0 && image.hfov.isFinite() && image.vfov.isFinite() &&
                image.hfov in 5.0..175.0 && image.vfov in 5.0..175.0) { "Invalid original image sampling" }
            val fx = image.width / (2.0 * tan(Math.toRadians(image.hfov) / 2.0))
            val fy = image.height / (2.0 * tan(Math.toRadians(image.vfov) / 2.0))
            minFocal = min(minFocal, min(fx, fy))
            largestSource = maxOf(largestSource, image.width.toLong() * image.height * 3L)
        }
        val sourceWidth = floor256(2.0 * PI * minFocal)
        require(sourceWidth >= MIN_WIDTH) { "Originals do not support a $MIN_WIDTH-pixel panorama without upscaling" }
        val availableForPlanes = budget - WORKING_RESERVE - largestSource
        require(availableForPlanes > 0) { "Not enough available memory to decode the largest original; close other apps and try again" }
        // Native renderer retains one RGB8 canvas and one uint16 weight plane: five bytes/pixel.
        val memoryWidth = floor256(sqrt(availableForPlanes.toDouble() * 2.0 / 5.0))
        require(memoryWidth >= MIN_WIDTH) { "Not enough available memory for stitching; close other apps and try again" }
        val full = minOf(sourceWidth, memoryWidth, 20_480)
        return Sizes(full, minOf(full, 8192), sourceWidth, memoryWidth, largestSource)
    }

    fun estimatedPeak(width: Int, largestSourceBytes: Long): Long =
        width.toLong() * (width / 2) * 5L + largestSourceBytes + WORKING_RESERVE

    /** Conservative storage allowance, including preview and a replacement's staging files. */
    fun requiredFree(width: Int): Long = width.toLong() * (width / 2) * 4L + 32L * MIB
    fun estimatedOutput(width: Int): Long = width.toLong() * (width / 2) * 3L + 16L * MIB

    private fun floor256(value: Double): Int = (floor(value.coerceAtMost(Int.MAX_VALUE.toDouble()) / 256.0) * 256.0).toInt()
}

internal data class StitchRecordSelection(val targetId: Int, val accepted: Boolean)

internal object StitchInputRules {
    /** The manifest is an append-only journal; wall-clock corrections must not resurrect a retake. */
    fun latestAcceptedIndices(records: List<StitchRecordSelection>, targetIds: Set<Int>): Map<Int, Int> {
        val latest = linkedMapOf<Int, Int>()
        records.forEachIndexed { index, record ->
            if (record.accepted) {
                require(record.targetId in targetIds) { "Photo record refers to unknown target ${record.targetId}" }
                latest[record.targetId] = index
            }
        }
        return latest
    }
}

/** Pure process-restart reconciliation: the committed report is authoritative for completed output. */
internal object StitchRecovery {
    fun reconcile(saved: StitchJobState?, published: PanoramaResult?, now: Long): StitchJobState? {
        if (published != null && (saved == null || saved.jobId == published.jobId || saved.status == StitchStatus.SUCCEEDED)) {
            return StitchJobState(published.sessionId, published.jobId, published.quality, StitchStatus.SUCCEEDED,
                100, "Photo Sphere ready", published, published.createdAtMs)
        }
        if (saved?.isActive == true) return saved.copy(status = StitchStatus.INTERRUPTED,
            message = "Stitching was interrupted. Original photos are safe; generate again to restart.", result = published, updatedAtMs = now)
        if (saved?.status == StitchStatus.SUCCEEDED) return saved.copy(status = StitchStatus.FAILED,
            message = "The completed panorama is missing. Original photos are safe; generate it again.", result = null, updatedAtMs = now)
        return saved?.copy(result = published)
    }
}

/** Cancellation and the publication commit point are serialized, independently of Android lifecycle. */
internal class StitchWork(val sessionId: String, val jobId: String, val quality: StitchQuality) {
    private val publicationLock = Any()
    val committed = AtomicBoolean()
    val cancelled = AtomicBoolean()
    val terminal = AtomicBoolean()
    @Volatile var interruption: String? = null

    fun cancel(): Boolean = synchronized(publicationLock) {
        if (committed.get() || terminal.get()) return false
        cancelled.set(true)
        true
    }

    fun interrupt(message: String): Boolean = synchronized(publicationLock) {
        if (committed.get() || terminal.get()) return false
        interruption = message
        cancelled.set(true)
        true
    }

    fun commit(publishFiles: () -> Unit): Boolean = synchronized(publicationLock) {
        if (cancelled.get() || terminal.get()) return false
        publishFiles()
        committed.set(true)
        true
    }
}
