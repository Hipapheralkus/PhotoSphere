package app.photosphere

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-process job registry. Starting, cancellation and observation are UI-safe. plan,
 * state and result read local files and must run on an IO thread. Observers run on main.
 */
object StitchRepository {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val journal = Executors.newSingleThreadExecutor { task -> Thread(task, "sphere-stitch-journal") }
    private val states = mutableMapOf<String, StitchJobState?>()
    private val observers = CopyOnWriteArrayList<Subscription>()
    private var active: StitchWork? = null

    fun start(context: Context, sessionId: String, quality: StitchQuality = StitchQuality.FULL): Boolean {
        requireValidId(sessionId)
        val app = context.applicationContext
        val work = synchronized(lock) {
            if (active != null) return false
            StitchWork(sessionId, UUID.randomUUID().toString(), quality).also { active = it }
        }
        update(app, work, StitchStatus.QUEUED, 0, "Preparing original photos")
        try {
            app.startForegroundService(Intent(app, StitchService::class.java)
                .setAction(StitchService.ACTION_START).putExtra(StitchService.EXTRA_JOB, work.jobId))
        } catch (error: Exception) {
            finish(app, work, StitchStatus.FAILED, "Could not start stitching: ${error.message ?: error.javaClass.simpleName}")
            release(work)
        }
        return true
    }

    fun cancel(context: Context, sessionId: String): Boolean {
        val work = synchronized(lock) { active?.takeIf { it.sessionId == sessionId && !it.terminal.get() } } ?: return false
        if (!work.cancel()) return false
        update(context.applicationContext, work, StitchStatus.CANCELLING, currentProgress(work), "Cancelling stitching")
        return true
    }

    fun observe(context: Context, sessionId: String, listener: (StitchJobState?) -> Unit): AutoCloseable {
        requireValidId(sessionId)
        val subscription = Subscription(sessionId, listener)
        observers.add(subscription)
        val app = context.applicationContext
        journal.execute {
            val initial = runCatching { state(app, sessionId) }.getOrElse { error ->
                StitchJobState(sessionId, "", StitchQuality.FULL, StitchStatus.FAILED, 0,
                    "Cannot read stitching state: ${error.message ?: error.javaClass.simpleName}")
            }
            main.post {
                if (!subscription.closed.get()) {
                    // A queued initial disk read must never replace a newer in-memory update.
                    val latest = synchronized(lock) { if (states.containsKey(sessionId)) states[sessionId] else initial }
                    subscription.listener(latest)
                }
            }
        }
        return AutoCloseable { subscription.closed.set(true); observers.remove(subscription) }
    }

    /** Recover stale process-local running states as interrupted, never as a resumable native job. */
    fun state(context: Context, sessionId: String): StitchJobState? {
        requireValidId(sessionId)
        synchronized(lock) { if (states.containsKey(sessionId)) return states[sessionId] }
        val directory = sessionDirectory(context, sessionId)
        val savedResult = result(context, sessionId)
        val file = File(directory, STATE_FILE)
        val loaded = if (file.exists() || File(file.path + ".bak").exists()) {
            val json = readJson(file)
            require(json.getString("sessionId") == sessionId) { "Stitch journal belongs to a different session" }
            StitchJobState(sessionId, json.getString("jobId"), StitchQuality.valueOf(json.getString("quality")),
                StitchStatus.valueOf(json.getString("status")), json.optInt("progress").coerceIn(0, 100),
                json.optString("message"), savedResult, json.optLong("updatedAtMs"))
        } else savedResult?.let {
            StitchJobState(sessionId, it.jobId, it.quality, StitchStatus.SUCCEEDED, 100, "Photo Sphere ready", it, it.createdAtMs)
        }
        val recovered = StitchRecovery.reconcile(loaded, savedResult, System.currentTimeMillis())
        synchronized(lock) {
            if (states.containsKey(sessionId)) return states[sessionId]
            states[sessionId] = recovered
        }
        if (recovered != null) journal.execute {
            if (synchronized(lock) { states[sessionId] === recovered }) writeState(directory, recovered)
        }
        return recovered
    }

    fun plan(context: Context, sessionId: String): StitchPlan = prepare(context, sessionId).plan

    /** Atomic panorama.json is the only publication point; temporary native JPEGs are never results. */
    fun result(context: Context, sessionId: String): PanoramaResult? {
        val directory = sessionDirectory(context, sessionId)
        val report = File(directory, RESULT_FILE)
        if (!report.exists() && !File(report.path + ".bak").exists()) return null
        val json = readJson(report)
        require(json.getString("schema") == "app.photosphere.panorama" && json.getInt("schemaVersion") == 1) {
            "Unsupported panorama report"
        }
        require(json.getString("sessionId") == sessionId && json.getString("status") == "complete") { "Invalid panorama report" }
        val jobId = json.getString("jobId").also(::requireValidId)
        val resultDirectory = File(directory, "stitch-results/$jobId")
        require(resultDirectory.canonicalFile.parentFile == File(directory, "stitch-results").canonicalFile) { "Invalid panorama path" }
        val image = File(resultDirectory, "panorama.jpg")
        val preview = File(resultDirectory, "panorama-preview.jpg")
        require(image.canonicalFile.parentFile == resultDirectory.canonicalFile &&
            preview.canonicalFile.parentFile == resultDirectory.canonicalFile) { "Invalid panorama image path" }
        require(isCompleteJpeg(image) && isCompleteJpeg(preview)) { "Saved panorama is missing or incomplete; generate it again" }
        val width = json.getInt("width")
        val height = json.getInt("height")
        require(width in 512..20_480 && height == width / 2 && width % 2 == 0) { "Saved panorama dimensions are invalid" }
        val coverage = json.getDouble("coverageFraction")
        require(coverage.isFinite() && coverage in 0.0..1.0) { "Saved panorama coverage is invalid" }
        return PanoramaResult(sessionId, jobId, image, preview, report, width, height, json.getInt("sourceCount"),
            coverage, json.getLong("createdAtMs"), stringList(json.optJSONArray("warnings")),
            json.optInt("visuallyMatchedImages"), json.optInt("visuallyMatchedPairs"),
            StitchQuality.valueOf(json.getString("quality")), json.optLong("estimatedPeakBytes"),
            json.optDouble("pixelCoverageFraction", coverage))
    }

    internal fun currentWork(jobId: String): StitchWork? = synchronized(lock) { active?.takeIf { it.jobId == jobId } }

    internal fun update(context: Context, work: StitchWork, status: StitchStatus, progress: Int, message: String) {
        if (work.terminal.get()) return
        val old = synchronized(lock) { states[work.sessionId] }
        publish(context, StitchJobState(work.sessionId, work.jobId, work.quality,
            if (work.cancelled.get()) StitchStatus.CANCELLING else status,
            progress.coerceIn(0, 100), if (work.cancelled.get()) "Cancelling stitching" else message, old?.result))
    }

    internal fun finish(context: Context, work: StitchWork, status: StitchStatus, message: String, result: PanoramaResult? = null) {
        if (!work.terminal.compareAndSet(false, true)) return
        val old = synchronized(lock) { states[work.sessionId] }
        publish(context, StitchJobState(work.sessionId, work.jobId, work.quality, status,
            if (status == StitchStatus.SUCCEEDED) 100 else old?.progress ?: 0, message, result ?: old?.result))
    }

    internal fun release(work: StitchWork) { synchronized(lock) { if (active === work) active = null } }

    /** A cancellation accepted before this lock is acquired can never publish its output. */
    internal fun commit(work: StitchWork, publishFiles: () -> Unit): Boolean = work.commit(publishFiles)

    internal fun interrupt(context: Context, work: StitchWork, message: String) {
        if (!work.interrupt(message)) return
        finish(context, work, StitchStatus.INTERRUPTED, message)
    }

    internal fun attachPreviousResult(context: Context, work: StitchWork) {
        val previous = runCatching { result(context, work.sessionId) }.getOrNull() ?: return
        val old = synchronized(lock) { states[work.sessionId] } ?: return
        if (old.jobId == work.jobId && !work.terminal.get()) publish(context, old.copy(result = previous))
    }

    private fun currentProgress(work: StitchWork): Int = synchronized(lock) { states[work.sessionId]?.progress ?: 0 }

    private fun publish(context: Context, state: StitchJobState) {
        synchronized(lock) {
            val current = states[state.sessionId]
            if (state.isActive && current?.jobId == state.jobId && !current.isActive) return
            if (state.isActive && active?.let { it.jobId == state.jobId && it.terminal.get() } == true) return
            states[state.sessionId] = state
        }
        journal.execute {
            try {
                if (synchronized(lock) { states[state.sessionId] === state }) {
                    writeState(sessionDirectory(context, state.sessionId), state)
                }
            }
            catch (error: Exception) { Log.w("PhotoSphere", "Could not persist stitching progress", error) }
        }
        main.post {
            val latest = synchronized(lock) { states[state.sessionId] }
            observers.forEach { if (!it.closed.get() && it.sessionId == state.sessionId) it.listener(latest) }
        }
    }

    private fun writeState(directory: File, state: StitchJobState) = writeJson(File(directory, STATE_FILE), JSONObject()
        .put("schemaVersion", 1).put("sessionId", state.sessionId).put("jobId", state.jobId)
        .put("quality", state.quality.name).put("status", state.status.name).put("progress", state.progress)
        .put("message", state.message).put("updatedAtMs", state.updatedAtMs))

    internal fun prepare(context: Context, sessionId: String): StitchInput {
        val directory = sessionDirectory(context, sessionId)
        // SessionStore owns this AtomicFile and may still append a capture on its IO thread.
        // Opening the committed base directly cannot delete its writer's .new transaction file.
        val manifestFile = File(directory, "manifest.json")
        val backup = File(manifestFile.path + ".bak")
        val committedInput = (if (backup.exists()) runCatching { FileInputStream(backup) }.getOrNull() else null)
            ?: FileInputStream(manifestFile)
        val manifest = committedInput.use(::readJsonStream)
        require(manifest.getInt("schemaVersion") == 1 && manifest.getString("id") == sessionId) { "Unsupported capture session" }
        val camera = manifest.optJSONObject("camera") ?: error("Session is missing camera field-of-view metadata")
        val portraitH = camera.getDouble("horizontalFovDegrees")
        val portraitV = camera.getDouble("verticalFovDegrees")
        require(portraitH.isFinite() && portraitV.isFinite() && portraitH in 15.0..155.0 && portraitV in 15.0..155.0) {
            "Session camera field of view is outside the stitcher's supported range"
        }
        val targets = manifest.optJSONArray("targets") ?: error("Session is missing its capture targets")
        val targetIds = (0 until targets.length()).map { targets.getJSONObject(it).getInt("id") }.toSet()
        require(targetIds.size == targets.length()) { "Session has duplicate capture targets" }
        val captures = manifest.optJSONArray("captures") ?: error("Session has no recorded photos")
        val selection = (0 until captures.length()).map { index ->
            val record = captures.getJSONObject(index)
            require(!record.has("accepted") || record.get("accepted") is Boolean) { "Photo record has an invalid acceptance flag" }
            val accepted = record.optBoolean("accepted", true)
            StitchRecordSelection(if (accepted) record.getInt("targetId") else -1, accepted)
        }
        val latest = StitchInputRules.latestAcceptedIndices(selection, targetIds)
        require(latest.size >= 2) { "Capture at least two overlapping accepted photos before stitching" }
        require(latest.size <= 400) { "This stitcher supports at most 400 accepted original photos per sphere" }
        val warnings = mutableListOf<String>()
        val frames = mutableListOf<StitchFrame>()
        var legacyOrientation = false
        var firstPhotoLocation: CaptureLocation? = null
        for ((targetId, index) in latest.entries.sortedBy { it.value }) {
            val record = captures.getJSONObject(index)
            val relative = record.getString("file")
            val original = File(directory, relative).canonicalFile
            require(original.parentFile == File(directory, "originals").canonicalFile && original.extension.equals("jpg", true)) {
                "Photo $targetId has an invalid original path"
            }
            require(frames.none { it.file == original }) { "The same original is recorded for more than one capture target" }
            require(isCompleteJpeg(original)) { "Original for target $targetId is missing or incomplete" }
            val capture = record.optJSONObject("capture") ?: error("Photo $targetId is missing capture metadata")
            require(capture.getLong("bytes") == original.length()) { "Original for target $targetId no longer matches its recorded size" }
            val pose = record.optJSONObject("pose") ?: error("Photo $targetId is missing its recorded orientation")
            val yaw = pose.getDouble("yawDegrees")
            val pitch = pose.getDouble("pitchDegrees")
            val roll = pose.getDouble("rollDegrees")
            require(yaw.isFinite() && pitch.isFinite() && roll.isFinite() && pitch in -90.0..90.0 &&
                kotlin.math.abs(yaw) <= 360.0 && kotlin.math.abs(roll) <= 360.0) { "Photo $targetId has invalid orientation data" }
            val metadata = capture.optJSONObject("cameraMetadata")
            if (metadata == null || !metadata.has("displayRotationDegreesAtCapture")) legacyOrientation = true
            val rotationValue = if (metadata?.has("displayRotationDegreesAtCapture") == true) metadata.get("displayRotationDegreesAtCapture") else 0
            require(rotationValue is Number && rotationValue.toDouble() == rotationValue.toInt().toDouble()) { "Photo $targetId has invalid display rotation" }
            val rotation = rotationValue.toInt()
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) { "Photo $targetId has invalid display rotation" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(original.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg") { "Cannot read JPEG dimensions for target $targetId" }
            val exif = ExifInterface(original.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            require(orientation in 0..8) { "Photo $targetId has invalid JPEG orientation" }
            val swapped = orientation in 5..8
            val width = if (swapped) options.outHeight else options.outWidth
            val height = if (swapped) options.outWidth else options.outHeight
            val landscape = rotation % 180 != 0
            // Pose.roll already describes the display-rotated viewfinder, exactly like imread's
            // EXIF-normalized pixels. Applying display rotation to roll again would double it.
            frames.add(StitchFrame(targetId, original, wrapDegrees(yaw), pitch, wrapDegrees(roll),
                if (landscape) portraitV else portraitH, if (landscape) portraitH else portraitV, width, height))
            if (firstPhotoLocation == null) firstPhotoLocation = location(record.optJSONObject("location"))
        }
        if (legacyOrientation) warnings.add("Older captures without display rotation are interpreted as portrait.")
        val complete = targetIds.isNotEmpty() && latest.keys.containsAll(targetIds)
        if (!complete) warnings.add("Partial capture: ${frames.size} of ${targetIds.size} planned directions. Uncovered areas remain visible.")
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        require(!memory.lowMemory && memory.availMem > 0) { "Available memory is low; close other apps before stitching" }
        val budget = minOf(StitchSizing.MAX_BUDGET, memory.availMem / 4L)
        val sizes = StitchSizing.choose(frames.map { StitchSampling(it.width, it.height, it.hfov, it.vfov) }, budget)
        if (sizes.full < minOf(sizes.sourceWidth, 20_480)) {
            warnings.add("Output is limited to ${sizes.full} × ${sizes.full / 2} by the current memory budget.")
        } else if (sizes.sourceWidth > 20_480) warnings.add("Output is limited to 20480 × 10240; higher-resolution originals are preserved.")
        val plan = StitchPlan(sessionId, frames.size, targetIds.size, complete, sizes.full, sizes.quick, budget,
            StitchSizing.estimatedPeak(sizes.full, sizes.largestSourceBytes), StitchSizing.estimatedPeak(sizes.quick, sizes.largestSourceBytes),
            StitchSizing.estimatedOutput(sizes.full), StitchSizing.estimatedOutput(sizes.quick),
            StitchSizing.requiredFree(sizes.full), StitchSizing.requiredFree(sizes.quick), warnings.toList())
        return StitchInput(directory, manifest.getLong("createdAtMs"), frames, plan,
            location(manifest.optJSONObject("location")) ?: firstPhotoLocation)
    }

    internal fun sessionDirectory(context: Context, sessionId: String): File {
        requireValidId(sessionId)
        val root = File(context.filesDir, "sessions").canonicalFile
        val directory = File(root, sessionId).canonicalFile
        require(directory.parentFile == root && directory.isDirectory) { "Capture session is missing" }
        return directory
    }

    @Synchronized
    internal fun writeJson(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    @Synchronized
    internal fun readJson(file: File): JSONObject {
        return AtomicFile(file).openRead().use(::readJsonStream)
    }

    private fun readJsonStream(input: InputStream): JSONObject {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 16 * 1024 * 1024) { "Session metadata exceeds the supported size" }
            output.write(buffer, 0, count)
        }
        return JSONObject(output.toString(Charsets.UTF_8.name()))
    }

    internal fun isCompleteJpeg(file: File): Boolean = try {
        file.isFile && file.length() >= 4 && RandomAccessFile(file, "r").use {
            val first = it.readUnsignedShort(); it.seek(it.length() - 2)
            first == 0xFFD8 && it.readUnsignedShort() == 0xFFD9
        }
    } catch (_: Exception) { false }

    internal fun stringList(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).map { array.getString(it) }

    private fun location(json: JSONObject?): CaptureLocation? {
        if (json == null) return null
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val altitude = json.optDouble("altitudeMeters", Double.NaN).takeIf { it.isFinite() }
        val accuracy = json.optDouble("accuracyMeters", Double.NaN).takeIf { it.isFinite() && it >= 0 }?.toFloat()
        return CaptureLocation(latitude, longitude, altitude, accuracy, json.optLong("timeMs", 0L))
    }

    private fun requireValidId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid capture session identifier" }
    }

    private data class Subscription(val sessionId: String, val listener: (StitchJobState?) -> Unit, val closed: AtomicBoolean = AtomicBoolean())
    internal const val RESULT_FILE = "panorama.json"
    private const val STATE_FILE = "stitch-state.json"
}

internal data class StitchFrame(
    val targetId: Int, val file: File, val yaw: Double, val pitch: Double, val roll: Double,
    val hfov: Double, val vfov: Double, val width: Int, val height: Int,
) {
    fun json(): JSONObject = JSONObject().put("path", file.absolutePath).put("yaw", yaw).put("pitch", pitch)
        .put("roll", roll).put("hfov", hfov).put("vfov", vfov)
}

internal data class StitchInput(
    val directory: File, val capturedAtMs: Long, val frames: List<StitchFrame>, val plan: StitchPlan, val location: CaptureLocation?,
) {
    fun framesJson(): String = JSONObject().put("images", JSONArray().apply { frames.forEach { put(it.json()) } })
        .put("memoryBudgetBytes", plan.memoryBudgetBytes).toString()
}
