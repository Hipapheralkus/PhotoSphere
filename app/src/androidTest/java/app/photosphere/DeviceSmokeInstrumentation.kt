package app.photosphere

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Explicitly invoked androidTest runner. This code is absent from the normal application APK. */
class DeviceSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val started = SystemClock.elapsedRealtime()
        val runId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val validationRoot = File(targetContext.cacheDir, "device-validation")
        val report = JSONObject()
            .put("schemaVersion", 1).put("runId", runId)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidSdk", Build.VERSION.SDK_INT).put("startedAtMs", System.currentTimeMillis())
        val modeReports = JSONArray()
        report.put("modes", modeReports)
        val testSessions = mutableListOf<CaptureSession>()
        var activity: Activity? = null
        var engine: CameraEngine? = null
        var savedCount = 0
        var failure: Throwable? = null
        try {
            check(validationRoot.isDirectory || validationRoot.mkdirs()) { "Cannot create test cache" }
            check(targetContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Grant the target application CAMERA permission before running device validation"
            }
            progress("Launching the camera validation activity")
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            lateinit var preview: TextureView
            runOnMainSync {
                host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                preview = TextureView(host)
                host.setContentView(preview, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            waitForIdleSync()
            val camera = CameraEngine(targetContext)
            engine = camera
            val store = SessionStore(targetContext)
            for (quality in listOf(CaptureQuality.FAST, CaptureQuality.DETAIL)) {
                val modeReport = JSONObject().put("quality", quality.name)
                modeReports.put(modeReport)
                val modeDirectory = File(validationRoot, quality.name.lowercase())
                check(modeDirectory.isDirectory || modeDirectory.mkdirs()) { "Cannot create mode test cache" }
                progress("Opening ${quality.name} camera mode")
                val openStart = SystemClock.elapsedRealtime()
                val info = openCamera(camera, preview, quality)
                modeReport.put("openDurationMs", SystemClock.elapsedRealtime() - openStart)
                    .put("cameraId", info.cameraId)
                    .put("configuredJpegWidth", info.jpegWidth).put("configuredJpegHeight", info.jpegHeight)
                    .put("megapixels", info.megapixels)
                    .put("maxResolutionMode", info.maxResolutionMode)
                    .put("horizontalFov", info.horizontalFov).put("verticalFov", info.verticalFov)
                    .put("diagnostics", JSONObject(info.diagnosticsJson))
                check(info.jpegWidth > 0 && info.jpegHeight > 0) { "Invalid configured JPEG dimensions" }
                if (quality == CaptureQuality.FAST && Build.MODEL.startsWith("Pixel", ignoreCase = true)) {
                    check(info.megapixels >= 8.0) { "Pixel FAST mode exposed less than 8 MP: ${info.megapixels}" }
                }
                // The first shot exercises startup AE/AF; the second exercises repeated capture.
                SystemClock.sleep(1200L)
                val captures = mutableListOf<Pair<File, CaptureMetadata>>()
                val shotReports = JSONArray()
                modeReport.put("captures", shotReports)
                repeat(2) { shotIndex ->
                    progress("Capturing ${quality.name} original ${shotIndex + 1}/2")
                    val destination = File(modeDirectory, "${runId}_${shotIndex + 1}.jpg")
                    check(!destination.exists()) { "Test destination already exists" }
                    val captureStart = SystemClock.elapsedRealtime()
                    val metadata = capture(camera, destination)
                    val elapsed = SystemClock.elapsedRealtime() - captureStart
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destination.absolutePath, options)
                    check(options.outWidth == metadata.width && options.outHeight == metadata.height) {
                        "JPEG header ${options.outWidth}x${options.outHeight} disagrees with metadata ${metadata.width}x${metadata.height}"
                    }
                    check(options.outMimeType == "image/jpeg") { "Capture is not a JPEG" }
                    check(metadata.sensorTimestampNs > 0L) { "Missing capture sensor timestamp" }
                    check(metadata.bytes == destination.length() && metadata.bytes > 1000L) { "Incomplete saved JPEG" }
                    check(metadata.width.toLong() * metadata.height == info.jpegWidth.toLong() * info.jpegHeight) {
                        "Saved JPEG pixel count differs from negotiated capture resolution"
                    }
                    val sha256 = destination.inputStream().use(::sha256)
                    shotReports.put(JSONObject()
                        .put("path", destination.absolutePath).put("bytes", destination.length())
                        .put("width", options.outWidth).put("height", options.outHeight)
                        .put("sha256", sha256).put("wallDurationMs", elapsed)
                        .put("engineDurationMs", metadata.durationMs)
                        .put("sensorTimestampNs", metadata.sensorTimestampNs)
                        .put("jpegOrientation", metadata.jpegOrientation)
                        .put("exposureTimeNs", metadata.exposureTimeNs ?: JSONObject.NULL)
                        .put("sensitivityIso", metadata.sensitivityIso ?: JSONObject.NULL)
                        .put("cameraMetadata", JSONObject(metadata.cameraMetadataJson)))
                    captures += destination to metadata
                    savedCount++
                    SystemClock.sleep(300L)
                }
                check(captures[1].second.sensorTimestampNs > captures[0].second.sensorTimestampNs) {
                    "Successive photographs do not have distinct increasing timestamps"
                }
                progress("Checking ${quality.name} durable session and exported original bytes")
                val targets = listOf(CaptureTarget(0, 0.0, 0.0), CaptureTarget(1, 30.0, 0.0))
                val session = store.create(info, targets, quality)
                testSessions += session
                val storedFiles = mutableListOf<File>()
                captures.forEachIndexed { index, (source, metadata) ->
                    val stored = store.allocatePhoto(session, targets[index])
                    source.inputStream().use { input ->
                        FileOutputStream(stored).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
                    }
                    // Synthetic poses exercise serialization only. These temporary sessions are deleted below.
                    val testPose = Pose(targets[index].yaw, 0.0, 0.0, 0.0, metadata.sensorTimestampNs, floatArrayOf(0f, 0f, 0f, 1f))
                    store.recordCapture(session, targets[index], testPose, metadata, stored)
                    storedFiles += stored
                }
                store.finish(session)
                val summary = store.listSessions().single { it.id == session.id }
                check(summary.capturedCount == 2 && summary.targetCount == 2 && summary.status == "complete") {
                    "Session recovery returned incorrect counts/status"
                }
                val archive = File(modeDirectory, "${runId}_session.zip")
                FileOutputStream(archive).use { output -> store.exportZip(summary, output); output.fd.sync() }
                ZipFile(archive).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json") ?: error("Archive has no manifest")
                    val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
                    check(manifest.getInt("schemaVersion") == 1 && manifest.getInt("capturedCount") == 2)
                    check(manifest.getJSONObject("camera").getString("cameraId") == info.cameraId)
                    check(manifest.has("software") && manifest.getJSONObject("camera").has("diagnostics"))
                    val records = manifest.getJSONArray("captures")
                    check(records.length() == 2)
                    captures.forEachIndexed { index, (original, metadata) ->
                        val record = records.getJSONObject(index)
                        check(record.getJSONObject("capture").getLong("sensorTimestampNs") == metadata.sensorTimestampNs)
                        check(record.getJSONObject("capture").getLong("bytes") == original.length())
                        val originalEntry = zip.getEntry(record.getString("file")) ?: error("Archive original missing")
                        val exportedHash = zip.getInputStream(originalEntry).use(::sha256)
                        check(exportedHash == original.inputStream().use(::sha256)) { "Archive changed the original JPEG bytes" }
                        check(storedFiles[index].inputStream().use(::sha256) == exportedHash) { "Session original changed during export" }
                    }
                    val recovery = JSONObject(zip.getInputStream(zip.getEntry("recovery.json") ?: error("Archive has no recovery report"))
                        .bufferedReader().use { it.readText() })
                    check(recovery.getJSONArray("uncommittedOriginals").length() == 0)
                    check(recovery.getJSONArray("missingOrIncompleteRecordedOriginals").length() == 0)
                }
                modeReport.put("sessionExportVerified", true).put("archivePath", archive.absolutePath)
                    .put("archiveBytes", archive.length())
                runOnMainSync { camera.close() }
                SystemClock.sleep(800L)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
        } finally {
            try { runOnMainSync { engine?.close(); activity?.finish() } }
            catch (error: Throwable) { if (failure == null) failure = error; report.put("activityCleanupError", error.toString()) }
            val cleanupErrors = JSONArray()
            testSessions.forEach { session ->
                try { removeTestSession(session) }
                catch (error: Throwable) { cleanupErrors.put("${session.id}: $error"); if (failure == null) failure = error }
            }
            report.put("sessionCleanupErrors", cleanupErrors)
        }
        report.put("passed", failure == null).put("captureCount", savedCount)
            .put("totalDurationMs", SystemClock.elapsedRealtime() - started)
        val reportFile = File(validationRoot, "report.json")
        try {
            FileOutputStream(reportFile).use { output -> output.write(report.toString(2).toByteArray()); output.fd.sync() }
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
        val result = Bundle().apply {
            putString("reportPath", reportFile.absolutePath)
            putInt("captureCount", savedCount)
            putLong("durationMs", SystemClock.elapsedRealtime() - started)
            val captureSummary = JSONArray()
            for (modeIndex in 0 until modeReports.length()) {
                val mode = modeReports.getJSONObject(modeIndex)
                val shots = mode.optJSONArray("captures") ?: JSONArray()
                for (shotIndex in 0 until shots.length()) {
                    val shot = shots.getJSONObject(shotIndex)
                    captureSummary.put(JSONObject().put("quality", mode.getString("quality"))
                        .put("width", shot.getInt("width")).put("height", shot.getInt("height"))
                        .put("bytes", shot.getLong("bytes")).put("durationMs", shot.getLong("wallDurationMs")))
                }
            }
            putString("captures", captureSummary.toString())
            putString("stream", if (failure == null) "\nPASS: both modes captured twice; JPEG dimensions, timestamps, session recovery, and byte-exact ZIP export verified.\n"
                else "\nFAIL: ${failure.toString()}\n")
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun openCamera(engine: CameraEngine, view: TextureView, quality: CaptureQuality): CameraInfo {
        val latch = CountDownLatch(1)
        val result = AtomicReference<CameraInfo?>()
        val errorMessage = AtomicReference<String?>()
        runOnMainSync {
            engine.open(view, quality, { result.set(it); latch.countDown() }, { errorMessage.set(it); latch.countDown() })
        }
        check(latch.await(45, TimeUnit.SECONDS)) { "Timed out opening ${quality.name} camera" }
        check(errorMessage.get() == null) { errorMessage.get() ?: "Camera open failed" }
        return result.get() ?: error("Camera returned no configuration")
    }

    private fun capture(engine: CameraEngine, file: File): CaptureMetadata {
        val latch = CountDownLatch(1)
        val result = AtomicReference<CaptureMetadata?>()
        val errorMessage = AtomicReference<String?>()
        runOnMainSync {
            engine.capture(file, null, { result.set(it); latch.countDown() }, { errorMessage.set(it); latch.countDown() })
        }
        check(latch.await(45, TimeUnit.SECONDS)) { "Timed out saving original JPEG" }
        check(errorMessage.get() == null) { errorMessage.get() ?: "Camera capture failed" }
        return result.get() ?: error("Camera returned no capture metadata")
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Delete only the two exact session directories created by this run; never traverse arbitrary trees. */
    private fun removeTestSession(session: CaptureSession) {
        val root = File(targetContext.filesDir, "sessions").canonicalFile
        val directory = session.directory.canonicalFile
        check(directory.parentFile == root && directory.name == session.id) { "Unsafe test session cleanup path" }
        val originals = File(directory, "originals")
        check(originals.canonicalFile.parentFile == directory) { "Unsafe test original directory" }
        originals.listFiles().orEmpty().forEach { file ->
            check(file.isFile && file.canonicalFile.parentFile == originals.canonicalFile) { "Unexpected test original path" }
            check(file.delete()) { "Could not remove test original" }
        }
        check(!originals.exists() || originals.delete()) { "Could not remove test originals directory" }
        listOf("manifest.json", "manifest.json.bak", "manifest.json.new").forEach { name ->
            val file = File(directory, name)
            check(file.canonicalFile.parentFile == directory) { "Unsafe test manifest path" }
            check(!file.exists() || file.delete()) { "Could not remove test manifest" }
        }
        check(directory.delete()) { "Unexpected contents remain in test session directory" }
    }

    private fun progress(message: String) = sendStatus(1, Bundle().apply { putString("stream", "$message\n") })
}
