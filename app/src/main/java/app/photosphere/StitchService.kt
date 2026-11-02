package app.photosphere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** Foreground, local-only work. The Activity can detach while this single native job completes. */
class StitchService : Service() {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "sphere-stitch-native") }
    @Volatile private var running: StitchWork? = null
    @Volatile private var shuttingDown = false
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Photo Sphere processing", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress and cancellation for on-device panorama generation" })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            running?.let { work ->
                if (intent.getStringExtra(EXTRA_JOB) == work.jobId) {
                    StitchRepository.cancel(this, work.sessionId)
                    notifications.notify(NOTIFICATION_ID, notification(work, 0, "Cancelling stitching"))
                }
            }
            if (running == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val jobId = intent?.getStringExtra(EXTRA_JOB)
        val work = jobId?.let(StitchRepository::currentWork)
        if (intent?.action != ACTION_START || work == null) {
            // Native work is never resumed after process death. A later state read reconciles
            // the persisted RUNNING entry with the last atomically committed panorama.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running != null) return START_NOT_STICKY
        try {
            startForeground(NOTIFICATION_ID, notification(work, 0, "Preparing original photos"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            running = work
            worker.execute { execute(work) }
        } catch (error: Exception) {
            LocalDiagnostics.record(this, "stitch_start_failed", work.sessionId, mapOf("errorType" to error.javaClass.simpleName))
            StitchRepository.finish(this, work, StitchStatus.FAILED, "Could not start foreground processing: ${error.message ?: error.javaClass.simpleName}")
            StitchRepository.release(work)
            running = null
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        running?.let { work ->
            StitchRepository.interrupt(this, work, "Android stopped this processing session. Generate again to restart.")
        }
        // Android requires the service to leave the foreground promptly; native cancellation is
        // cooperative and the worker will discard its unpublished files at its next checkpoint.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        shuttingDown = true
        running?.let { work ->
            if (!work.terminal.get()) {
                StitchRepository.interrupt(this, work, "Stitching service stopped before completion. Generate again to restart.")
            }
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun execute(work: StitchWork) {
        var staging: File? = null
        var completedDirectory: File? = null
        var committed = false
        var wakeLock: PowerManager.WakeLock? = null
        val started = SystemClock.elapsedRealtime()
        val deadline = started + MAX_JOB_MS
        LocalDiagnostics.record(this, "stitch_started", work.sessionId, mapOf("quality" to work.quality.name))
        try {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoSphere:stitch")
                .apply { setReferenceCounted(false); acquire(MAX_JOB_MS) }
            checkpoint(work, deadline)
            StitchRepository.attachPreviousResult(this, work)
            StitchRepository.update(this, work, StitchStatus.RUNNING, 0, "Checking original photos and available memory")
            val input = StitchRepository.prepare(this, work.sessionId)
            checkpoint(work, deadline)
            val width = input.plan.width(work.quality)
            require(input.directory.usableSpace >= input.plan.requiredFreeBytes(work.quality)) {
                "Not enough free storage. This job needs about ${input.plan.requiredFreeBytes(work.quality) / StitchSizing.MIB} MiB of workspace; free some storage and try again."
            }
            val stagingRoot = File(input.directory, "stitch-work")
            check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Cannot create stitching workspace" }
            val jobDirectory = File(stagingRoot, work.jobId)
            require(jobDirectory.canonicalFile.parentFile == stagingRoot.canonicalFile && !jobDirectory.exists()) { "Invalid stitching workspace" }
            check(jobDirectory.mkdir()) { "Cannot create stitching job directory" }
            staging = jobDirectory
            val image = File(jobDirectory, "panorama.jpg")
            val preview = File(jobDirectory, "panorama-preview.jpg")
            var lastProgressTime = 0L
            var lastPercent = -1
            val progressCallback = StitchProgress { percent, message ->
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) {
                    work.interruption = "Stitching reached the processing limit. Your originals are safe; generate again to restart."
                    work.cancelled.set(true)
                }
                if (work.cancelled.get() || work.terminal.get() || shuttingDown) false
                else {
                    val bounded = percent.coerceIn(0, 94)
                    if (bounded != lastPercent && now - lastProgressTime >= 250L || now - lastProgressTime >= 1500L) {
                        lastPercent = bounded
                        lastProgressTime = now
                        StitchRepository.update(this, work, StitchStatus.RUNNING, bounded, message)
                        notifications.notify(NOTIFICATION_ID, notification(work, bounded, message))
                    }
                    true
                }
            }
            val native = JSONObject(NativeStitcher.stitch(
                input.framesJson(), image.absolutePath, preview.absolutePath, width, progressCallback))
            checkpoint(work, deadline)
            when (native.optString("status")) {
                "cancelled" -> throw Cancelled()
                "complete" -> Unit
                else -> error(native.optString("message", "Native stitching could not align these photos"))
            }
            val actualWidth = native.getInt("width")
            val actualHeight = native.getInt("height")
            require(actualWidth in 512..width && actualWidth % 2 == 0 && actualHeight == actualWidth / 2) { "Stitcher returned invalid panorama dimensions" }
            val coverage = native.getDouble("coverageFraction")
            require(coverage.isFinite() && coverage > 0.0 && coverage <= 1.0) { "Stitcher returned invalid coverage" }
            val pixelCoverage = native.getDouble("pixelCoverageFraction")
            require(pixelCoverage.isFinite() && pixelCoverage in 0.0..1.0) { "Stitcher returned invalid pixel coverage" }
            require(native.getInt("visuallyMatchedImages") >= 2 && native.getInt("visuallyMatchedPairs") >= 1) {
                "The photos did not produce a verified visual alignment"
            }
            val peak = native.getLong("estimatedPeakBytes")
            require(peak in 1..input.plan.memoryBudgetBytes) { "Stitcher exceeded the configured memory estimate" }
            validateOutput(image, actualWidth, actualHeight)
            validateOutput(preview)
            checkpoint(work, deadline)
            StitchRepository.update(this, work, StitchStatus.RUNNING, 95, "Saving spherical metadata and GPS")
            PanoramaMetadata.write(image, actualWidth, actualHeight, input.frames.size, input.capturedAtMs, input.location, coverage)
            sync(image)
            sync(preview)
            validateOutput(image, actualWidth, actualHeight)
            val warnings = (input.plan.warnings + StitchRepository.stringList(native.optJSONArray("warnings"))).distinct().toMutableList()
            if (actualWidth < width) warnings.add("Native processing reduced output to $actualWidth × $actualHeight to fit its memory budget.")
            if (work.quality == StitchQuality.QUICK && input.plan.widthQuick < input.plan.widthFull) {
                warnings.add("Quick output uses fewer pixels; full-resolution original photos are preserved.")
            }
            val now = System.currentTimeMillis()
            native.put("outputPath", "stitch-results/${work.jobId}/panorama.jpg")
                .put("previewPath", "stitch-results/${work.jobId}/panorama-preview.jpg")
            val report = JSONObject()
                .put("schema", "app.photosphere.panorama").put("schemaVersion", 1).put("status", "complete")
                .put("sessionId", work.sessionId).put("jobId", work.jobId).put("quality", work.quality.name)
                .put("createdAtMs", now).put("capturedAtMs", input.capturedAtMs)
                .put("width", actualWidth).put("height", actualHeight).put("sourceCount", input.frames.size)
                .put("coverageFraction", coverage).put("pixelCoverageFraction", pixelCoverage)
                .put("visuallyMatchedImages", native.optInt("visuallyMatchedImages"))
                .put("visuallyMatchedPairs", native.optInt("visuallyMatchedPairs"))
                .put("memoryBudgetBytes", input.plan.memoryBudgetBytes).put("estimatedPeakBytes", peak)
                .put("completePlanned", input.plan.completePlanned).put("targetCount", input.plan.targetCount)
                .put("warnings", JSONArray(warnings)).put("native", native)
                .put("image", "stitch-results/${work.jobId}/panorama.jpg")
                .put("preview", "stitch-results/${work.jobId}/panorama-preview.jpg")
                .put("sourceFiles", JSONArray().apply { input.frames.forEach { put("originals/${it.file.name}") } })
            StitchRepository.writeJson(File(jobDirectory, "details.json"), report)
            checkpoint(work, deadline)
            StitchRepository.update(this, work, StitchStatus.RUNNING, 99, "Publishing completed Photo Sphere")
            val completedRoot = File(input.directory, "stitch-results")
            check(completedRoot.isDirectory || completedRoot.mkdirs()) { "Cannot create panorama storage" }
            val finalDirectory = File(completedRoot, work.jobId)
            require(finalDirectory.canonicalFile.parentFile == completedRoot.canonicalFile && !finalDirectory.exists()) { "Invalid panorama destination" }
            try { Files.move(jobDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(jobDirectory.toPath(), finalDirectory.toPath()) }
            completedDirectory = finalDirectory
            staging = null
            checkpoint(work, deadline)
            // The old panorama.json remains authoritative through native generation, metadata
            // writing and directory publication. AtomicFile replaces it only after all files exist.
            if (!StitchRepository.commit(work) {
                StitchRepository.writeJson(File(input.directory, StitchRepository.RESULT_FILE), report)
            }) throw Cancelled()
            committed = true
            val result = StitchRepository.result(this, work.sessionId) ?: error("Published panorama could not be read")
            require(result.jobId == work.jobId) { "Panorama report could not be atomically replaced" }
            StitchRepository.finish(this, work, StitchStatus.SUCCEEDED,
                if (coverage >= .995) "Photo Sphere ready" else "Partial Photo Sphere ready · ${(coverage * 100).toInt()}% sphere coverage", result)
            LocalDiagnostics.record(this, "stitch_succeeded", work.sessionId, mapOf(
                "durationMs" to SystemClock.elapsedRealtime() - started, "width" to actualWidth,
                "height" to actualHeight, "sources" to input.frames.size, "coverage" to coverage,
                "matchedPairs" to native.optInt("visuallyMatchedPairs"),
                "residualDegrees" to native.optDouble("meanVisualResidualDegrees")))
        } catch (_: Cancelled) {
            val interrupted = work.interruption
            LocalDiagnostics.record(this, "stitch_cancelled", work.sessionId, mapOf("interrupted" to (interrupted != null)))
            StitchRepository.finish(this, work, if (interrupted != null) StitchStatus.INTERRUPTED else StitchStatus.CANCELLED,
                interrupted ?: "Stitching cancelled. Original photos and any earlier panorama are preserved.")
        } catch (error: Throwable) {
            val status = if (work.cancelled.get()) {
                if (work.interruption != null) StitchStatus.INTERRUPTED else StitchStatus.CANCELLED
            } else StitchStatus.FAILED
            LocalDiagnostics.record(this, "stitch_failed", work.sessionId, mapOf(
                "status" to status.name, "errorType" to error.javaClass.simpleName,
                "durationMs" to SystemClock.elapsedRealtime() - started))
            StitchRepository.finish(this, work, status, work.interruption ?: if (work.cancelled.get())
                "Stitching cancelled. Original photos and any earlier panorama are preserved."
                else "Could not create panorama: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            staging?.let { removeUnpublished(it, "stitch-work", work) }
            if (!committed) completedDirectory?.let { removeUnpublished(it, "stitch-results", work) }
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            // Complete service ownership on main in one turn. Releasing the repository first
            // could let a new start race this worker's stopForeground()/stopSelf().
            Handler(Looper.getMainLooper()).post {
                if (running === work) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    running = null
                }
                // A notification cancellation has a newer startId than the original worker.
                // This main turn owns the still-active job, so stop the service unconditionally
                // before allowing a new start to acquire repository ownership.
                stopSelf()
                StitchRepository.release(work)
            }
        }
    }

    private fun checkpoint(work: StitchWork, deadline: Long) {
        if (SystemClock.elapsedRealtime() >= deadline) {
            work.interruption = "Stitching reached the processing limit. Your originals are safe; generate again to restart."
            work.cancelled.set(true)
        }
        if (work.cancelled.get() || work.terminal.get() || shuttingDown) throw Cancelled()
    }

    private fun validateOutput(file: File, expectedWidth: Int? = null, expectedHeight: Int? = null) {
        require(StitchRepository.isCompleteJpeg(file)) { "Stitcher produced an incomplete JPEG" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0 &&
            (expectedWidth == null || options.outWidth == expectedWidth) &&
            (expectedHeight == null || options.outHeight == expectedHeight)) { "Stitcher output JPEG dimensions are invalid" }
    }

    private fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }

    private fun removeUnpublished(directory: File, parentName: String, work: StitchWork) {
        runCatching {
            val session = StitchRepository.sessionDirectory(this, work.sessionId)
            val parent = File(session, parentName).canonicalFile
            require(directory.name == work.jobId && directory.canonicalFile.parentFile == parent)
            directory.deleteRecursively()
        }
    }

    private fun notification(work: StitchWork, progress: Int, message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("stitchSessionId", work.sessionId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, StitchService::class.java)
            .setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, work.jobId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Creating Photo Sphere").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress.coerceIn(0, 100), progress == 0)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    private class Cancelled : Exception()

    companion object {
        internal const val ACTION_START = "app.photosphere.STITCH_START"
        internal const val ACTION_CANCEL = "app.photosphere.STITCH_CANCEL"
        internal const val EXTRA_JOB = "jobId"
        private const val CHANNEL = "photosphere.stitching"
        private const val NOTIFICATION_ID = 3401
        private const val MAX_JOB_MS = 30L * 60L * 1000L
    }
}
