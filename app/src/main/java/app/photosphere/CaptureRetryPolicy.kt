package app.photosphere

import java.util.Locale

enum class CaptureFailureKind { MOTION, TRANSIENT_CAMERA, PERMANENT }

data class CaptureRetryDecision(val retryDelayMs: Long?, val attempt: Int)

/** Consecutive failures are bounded; only a saved photo or explicit user retry resets the burst. */
class CaptureRetryPolicy {
    private var failures = 0
    private var blocked = false

    fun failed(kind: CaptureFailureKind): CaptureRetryDecision {
        if (kind == CaptureFailureKind.PERMANENT) blocked = true
        failures = (failures + 1).coerceAtMost(4)
        val delay = if (blocked || failures > 3) null else 700L shl (failures - 1)
        return CaptureRetryDecision(delay, failures)
    }

    fun reset() { failures = 0; blocked = false }

    companion object {
        /** CameraEngine exposes strings. Only its known recoverable messages are admitted. */
        fun classifyCameraMessage(message: String): CaptureFailureKind {
            val text = message.lowercase(Locale.ROOT)
            if (listOf("permission", "security", "denied", "disabled", "storage", "space", "disk", "directory",
                    "destination", "save the original", "queued for saving", "preserve photograph metadata", "enospc", "eacces")
                    .any(text::contains)) return CaptureFailureKind.PERMANENT
            if (Regex("camera error [1245]\\.").containsMatchIn(text)) return CaptureFailureKind.TRANSIENT_CAMERA
            return if (listOf("the camera is not ready.", "a photograph is already being saved.",
                    "opening the camera timed out.", "the camera disconnected.", "capture timed out",
                    "the camera aborted the photograph.", "the camera lost the photograph buffer.",
                    "the camera failed to capture the photograph", "capture failed:", "could not start focus:",
                    "could not read the photograph:", "the camera did not return a sensor timestamp")
                    .any(text::startsWith)) CaptureFailureKind.TRANSIENT_CAMERA else CaptureFailureKind.PERMANENT
        }
    }
}
