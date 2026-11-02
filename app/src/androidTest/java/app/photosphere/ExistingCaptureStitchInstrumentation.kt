package app.photosphere

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Xml
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Explicit runner for one user-selected existing capture. Required: -e session <id>.
 * Optional: -e quality FULL|QUICK. It takes no photos and deletes no session or result files.
 */
class ExistingCaptureStitchInstrumentation : Instrumentation() {
    private var runnerArguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runnerArguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val started = SystemClock.elapsedRealtime()
        val runId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val outputDirectory = File(targetContext.cacheDir, "existing-capture-stitch-validation/$runId")
        val reportFile = File(outputDirectory, "report.json")
        val originalsReport = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("model", Build.MODEL).put("androidSdk", Build.VERSION.SDK_INT)
            .put("originals", originalsReport).put("newPhotosRequested", 0).put("filesDeletedByRunner", 0)
        var failure: Throwable? = null
        var host: Activity? = null
        var sessionId: String? = null
        var jobId: String? = null
        var originals: List<OriginalSnapshot> = emptyList()
        var originalDirectory: File? = null
        var originalNames: Set<String> = emptySet()
        try {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Cannot create validation report directory" }
            val selectedId = runnerArguments.getString("session")?.trim().orEmpty()
            require(selectedId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Supply the existing capture explicitly with -e session <id>" }
            sessionId = selectedId
            val quality = StitchQuality.valueOf((runnerArguments.getString("quality") ?: "FULL").uppercase(Locale.ROOT))
            report.put("sessionId", selectedId).put("quality", quality.name)
            check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the phone before starting this validation" }

            progress("Checking the selected saved capture and hashing its original JPEGs")
            val input = StitchRepository.prepare(targetContext, selectedId)
            val originalsFolder = File(input.directory, "originals")
            originalDirectory = originalsFolder
            originalNames = originalsFolder.listFiles().orEmpty().map { it.name }.toSet()
            originals = input.frames.map { frame ->
                OriginalSnapshot(frame.targetId, frame.file, frame.file.length(), frame.file.inputStream().use(::sha256))
            }
            report.put("selectedOriginalCount", originals.size).put("plannedDirectionCount", input.plan.targetCount)
                .put("completePlannedCapture", input.plan.completePlanned)
                .put("plannedWidth", input.plan.width(quality)).put("planningMemoryBudgetBytes", input.plan.memoryBudgetBytes)
                .put("savedSourceGpsPresent", input.location != null)
            val prior = runCatching { StitchRepository.result(targetContext, selectedId) }.getOrNull()
            report.put("previousPanoramaPresent", prior != null)

            progress("Launching the ordinary app and starting ${quality.name.lowercase(Locale.ROOT)} stitching")
            host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            var accepted = false
            onMain {
                // Keep this explicit interactive validation visible through the later export check.
                // The flag belongs only to the test host and disappears when it finishes below.
                host?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                accepted = StitchRepository.start(targetContext, selectedId, quality)
            }
            check(accepted) { "Another stitching job is active; this runner did not start or cancel it" }
            val initial = StitchRepository.state(targetContext, selectedId) ?: error("Started job has no observable state")
            jobId = initial.jobId
            report.put("jobId", jobId)
            val generationStarted = SystemClock.elapsedRealtime()
            val terminal = awaitTerminal(selectedId, initial.jobId, generationStarted + MAX_GENERATION_MS)
            report.put("generationDurationMs", SystemClock.elapsedRealtime() - generationStarted)
                .put("terminalStatus", terminal.status.name).put("terminalMessage", terminal.message)
            check(terminal.status == StitchStatus.SUCCEEDED) { "Stitching ended with ${terminal.status}: ${terminal.message}" }
            val result = StitchRepository.result(targetContext, selectedId) ?: error("Successful job has no committed panorama")
            check(result.jobId == initial.jobId) { "The published panorama belongs to a different job" }
            check(result.quality == quality) { "The published panorama used a different quality choice" }
            check(result.sourceCount == originals.size) { "The stitcher used a different original-photo selection" }
            val committedReport = StitchRepository.readJson(result.report)
            val memoryBudget = committedReport.getLong("memoryBudgetBytes")
            report.put("width", result.width).put("height", result.height).put("outputBytes", result.image.length())
                .put("coverageFraction", result.coverageFraction).put("pixelCoverageFraction", result.pixelCoverageFraction)
                .put("visuallyMatchedImages", result.visuallyMatchedImages).put("visuallyMatchedPairs", result.visuallyMatchedPairs)
                .put("estimatedPeakBytes", result.estimatedPeakBytes).put("memoryBudgetBytes", memoryBudget)
                .put("warnings", JSONArray(result.warnings)).put("outputPath", result.image.absolutePath)
                .put("committedReportPath", result.report.absolutePath)
            val sourceFiles = StitchRepository.stringList(committedReport.getJSONArray("sourceFiles")).toSet()
            check(sourceFiles == originals.map { "originals/${it.file.name}" }.toSet()) { "Committed report does not identify the hashed original photos" }

            progress("Validating the generated JPEG, GPano metadata and saved GPS")
            val size = jpegDimensions(result.image)
            check(size.first == result.width && size.second == result.height && result.width == result.height * 2) {
                "Published panorama JPEG must match its reported full 2:1 dimensions"
            }
            val previewSize = jpegDimensions(result.preview)
            report.put("previewWidth", previewSize.first).put("previewHeight", previewSize.second)
            check(previewSize.first == previewSize.second * 2 && previewSize.first <= 4096) { "Interactive preview dimensions are invalid" }
            val exif = ExifInterface(result.image)
            check(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) == ExifInterface.ORIENTATION_NORMAL) {
                "The completed panorama must have normal EXIF orientation"
            }
            validateGpano(exif.getAttribute(ExifInterface.TAG_XMP) ?: error("Panorama has no XMP metadata"), result)
            report.put("gpanoVerified", true).put("jpegDimensionsVerified", true)

            val actualGps = exif.latLong
            val expectedGps = input.location
            val gpsMatches = if (expectedGps == null) actualGps == null else actualGps != null &&
                abs(actualGps[0] - expectedGps.latitude) <= .000001 && abs(actualGps[1] - expectedGps.longitude) <= .000001
            report.put("outputGpsPresent", actualGps != null).put("gpsMatchesSavedSource", gpsMatches)
            check(gpsMatches) { "Panorama GPS does not match the saved source location" }
            val altitudeMatches = expectedGps?.altitudeMeters?.let { expected ->
                val actual = exif.getAltitude(Double.NaN)
                actual.isFinite() && abs(actual - expected) <= .1
            } ?: true
            report.put("gpsAltitudeMatchesSavedSource", altitudeMatches)
            check(altitudeMatches) { "Panorama GPS altitude does not match the saved source" }
            val previewHasNoGps = ExifInterface(result.preview).latLong == null
            report.put("previewHasNoGps", previewHasNoGps)
            check(previewHasNoGps) { "The interactive preview unexpectedly contains geographic EXIF" }

            check(result.visuallyMatchedImages >= 2 && result.visuallyMatchedPairs >= 1) { "Generated output has no verified visual alignment" }
            check(result.coverageFraction.isFinite() && result.coverageFraction > 0 && result.coverageFraction <= 1) { "Rendered sphere coverage is invalid" }
            check(result.estimatedPeakBytes in 1..memoryBudget) { "Reported peak memory exceeds the configured budget" }
        } catch (error: Throwable) {
            failure = error
            report.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
        } finally {
            // On timeout/failure, cancel only this runner's own still-active job. The service
            // performs its normal unpublished-work cleanup; the runner removes no files.
            val cleanupSession = sessionId
            val cleanupJob = jobId
            if (cleanupSession != null && cleanupJob != null) runCatching {
                val state = StitchRepository.state(targetContext, cleanupSession)
                if (state?.jobId == cleanupJob && state.isActive) {
                    report.put("ownJobCancellationRequested", StitchRepository.cancel(targetContext, cleanupSession))
                }
            }.onFailure { report.put("jobCleanupError", it.toString()) }
            try {
                progress("Rehashing the selected originals; retaining all capture and panorama files")
                var allUnchanged = true
                originals.forEach { original ->
                    val after = runCatching { original.file.inputStream().use(::sha256) }.getOrNull()
                    val unchanged = original.file.length() == original.bytes && original.sha256 == after
                    originalsReport.put(JSONObject().put("targetId", original.targetId).put("file", original.file.name)
                        .put("bytes", original.bytes).put("sha256Before", original.sha256)
                        .put("sha256After", after ?: JSONObject.NULL).put("unchanged", unchanged))
                    allUnchanged = allUnchanged && unchanged
                }
                report.put("originalsUnchanged", originals.isNotEmpty() && allUnchanged)
                if (originals.isNotEmpty()) check(allUnchanged) { "One or more original JPEGs changed during generation" }
                originalDirectory?.let { directory ->
                    val namesAfter = directory.listFiles().orEmpty().map { it.name }.toSet()
                    val unchanged = namesAfter == originalNames
                    report.put("originalDirectoryEntriesUnchanged", unchanged)
                    check(unchanged) { "Original-photo directory entries changed during validation" }
                }
            } catch (error: Throwable) {
                report.put("originalVerificationError", error.toString())
                if (failure == null) failure = error
            }
            try { onMain { host?.takeUnless { it.isDestroyed }?.finish() }; waitForIdleSync() }
            catch (error: Throwable) { report.put("activityCleanupError", error.toString()); if (failure == null) failure = error }
        }
        report.put("passed", failure == null).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("retainedSessionId", sessionId ?: JSONObject.NULL)
        try {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Cannot create validation report directory" }
            FileOutputStream(reportFile).use { output -> output.write(report.toString(2).toByteArray(Charsets.UTF_8)); output.fd.sync() }
        } catch (error: Throwable) { if (failure == null) failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("reportPath", reportFile.absolutePath)
            putString("sessionId", sessionId)
            putString("jobId", jobId)
            putString("stream", if (failure == null)
                "\nPASS: existing capture generated a ${report.optInt("width")} x ${report.optInt("height")} sphere; GPano, saved GPS and byte-identical originals verified. All session/result files retained.\n"
                else "\nFAIL: $failure\n")
        })
    }

    private fun awaitTerminal(sessionId: String, jobId: String, deadline: Long): StitchJobState {
        var lastProgress = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = StitchRepository.state(targetContext, sessionId) ?: error("Stitching state disappeared")
            check(state.jobId == jobId) { "Another job replaced the validation job's state" }
            if (!state.isActive) return state
            if (state.progress != lastProgress) { lastProgress = state.progress; progress("${state.progress}%: ${state.message}") }
            SystemClock.sleep(250)
        }
        error("Stitching did not finish within five minutes; cancellation will be requested for this job")
    }

    private fun validateGpano(xmp: String, result: PanoramaResult) {
        val attributes = mutableMapOf<String, String>()
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(xmp.reader()) }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) for (index in 0 until parser.attributeCount) {
                if (parser.getAttributeNamespace(index) == GPANO) attributes[parser.getAttributeName(index)] = parser.getAttributeValue(index)
            }
            parser.next()
        }
        check(attributes["ProjectionType"] == "equirectangular" && attributes["UsePanoramaViewer"].equals("true", true)) { "JPEG is missing usable GPano projection metadata" }
        for (key in listOf("FullPanoWidthPixels", "CroppedAreaImageWidthPixels")) check(attributes[key]?.toIntOrNull() == result.width) { "Incorrect GPano width metadata" }
        for (key in listOf("FullPanoHeightPixels", "CroppedAreaImageHeightPixels")) check(attributes[key]?.toIntOrNull() == result.height) { "Incorrect GPano height metadata" }
        check(attributes["CroppedAreaLeftPixels"] == "0" && attributes["CroppedAreaTopPixels"] == "0") { "GPano canvas offset must be zero" }
        check(attributes["SourcePhotosCount"]?.toIntOrNull() == result.sourceCount) { "GPano source-photo count is incorrect" }
        check("PoseHeadingDegrees" !in attributes) { "Panorama incorrectly claims an absolute compass heading" }
    }

    private fun jpegDimensions(file: File): Pair<Int, Int> {
        check(StitchRepository.isCompleteJpeg(file)) { "Generated JPEG is missing or incomplete" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        check(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg") { "Generated file is not a readable JPEG" }
        return options.outWidth to options.outHeight
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun onMain(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (caught: Throwable) { error = caught } }
        error?.let { throw it }
    }

    private fun progress(message: String) = sendStatus(1, Bundle().apply { putString("stream", "$message\n") })
    private data class OriginalSnapshot(val targetId: Int, val file: File, val bytes: Long, val sha256: String)

    companion object {
        private const val MAX_GENERATION_MS = 5L * 60L * 1000L
        private const val GPANO = "http://ns.google.com/photos/1.0/panorama/"
    }
}
