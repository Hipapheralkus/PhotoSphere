package app.photosphere

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CaptureLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val timeMs: Long,
)

class CaptureSession internal constructor(
    val id: String,
    val directory: File,
    val targets: List<CaptureTarget>,
    internal val manifest: JSONObject,
) {
    @Volatile var capturedCount: Int = 0
        internal set
    @Volatile var status: String = "capturing"
        internal set
}

data class SessionSummary(
    val id: String,
    val directory: File,
    val createdAtMs: Long,
    val capturedCount: Int,
    val targetCount: Int,
    val status: String,
    val bytes: Long,
    val recoveredOriginalCount: Int = 0,
)

/** Synchronous operations: invoke on the application's IO executor, never the camera/UI thread. */
class SessionStore(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "sessions")

    @Synchronized
    fun create(
        camera: CameraInfo,
        targets: List<CaptureTarget>,
        quality: CaptureQuality,
        location: CaptureLocation? = null,
    ): CaptureSession {
        require(targets.isNotEmpty() && targets.map { it.id }.distinct().size == targets.size)
        check(root.isDirectory || root.mkdirs()) { "Cannot create session storage" }
        val now = System.currentTimeMillis()
        val id = "${now}_${UUID.randomUUID().toString().take(8)}"
        val directory = File(root, id)
        check(File(directory, "originals").mkdirs()) { "Cannot create original image storage" }
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val manifest = JSONObject()
            .put("schema", "app.photosphere.capture-session")
            .put("schemaVersion", 1)
            .put("id", id)
            .put("createdAtMs", now)
            .put("createdAt", Instant.ofEpochMilli(now).toString())
            .put("status", "capturing")
            .put("quality", quality.name.lowercase())
            .put("projection", "equirectangular")
            .put("intendedCoverage", JSONObject().put("horizontalDegrees", 360).put("verticalDegrees", 180))
            .put("orientationConvention", "degrees; yaw right, pitch up, roll clockwise; yaw relative to session start; portrait rear camera")
            .put("timestampConvention", "pose and poseSamples timestamps use Android elapsedRealtimeNanos; Camera2 sensorTimestampNs is comparable to that clock only when capture.cameraMetadata.sensorTimestampSource is REALTIME (1), never when UNKNOWN (0) or absent; wall time uses Unix milliseconds")
            .put("resumeSupported", false)
            .put("camera", JSONObject()
                .put("cameraId", camera.cameraId)
                .put("jpegWidth", camera.jpegWidth).put("jpegHeight", camera.jpegHeight)
                .put("previewWidth", camera.previewWidth).put("previewHeight", camera.previewHeight)
                .put("sensorOrientation", camera.sensorOrientation)
                .put("horizontalFovDegrees", camera.horizontalFov).put("verticalFovDegrees", camera.verticalFov)
                .put("maxResolutionMode", camera.maxResolutionMode)
                .put("diagnostics", parseObject(camera.diagnosticsJson)))
            .put("software", JSONObject()
                .put("package", context.packageName)
                .put("versionName", packageInfo.versionName ?: "unknown")
                .put("versionCode", packageInfo.longVersionCode)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL))
            .put("targetCount", targets.size)
            .put("capturedCount", 0)
            .put("targets", JSONArray().apply { targets.forEach { put(targetJson(it)) } })
            .put("captures", JSONArray())
            .put("location", location?.let(::locationJson) ?: JSONObject.NULL)
        val session = CaptureSession(id, directory, targets.toList(), manifest)
        writeManifest(session)
        return session
    }

    /** Every attempt has a distinct name, so a retake or interrupted write cannot overwrite a source. */
    @Synchronized
    fun allocatePhoto(session: CaptureSession, target: CaptureTarget): File {
        validateSession(session)
        check(session.status == "capturing") { "This capture session is closed" }
        require(target in session.targets)
        return File(session.directory, "originals/target_${target.id.toString().padStart(4, '0')}_${UUID.randomUUID().toString().take(8)}.jpg")
    }

    /** Commit only after the camera has closed and synced a complete JPEG file. */
    @Synchronized
    fun recordCapture(
        session: CaptureSession,
        target: CaptureTarget,
        pose: Pose,
        metadata: CaptureMetadata,
        file: File,
        location: CaptureLocation? = null,
        motionSamples: List<Pose> = emptyList(),
        accepted: Boolean = true,
    ) {
        validateSession(session)
        require(target in session.targets)
        check(session.status == "capturing") { "This capture session is closed" }
        require(file.canonicalFile.parentFile == File(session.directory, "originals").canonicalFile)
        check(file.length() == metadata.bytes && isCompleteJpeg(file)) { "Original JPEG is missing or incomplete" }
        val captures = session.manifest.getJSONArray("captures")
        val record = JSONObject()
            .put("targetId", target.id)
            .put("accepted", accepted)
            .put("file", "originals/${file.name}")
            .put("recordedAtMs", System.currentTimeMillis())
            .put("pose", poseJson(pose))
            .put("poseSamples", JSONArray().apply { motionSamples.forEach { put(poseJson(it)) } })
            .put("capture", JSONObject()
                .put("sensorTimestampNs", metadata.sensorTimestampNs)
                .put("exposureTimeNs", metadata.exposureTimeNs ?: JSONObject.NULL)
                .put("sensitivityIso", metadata.sensitivityIso ?: JSONObject.NULL)
                .put("focalLengthMm", metadata.focalLengthMm ?: JSONObject.NULL)
                .put("focusDistanceDiopters", metadata.focusDistanceDiopters ?: JSONObject.NULL)
                .put("jpegOrientation", metadata.jpegOrientation)
                .put("width", metadata.width).put("height", metadata.height)
                .put("bytes", metadata.bytes).put("durationMs", metadata.durationMs)
                .put("cameraMetadata", parseObject(metadata.cameraMetadataJson)))
            .put("location", location?.let(::locationJson) ?: JSONObject.NULL)
        // Prepare a copy so a failed atomic commit never advances the in-memory capture count.
        val updated = JSONObject(session.manifest.toString())
        updated.getJSONArray("captures").put(record)
        val ids = acceptedTargetIds(updated.getJSONArray("captures"))
        updated.put("capturedCount", ids.size)
        writeAtomic(File(session.directory, "manifest.json"), updated)
        captures.put(record)
        session.manifest.put("capturedCount", ids.size)
        session.capturedCount = ids.size
    }

    @Synchronized
    fun finish(session: CaptureSession) {
        validateSession(session)
        val acceptedCount = acceptedTargetIds(session.manifest.getJSONArray("captures")).size
        val nextStatus = if (acceptedCount == session.targets.size) "complete" else "partial"
        val updated = JSONObject(session.manifest.toString())
            .put("capturedCount", acceptedCount)
            .put("status", nextStatus).put("finishedAtMs", System.currentTimeMillis())
        writeAtomic(File(session.directory, "manifest.json"), updated)
        session.manifest.put("capturedCount", acceptedCount).put("status", nextStatus)
            .put("finishedAtMs", updated.getLong("finishedAtMs"))
        session.capturedCount = acceptedCount
        session.status = nextStatus
    }

    /** Recovers AtomicFile backups and lists unfinished sessions without inventing a new orientation anchor. */
    @Synchronized
    fun listSessions(): List<SessionSummary> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
        try {
            val manifest = readManifest(directory)
            val valid = originalFiles(directory)
            val records = manifest.getJSONArray("captures")
            val validNames = valid.map { "originals/${it.name}" }.toSet()
            val committedNames = (0 until records.length()).map { records.getJSONObject(it).getString("file") }.toSet()
            val targetIds = acceptedTargetIds(records, validNames)
            val status = manifest.optString("status", "unfinished")
            SessionSummary(
                id = directory.name, directory = directory,
                createdAtMs = manifest.optLong("createdAtMs", directory.lastModified()),
                capturedCount = targetIds.size, targetCount = manifest.optInt("targetCount"),
                status = if (status == "complete" && targetIds.size != manifest.optInt("targetCount")) "incomplete" else status,
                bytes = valid.sumOf { it.length() } + File(directory, "manifest.json").length(),
                recoveredOriginalCount = validNames.count { it !in committedNames },
            )
        } catch (_: Exception) {
            // Even when a manifest cannot be recovered, complete original files remain exportable.
            val valid = originalFiles(directory)
            if (valid.isEmpty()) null else SessionSummary(directory.name, directory, directory.lastModified(), 0, 0,
                "recovered originals", valid.sumOf { it.length() }, valid.size)
        }
    }.sortedByDescending { it.createdAtMs }

    fun exportZip(summary: SessionSummary, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open export destination")
        output.use { exportZip(summary, it) }
    }

    /** Streams each original in bounded memory. The caller owns and closes output. */
    @Synchronized
    fun exportZip(summary: SessionSummary, output: OutputStream) {
        require(summary.directory.canonicalFile.parentFile == root.canonicalFile)
        val manifest = try { readManifest(summary.directory) } catch (_: Exception) { null }
        val originals = originalFiles(summary.directory)
        val committed = manifest?.optJSONArray("captures") ?: JSONArray()
        val recordedNames = (0 until committed.length()).map { committed.getJSONObject(it).getString("file") }.toSet()
        val validNames = originals.map { "originals/${it.name}" }.toSet()
        val recovery = JSONObject()
            .put("exportedAtMs", System.currentTimeMillis())
            .put("manifestReadable", manifest != null)
            .put("orientationResumeSupported", false)
            .put("uncommittedOriginals", JSONArray(validNames.filter { it !in recordedNames }))
            .put("rejectedOriginals", JSONArray((0 until committed.length()).map { committed.getJSONObject(it) }
                .filter { !it.optBoolean("accepted", true) && it.getString("file") in validNames }
                .map { it.getString("file") }))
            .put("missingOrIncompleteRecordedOriginals", JSONArray(recordedNames.filter { it !in validNames }))
            .put("note", "Original JPEGs are preserved without edits, including rejected captures. Rejected captures do not count toward target coverage. Uncommitted originals have no confirmed pose/capture record. Incomplete and zero-byte JPEGs are excluded.")
        val borrowedOutput = object : FilterOutputStream(output) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }
        ZipOutputStream(borrowedOutput).use { zip ->
            // JPEG is already compressed; avoid a second CPU-intensive compression pass.
            zip.setLevel(0)
            fun addJson(name: String, json: JSONObject) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (manifest != null) addJson("manifest.json", manifest)
            addJson("recovery.json", recovery)
            val buffer = ByteArray(64 * 1024)
            originals.forEach { file ->
                zip.putNextEntry(ZipEntry("originals/${file.name}").apply { time = file.lastModified() })
                file.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun validateSession(session: CaptureSession) {
        require(session.directory.canonicalFile.parentFile == root.canonicalFile)
        check(session.directory.isDirectory) { "Capture session is missing" }
    }

    private fun writeManifest(session: CaptureSession) = writeAtomic(File(session.directory, "manifest.json"), session.manifest)

    private fun readManifest(directory: File): JSONObject {
        val text = AtomicFile(File(directory, "manifest.json")).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text).also { require(it.getInt("schemaVersion") == 1) { "Unsupported session version" } }
    }

    private fun writeAtomic(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun originalFiles(directory: File): List<File> = File(directory, "originals").listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("jpg", true) && isCompleteJpeg(it) }.sortedBy { it.name }

    private fun isCompleteJpeg(file: File): Boolean = try {
        if (!file.isFile || file.length() < 4) false else RandomAccessFile(file, "r").use {
            val start = it.readUnsignedShort()
            it.seek(it.length() - 2)
            start == 0xFFD8 && it.readUnsignedShort() == 0xFFD9
        }
    } catch (_: Exception) { false }

    private fun targetJson(target: CaptureTarget) = JSONObject()
        .put("id", target.id).put("yawDegrees", target.yaw).put("pitchDegrees", target.pitch)

    /** Missing accepted flags are valid captures from older version-1 manifests. */
    private fun acceptedTargetIds(records: JSONArray, validNames: Set<String>? = null): Set<Int> =
        (0 until records.length()).map { records.getJSONObject(it) }
            .filter { it.optBoolean("accepted", true) && (validNames == null || it.getString("file") in validNames) }
            .map { it.getInt("targetId") }.toSet()

    private fun poseJson(pose: Pose) = JSONObject()
        .put("yawDegrees", finite(pose.yaw)).put("pitchDegrees", finite(pose.pitch))
        .put("rollDegrees", finite(pose.roll)).put("angularSpeedDegreesPerSecond", finite(pose.angularSpeed))
        .put("timestampNs", pose.timestampNs)
        .put("rotationVector", JSONArray().apply { pose.rotationVector.forEach { put(finite(it.toDouble())) } })

    private fun locationJson(location: CaptureLocation): JSONObject {
        require(location.latitude.isFinite() && location.latitude in -90.0..90.0)
        require(location.longitude.isFinite() && location.longitude in -180.0..180.0)
        return JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("altitudeMeters", location.altitudeMeters?.let(::finite) ?: JSONObject.NULL)
            .put("accuracyMeters", location.accuracyMeters?.toDouble()?.let(::finite) ?: JSONObject.NULL)
            .put("timeMs", location.timeMs)
    }

    private fun finite(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL
    private fun parseObject(value: String): JSONObject = try { JSONObject(value) } catch (_: Exception) { JSONObject().put("raw", value) }
}
