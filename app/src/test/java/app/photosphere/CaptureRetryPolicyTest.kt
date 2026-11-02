package app.photosphere

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureRetryPolicyTest {
    @Test fun mixedTransientFailuresStopAfterThreeRetries() {
        val policy = CaptureRetryPolicy()
        assertEquals(700L, policy.failed(CaptureFailureKind.MOTION).retryDelayMs)
        assertEquals(1400L, policy.failed(CaptureFailureKind.TRANSIENT_CAMERA).retryDelayMs)
        assertEquals(2800L, policy.failed(CaptureFailureKind.MOTION).retryDelayMs)
        repeat(4) { assertNull(policy.failed(CaptureFailureKind.TRANSIENT_CAMERA).retryDelayMs) }
        policy.reset()
        assertEquals(700L, policy.failed(CaptureFailureKind.MOTION).retryDelayMs)
    }

    @Test fun permanentFailureCannotBeRearmedByALaterCameraCallback() {
        val policy = CaptureRetryPolicy()
        assertNull(policy.failed(CaptureFailureKind.PERMANENT).retryDelayMs)
        assertNull(policy.failed(CaptureFailureKind.TRANSIENT_CAMERA).retryDelayMs)
        policy.reset()
        assertEquals(700L, policy.failed(CaptureFailureKind.TRANSIENT_CAMERA).retryDelayMs)
    }

    @Test fun storageAndPermissionWordsTakePrecedenceOverTransientPrefixes() {
        listOf("Capture failed: permission denied", "Capture failed: ENOSPC", "Capture failed: camera disabled",
            "Could not save the original photograph: disk full", "The photograph could not be queued for saving: closed",
            "Camera error 3. Close and reopen capture.", "An unknown camera problem")
            .forEach { assertEquals(CaptureFailureKind.PERMANENT, CaptureRetryPolicy.classifyCameraMessage(it), it) }
    }

    @Test fun knownCameraTimeoutsAndRecoverableDeviceErrorsCanRetry() {
        listOf("Capture timed out waiting for matching image and sensor metadata. Please retry.",
            "The camera disconnected. Close and reopen capture.", "The camera lost the photograph buffer. Please retry.",
            "The camera failed to capture the photograph (reason 0).", "Camera error 1. Close and reopen capture.",
            "Camera error 2. Close and reopen capture.", "Camera error 4. Close and reopen capture.",
            "Camera error 5. Close and reopen capture.")
            .forEach { assertEquals(CaptureFailureKind.TRANSIENT_CAMERA, CaptureRetryPolicy.classifyCameraMessage(it), it) }
    }
}
