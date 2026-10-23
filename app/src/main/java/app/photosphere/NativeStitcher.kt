package app.photosphere

import org.opencv.android.OpenCVLoader

/** Runs on the stitch worker. Return false to cancel; callbacks never touch views directly. */
fun interface StitchProgress {
    fun onProgress(percent: Int, message: String): Boolean
}

/** Visual registration and original-JPEG spherical composition; no camera or UI thread work. */
object NativeStitcher {
    init {
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }
        System.loadLibrary("photosphere_stitch")
    }

    /** Returns a JSON report with status complete, cancelled, or error. */
    @JvmStatic
    external fun stitch(
        framesJson: String,
        outputPath: String,
        previewPath: String,
        outputWidth: Int,
        callback: StitchProgress,
    ): String
}
