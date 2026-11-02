package app.photosphere

/** Quality changes require opening a fresh camera session. Never silently downsample originals. */
enum class CaptureQuality { FAST, DETAIL }

data class CameraInfo(
    val cameraId: String,
    val jpegWidth: Int,
    val jpegHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val sensorOrientation: Int,
    /**
     * Fixed portrait-viewfinder field of view, independent of current display rotation.
     * A landscape viewfinder swaps these axes; sensorOrientation remains the sensor's value.
     */
    val horizontalFov: Double,
    val verticalFov: Double,
    val maxResolutionMode: Boolean,
    val diagnosticsJson: String,
    val aeLockSupported: Boolean = false,
    val awbLockSupported: Boolean = false,
) {
    val megapixels: Double get() = jpegWidth.toDouble() * jpegHeight / 1_000_000.0
}

data class CaptureMetadata(
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val focalLengthMm: Float?,
    val focusDistanceDiopters: Float?,
    val jpegOrientation: Int,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val durationMs: Long,
    val cameraMetadataJson: String,
    /** Identifies the live capture session; never reuses a lock from a previous sphere. */
    val brightnessSessionId: Long? = null,
)
