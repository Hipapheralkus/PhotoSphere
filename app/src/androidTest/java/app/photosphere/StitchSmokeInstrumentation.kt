package app.photosphere

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Xml
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.abs

/** Explicit androidTest runner. All photographs, poses and GPS in this test are synthetic. */
class StitchSmokeInstrumentation : Instrumentation() {
    private var fixtureArgument: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureArgument = arguments?.getString("fixture")
        start()
    }

    override fun onStart() {
        super.onStart()
        val started = SystemClock.elapsedRealtime()
        val runId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val directory = File(targetContext.cacheDir, "stitch-validation/$runId")
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("syntheticDataOnly", true).put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("androidSdk", Build.VERSION.SDK_INT)
            .put("startedAtMs", System.currentTimeMillis())
        val phases = JSONArray()
        report.put("phases", phases)
        val originalHashes = linkedMapOf<File, String>()
        var activity: Activity? = null
        var session: CaptureSession? = null
        var failure: Throwable? = null
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create stitching validation cache." }
            progress("Checking that malformed native input returns an error without crashing")
            val rejectedOutput = File(directory, "invalid-input.jpg")
            val rejectedPreview = File(directory, "invalid-preview.jpg")
            val rejected = JSONObject(NativeStitcher.stitch("{\"images\":[" + "invalid".repeat(2000),
                rejectedOutput.absolutePath, rejectedPreview.absolutePath, 1024, StitchProgress { _, _ -> true }))
            check(rejected.getString("status") == "error" && !rejectedOutput.exists() && !rejectedPreview.exists())
            report.put("malformedNativeInputHandled", true)
            val sentinel = "Existing output must remain untouched".toByteArray()
            rejectedOutput.writeBytes(sentinel)
            val existing = JSONObject(NativeStitcher.stitch("{}", rejectedOutput.absolutePath,
                rejectedPreview.absolutePath, 1024, StitchProgress { _, _ -> true }))
            check(existing.getString("status") == "error" && rejectedOutput.readBytes().contentEquals(sentinel))
            report.put("existingNativeOutputPreserved", true)
            val archive = File(fixtureArgument ?: error("Pass -e fixture /data/local/tmp/photosphere-stitch-fixture.zip"))
            check(archive.isFile) { "Synthetic fixture ZIP is missing: ${archive.absolutePath}" }
            progress("Extracting the synthetic stitching fixture")
            val staging = File(directory, "fixture").also { check(it.mkdirs()) }
            extractFixture(archive, staging)
            val fixture = JSONObject(File(staging, "fixture.json").readText())
            check(fixture.getInt("schemaVersion") == 1) { "Unknown synthetic fixture schema." }
            val frames = fixture.getJSONArray("frames")
            check(frames.length() == 48) { "Expected the 48-image synthetic fixture." }
            val targets = (0 until frames.length()).map { index ->
                val frame = frames.getJSONObject(index)
                CaptureTarget(index, frame.getDouble("yaw"), frame.getDouble("pitch"))
            }
            val gps = CaptureLocation(10.25, -20.5, 123.0, 1f, System.currentTimeMillis())
            val info = CameraInfo("synthetic-fixture", 1024, 768, 1024, 768, 90,
                fixture.getDouble("hfov"), fixture.getDouble("vfov"), false,
                JSONObject().put("syntheticDataOnly", true).put("description", "Generated 48-image sphere fixture; no real camera or location").toString())
            val store = SessionStore(targetContext)
            val created = store.create(info, targets, CaptureQuality.FAST, gps)
            session = created
            report.put("retainedSyntheticSessionId", created.id).put("retainedSyntheticSessionPath", created.directory.absolutePath)
            writeJson(File(created.directory, "synthetic-test.json"), JSONObject()
                .put("purpose", "Automated stitching validation using generated images")
                .put("runId", runId).put("containsRealPhotos", false).put("gpsIsSynthetic", true)
                .put("testReportPath", File(directory, "report.json").absolutePath))
            progress("Recording 48 mixed-orientation originals without changing their encoded bytes")
            val baseTimestamp = SystemClock.elapsedRealtimeNanos()
            val seenFiles = mutableSetOf<String>()
            for (index in 0 until frames.length()) {
                val frame = frames.getJSONObject(index)
                val name = frame.getString("file")
                check(name.matches(Regex("originals/frame_[0-9]{3}\\.jpg")) && seenFiles.add(name)) { "Unexpected fixture original path." }
                val source = File(staging, name).canonicalFile
                check(source.parentFile == File(staging, "originals").canonicalFile && source.isFile)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                check(bounds.outWidth == frame.getInt("width") && bounds.outHeight == frame.getInt("height")) { "Fixture JPEG dimensions disagree with its record." }
                check(source.length() == frame.getLong("bytes")) { "Fixture JPEG byte count disagrees with its record." }
                val exif = ExifInterface(source)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                check(orientation in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_180))
                val jpegRotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    else -> 0
                }
                val stored = store.allocatePhoto(created, targets[index])
                source.inputStream().use { input -> FileOutputStream(stored).use { output -> input.copyTo(output); output.fd.sync() } }
                val sourceHash = source.inputStream().use(::sha256)
                check(stored.inputStream().use(::sha256) == sourceHash) { "Copying a fixture changed its original bytes." }
                originalHashes[stored] = sourceHash
                val timestamp = baseTimestamp + index * 100_000_000L
                val pose = Pose(wrapDegrees(frame.getDouble("poseYaw")), frame.getDouble("posePitch"),
                    frame.getDouble("roll"), 0.0, timestamp)
                val cameraJson = JSONObject().put("syntheticDataOnly", true)
                    .put("displayRotationDegreesAtCapture", frame.getInt("displayRotation"))
                    .put("sensorTimestampSource", 1)
                    .put("exposurePoseAccepted", true)
                val metadata = CaptureMetadata(timestamp, 10_000_000L, 100, null, null,
                    jpegRotation, bounds.outWidth, bounds.outHeight, stored.length(), 0, cameraJson.toString())
                store.recordCapture(created, targets[index], pose, metadata, stored, gps, listOf(pose), true)
            }
            store.finish(created)
            check(store.listSessions().single { it.id == created.id }.capturedCount == 48)

            progress("Starting ordinary PhotoSphere and generating the synthetic panorama")
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            startJob(created.id, cancelImmediately = false)
            val first = awaitTerminal(created.id, phases, 180_000L)
            check(first.status == StitchStatus.SUCCEEDED) { "Stitching finished as ${first.status}: ${first.message}" }
            val result = StitchRepository.result(targetContext, created.id) ?: error("Successful stitching did not publish a panorama.")
            check(result.width == result.height * 2) { "The panorama canvas is not 2:1." }
            check(result.sourceCount == 48) { "The panorama did not use every synthetic source." }
            check(result.coverageFraction >= .99) { "Synthetic full-sphere coverage was only ${result.coverageFraction}." }
            check(result.visuallyMatchedImages >= 40) { "Only ${result.visuallyMatchedImages} of 48 sources were visually matched." }
            val encodedBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(result.image.absolutePath, encodedBounds)
            check(encodedBounds.outWidth == result.width && encodedBounds.outHeight == result.height)
            report.put("panorama", JSONObject(result.report.readText()))
            report.put("jpegBytes", result.image.length())
            progress("Checking spherical metadata, synthetic GPS and original file integrity")
            verifyMetadata(result, gps)
            verifyOriginals(originalHashes)
            report.put("originalSha256Unchanged", true)
            val firstHash = result.image.inputStream().use(::sha256)
            report.put("panoramaSha256", firstHash)
            result.preview.copyTo(File(directory, "panorama-preview.jpg"), overwrite = false)

            progress("Comparing the reconstructed sphere with the synthetic ground truth")
            val mae = comparePanoramas(result.preview, File(staging, "ground-truth.jpg"), directory)
            report.put("rgbMeanAbsoluteError", mae).put("maeThreshold", 15.0)
                .put("comparisonWidth", 1024).put("comparisonHeight", 512).put("excludedPoleRows", 3)
            check(mae <= 15.0) { "Panorama RGB mean absolute error $mae exceeds 15.0 against the anchored synthetic sphere." }

            progress("Checking that absent GPS removes geographic metadata without recompressing JPEG pixels")
            val withoutGps = File(directory, "metadata-without-gps.jpg")
            result.image.copyTo(withoutGps, overwrite = false)
            val scanBefore = jpegScanHash(withoutGps)
            PanoramaMetadata.write(withoutGps, result.width, result.height, result.sourceCount, result.createdAtMs, null, result.coverageFraction)
            val clean = ExifInterface(withoutGps)
            check(clean.latLong == null && clean.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) == null &&
                clean.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP) == null) { "GPS tags remained when no location was supplied." }
            check(jpegScanHash(withoutGps) == scanBefore) { "Writing panorama metadata changed JPEG image data." }
            report.put("absentGpsVerified", true).put("metadataPreservesEncodedScan", true)

            progress("Cancelling a second generation and checking that the published panorama stays intact")
            startJob(created.id, cancelImmediately = true)
            val cancelled = awaitTerminal(created.id, phases, 180_000L, previousJobId = result.jobId)
            check(cancelled.status == StitchStatus.CANCELLED) { "Immediate cancellation ended as ${cancelled.status}: ${cancelled.message}" }
            val retained = StitchRepository.result(targetContext, created.id) ?: error("Cancelling removed the existing panorama.")
            check(retained.jobId == result.jobId && retained.image.inputStream().use(::sha256) == firstHash) {
                "Cancelling a new generation changed the previous completed panorama."
            }
            verifyOriginals(originalHashes)
            report.put("cancelledJobId", cancelled.jobId).put("cancellationPreservesPreviousResult", true)
                .put("retainedPanoramaJobId", retained.jobId)
            progress("Checking partial coverage and cancellation after native image encoding")
            val input = StitchRepository.prepare(targetContext, created.id)
            val partialInput = JSONObject().put("memoryBudgetBytes", input.plan.memoryBudgetBytes)
                .put("images", JSONArray().apply { input.frames.take(4).forEach { put(it.json()) } }).toString()
            val partialImage = File(directory, "partial.jpg")
            val partialPreview = File(directory, "partial-preview.jpg")
            val partial = JSONObject(NativeStitcher.stitch(partialInput, partialImage.absolutePath,
                partialPreview.absolutePath, 1024, StitchProgress { _, _ -> true }))
            check(partial.getString("status") == "complete" && partial.getDouble("coverageFraction") in .1.. .6)
            val partialBitmap = BitmapFactory.decodeFile(partialImage.absolutePath) ?: error("Partial preview is missing.")
            try {
                val missing = partialBitmap.getPixel(0, partialBitmap.height / 2)
                check(abs((missing shr 16 and 255) - 32) < 4 && abs((missing shr 8 and 255) - 32) < 4 && abs((missing and 255) - 32) < 4) {
                    "Uncaptured directions were not left neutral."
                }
            } finally { partialBitmap.recycle() }
            report.put("partialCoverageVerified", true).put("partialCoverageFraction", partial.getDouble("coverageFraction"))
            val lateImage = File(directory, "late-cancel.jpg")
            val latePreview = File(directory, "late-cancel-preview.jpg")
            var reachedEncoded = false
            val late = JSONObject(NativeStitcher.stitch(partialInput, lateImage.absolutePath, latePreview.absolutePath,
                1024, StitchProgress { percent, _ -> if (percent == 100) { reachedEncoded = true; false } else true }))
            check(reachedEncoded && late.getString("status") == "cancelled" && !lateImage.exists() && !latePreview.exists() &&
                !File(lateImage.path + ".partial.jpg").exists() && !File(latePreview.path + ".partial.jpg").exists())
            check(retained.image.inputStream().use(::sha256) == firstHash)
            verifyOriginals(originalHashes)
            report.put("lateNativeCancellationCleanupVerified", true)
            progress("Opening the completed sphere and checking preview rendering, gestures and rotation")
            verifyViewer(created.id, result, directory, report)
        } catch (error: Throwable) {
            failure = error
            report.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
        } finally {
            session?.let { created ->
                try { StitchRepository.cancel(targetContext, created.id) }
                catch (error: Throwable) { report.put("cancellationCleanupError", error.toString()) }
            }
            try { runOnMainSync { activity?.finish() } }
            catch (error: Throwable) { report.put("activityCleanupError", error.toString()); if (failure == null) failure = error }
        }
        report.put("passed", failure == null).put("durationMs", SystemClock.elapsedRealtime() - started)
            .put("retentionNote", "Only this explicitly marked synthetic test session and its cache artifacts are retained; no user capture was changed.")
        val reportFile = File(directory, "report.json")
        try { writeJson(reportFile, report) }
        catch (error: Throwable) { if (failure == null) failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("reportPath", reportFile.absolutePath)
            putString("retainedSyntheticSessionId", session?.id)
            putLong("durationMs", SystemClock.elapsedRealtime() - started)
            putString("stream", if (failure == null)
                "\nPASS: synthetic stitching, ground-truth comparison, GPano/GPS, immutable originals, and cancellation preservation verified.\nSynthetic session retained: ${session?.id}\nReport: ${reportFile.absolutePath}\n"
            else "\nFAIL: $failure\nSynthetic session retained: ${session?.id}\nReport: ${reportFile.absolutePath}\n")
        })
    }

    private fun extractFixture(archive: File, staging: File) {
        val destinationRoot = staging.canonicalFile
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                check(!entry.isDirectory && (entry.name == "fixture.json" || entry.name == "ground-truth.jpg" ||
                    entry.name.matches(Regex("originals/frame_[0-9]{3}\\.jpg")))) { "Unexpected fixture archive entry: ${entry.name}" }
                check(seen.add(entry.name) && seen.size <= 50) { "Duplicate or excessive fixture entries." }
                val limit = if (entry.name == "fixture.json") 256L * 1024 else 16L * 1024 * 1024
                check(entry.size in 1..limit) { "Invalid fixture entry size." }
                val destination = File(destinationRoot, entry.name).canonicalFile
                check(destination.toPath().startsWith(destinationRoot.toPath()) && destination != destinationRoot) { "Unsafe fixture extraction path." }
                check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
                var copied = 0L
                zip.getInputStream(entry).use { input -> FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count; total += count
                        check(copied <= limit && total <= 128L * 1024 * 1024) { "Fixture extraction exceeded its size limit." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                check(copied == entry.size) { "Fixture entry ended early." }
            }
        }
        check(seen.size == 50 && "fixture.json" in seen && "ground-truth.jpg" in seen) { "The synthetic fixture is incomplete." }
    }

    private fun startJob(sessionId: String, cancelImmediately: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            var accepted = false
            runOnMainSync {
                accepted = StitchRepository.start(targetContext, sessionId, StitchQuality.QUICK)
                if (accepted && cancelImmediately) check(StitchRepository.cancel(targetContext, sessionId)) { "New stitching job could not be cancelled." }
            }
            if (accepted) return
            check(SystemClock.elapsedRealtime() < deadline) { "Another stitching job prevented the synthetic test from starting." }
            SystemClock.sleep(100L)
        }
    }

    private fun awaitTerminal(sessionId: String, phases: JSONArray, timeoutMs: Long, previousJobId: String? = null): StitchJobState {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previousPhase = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = StitchRepository.state(targetContext, sessionId)
            if (state != null && state.jobId != previousJobId) {
                val phase = "${state.status}:${state.message}:${state.progress / 10}"
                if (phase != previousPhase) {
                    previousPhase = phase
                    progress("${state.progress}% · ${state.status} · ${state.message}")
                    phases.put(JSONObject().put("jobId", state.jobId).put("status", state.status.name)
                        .put("progress", state.progress).put("message", state.message).put("atElapsedMs", SystemClock.elapsedRealtime()))
                }
                if (!state.isActive) return state
            }
            SystemClock.sleep(250L)
        }
        error("Stitching did not reach a terminal state within ${timeoutMs / 1000} seconds.")
    }

    private fun verifyMetadata(result: PanoramaResult, expectedGps: CaptureLocation) {
        val exif = ExifInterface(result.image)
        val position = exif.latLong ?: error("The panorama is missing synthetic GPS metadata.")
        check(abs(position[0] - expectedGps.latitude) < .000001 && abs(position[1] - expectedGps.longitude) < .000001)
        check(abs(exif.getAltitude(Double.NaN) - expectedGps.altitudeMeters!!) < .1)
        check(!exif.getAttribute(ExifInterface.TAG_SOFTWARE).isNullOrBlank())
        val xmp = exif.getAttribute(ExifInterface.TAG_XMP) ?: error("The panorama is missing XMP metadata.")
        val attributes = mutableMapOf<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xmp))
        }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                for (index in 0 until parser.attributeCount) {
                    if (parser.getAttributeNamespace(index) == "http://ns.google.com/photos/1.0/panorama/")
                        attributes[parser.getAttributeName(index)] = parser.getAttributeValue(index)
                }
            }
            parser.next()
        }
        check(attributes["ProjectionType"] == "equirectangular" && attributes["UsePanoramaViewer"] == "True")
        check(attributes["FullPanoWidthPixels"]?.toInt() == result.width && attributes["FullPanoHeightPixels"]?.toInt() == result.height)
        check(attributes["CroppedAreaImageWidthPixels"]?.toInt() == result.width && attributes["CroppedAreaImageHeightPixels"]?.toInt() == result.height)
        check(attributes["CroppedAreaLeftPixels"] == "0" && attributes["CroppedAreaTopPixels"] == "0")
        check(attributes["SourcePhotosCount"]?.toInt() == 48)
        check("PoseHeadingDegrees" !in attributes && "InitialViewHeadingDegrees" !in attributes) { "Unknown absolute heading was invented." }
    }

    private fun comparePanoramas(output: File, reference: File, directory: File): Double {
        val actual = comparisonBitmap(output)
        val expected = comparisonBitmap(reference)
        try {
            val rowActual = IntArray(1024)
            val rowExpected = IntArray(1024)
            var error = 0L
            var channels = 0L
            for (row in 3 until 509) {
                actual.getPixels(rowActual, 0, 1024, 0, row, 1024, 1)
                expected.getPixels(rowExpected, 0, 1024, 0, row, 1024, 1)
                for (column in 0 until 1024) {
                    val a = rowActual[column]; val b = rowExpected[column]
                    error += abs((a ushr 16 and 255) - (b ushr 16 and 255))
                    error += abs((a ushr 8 and 255) - (b ushr 8 and 255))
                    error += abs((a and 255) - (b and 255))
                    channels += 3
                }
            }
            FileOutputStream(File(directory, "comparison-output.jpg")).use { check(actual.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            FileOutputStream(File(directory, "comparison-reference.jpg")).use { check(expected.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            return error.toDouble() / channels
        } finally { actual.recycle(); expected.recycle() }
    }

    private fun comparisonBitmap(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight * 2)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Could not decode comparison image.")
        if (decoded.width == 1024 && decoded.height == 512) return decoded
        return Bitmap.createScaledBitmap(decoded, 1024, 512, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** Uses the ordinary activity and touch events; no debug endpoint or camera is involved. */
    private fun verifyViewer(sessionId: String, result: PanoramaResult, directory: File, report: JSONObject) {
        val activity = startActivitySync(Intent(targetContext, PanoramaViewerActivity::class.java)
            .putExtra(PanoramaViewerActivity.SESSION_ID, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var sphere: SphereViewerView
            runOnMainSync {
                sphere = descendants(activity.window.decorView).filterIsInstance<SphereViewerView>().single()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            awaitUi("The panorama preview did not become ready in portrait") {
                !activity.isDestroyed && sphere.height > sphere.width && sphere.width > 0 &&
                    descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString().startsWith("Preview ") && it.text.contains("Export ${result.width} × ${result.height}")
                    }
            }
            var previewLabel = ""
            runOnMainSync {
                previewLabel = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString().startsWith("Preview ") }.text.toString()
            }
            report.put("viewerPreviewLabel", previewLabel)
            val displayedPreviewWidth = Regex("Preview ([0-9]+) × ([0-9]+)").find(previewLabel)?.groupValues?.get(1)?.toInt()
                ?: error("The preview resolution label is missing.")
            check(displayedPreviewWidth in 2..4096) {
                "The interactive viewer did not use a bounded preview texture."
            }
            saveViewerScreenshot(sphere, File(directory, "viewer-portrait.png"))

            val center = viewCenter(sphere)
            val distance = sphere.width * .18f
            drag(center[0] + distance, center[1], center[0] - distance, center[1])
            check(abs(sphere.yawDegrees) > 3f) { "Dragging did not change the panorama heading." }
            val beforePinch = sphere.verticalFovDegrees
            pinch(center[0], center[1], minOf(sphere.width, sphere.height) * .08f, minOf(sphere.width, sphere.height) * .30f)
            report.put("viewerFovBeforePinch", beforePinch).put("viewerFovAfterPinch", sphere.verticalFovDegrees)
            check(sphere.verticalFovDegrees < beforePinch - 2f) { "Pinching out did not zoom into the panorama." }
            val yaw = sphere.yawDegrees
            val pitch = sphere.pitchDegrees
            val fov = sphere.verticalFovDegrees
            runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitUi("The panorama viewer did not adapt to landscape") {
                !activity.isDestroyed && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && sphere.width > sphere.height
            }
            check(abs(sphere.yawDegrees - yaw) < .01f && abs(sphere.pitchDegrees - pitch) < .01f && abs(sphere.verticalFovDegrees - fov) < .01f) {
                "Rotation unexpectedly reset the viewing direction or zoom."
            }
            saveViewerScreenshot(sphere, File(directory, "viewer-landscape.png"))
            runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Reset view" }.performClick()
            }
            check(abs(sphere.yawDegrees) < .01f && abs(sphere.pitchDegrees) < .01f && abs(sphere.verticalFovDegrees - 70f) < .01f) {
                "The Reset view control did not restore the starting direction and zoom."
            }
            report.put("viewerGesturesVerified", true).put("viewerRotationPreservesDirection", true)
                .put("viewerResetVerified", true).put("viewerRenderedPreviewVerified", true)
        } finally { runOnMainSync { activity.finish() } }
    }

    private fun awaitUi(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            runOnMainSync { ready = predicate() }
            if (ready) { waitForIdleSync(); return }
            SystemClock.sleep(100L)
        }
        error(message)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun viewCenter(view: View): FloatArray {
        val location = IntArray(2)
        val center = FloatArray(2)
        runOnMainSync { view.getLocationOnScreen(location); center[0] = location[0] + view.width * .5f; center[1] = location[1] + view.height * .5f }
        return center
    }

    private fun drag(startX: Float, startY: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startX, startY)
        for (step in 1..12) {
            SystemClock.sleep(16L)
            send(MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 12f, startY + (endY - startY) * step / 12f)
        }
        send(MotionEvent.ACTION_UP, endX, endY)
        waitForIdleSync()
    }

    private fun pinch(centerX: Float, centerY: Float, startRadius: Float, endRadius: Float) {
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(2) { MotionEvent.PointerCoords().apply { y = centerY; pressure = 1f; size = 1f } }
        fun send(action: Int, count: Int, radius: Float) {
            coordinates[0].x = centerX - radius; coordinates[1].x = centerX + radius
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1, startRadius)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, startRadius)
        for (step in 1..12) {
            SystemClock.sleep(16L)
            send(MotionEvent.ACTION_MOVE, 2, startRadius + (endRadius - startRadius) * step / 12f)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, endRadius)
        send(MotionEvent.ACTION_UP, 1, endRadius)
        waitForIdleSync()
    }

    private fun saveViewerScreenshot(sphere: SphereViewerView, file: File) {
        // Surface composition follows the UI resize/texture callback on its own GL thread.
        SystemClock.sleep(750L)
        val center = viewCenter(sphere)
        val screenshot = uiAutomation.takeScreenshot() ?: error("Could not capture the panorama viewer for validation.")
        try {
            var sceneSamples = 0
            var samples = 0
            // A small centre-only patch can legitimately hit a dark label in the fixture.
            // Sample the scene broadly while staying clear of header/footer controls.
            val colors = mutableSetOf<Int>()
            for (row in -5..5) for (column in -5..5) {
                val x = (center[0] + column * sphere.width * .035f).toInt().coerceIn(0, screenshot.width - 1)
                val y = (center[1] + row * sphere.height * .035f).toInt().coerceIn(0, screenshot.height - 1)
                val pixel = screenshot.getPixel(x, y)
                colors += pixel and 0x00F0F0F0
                val distanceFromEmpty = abs((pixel ushr 16 and 255) - 12) + abs((pixel ushr 8 and 255) - 24) + abs((pixel and 255) - 21)
                if (distanceFromEmpty > 35) sceneSamples++
                samples++
            }
            FileOutputStream(file).use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(sceneSamples > samples * .7 && colors.size > 20) { "The viewer remained blank instead of displaying the synthetic panorama." }
        } finally { screenshot.recycle() }
    }

    private fun verifyOriginals(originals: Map<File, String>) {
        originals.forEach { (file, hash) -> check(file.isFile && file.inputStream().use(::sha256) == hash) { "Stitching changed original ${file.name}." } }
    }

    /** The scan bytes must remain identical when only EXIF/XMP segments are changed. */
    private fun jpegScanHash(file: File): String = RandomAccessFile(file, "r").use { input ->
        check(input.readUnsignedShort() == 0xffd8) { "Not a JPEG." }
        while (input.filePointer < input.length()) {
            check(input.readUnsignedByte() == 0xff) { "Malformed JPEG marker." }
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda) {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                return@use hex(digest.digest())
            }
            check(marker != 0xd9) { "JPEG has no image scan." }
            val length = input.readUnsignedShort()
            check(length >= 2 && input.filePointer + length - 2 <= input.length()) { "Malformed JPEG segment." }
            input.seek(input.filePointer + length - 2)
        }
        error("JPEG has no image scan.")
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 255) }
    private fun writeJson(file: File, json: JSONObject) = FileOutputStream(file).use { output -> output.write(json.toString(2).toByteArray()); output.fd.sync() }
    private fun progress(message: String) = sendStatus(1, Bundle().apply { putString("stream", "$message\n") })
}
