package app.photosphere

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/** Explicit real-camera validation: two private test JPEGs, no location or existing session edits. */
class BrightnessSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        super.onStart()
        val started = SystemClock.elapsedRealtime()
        val folder = File(targetContext.cacheDir, "brightness-validation/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
        val report = JSONObject().put("testOnly", true).put("model", Build.MODEL)
            .put("androidSdk", Build.VERSION.SDK_INT).put("locationRequested", false)
            .put("retainedTestFiles", true)
        val shots = JSONArray()
        report.put("shots", shots)
        var activity: Activity? = null
        var engine: CameraEngine? = null
        var failure: Throwable? = null
        try {
            check(folder.mkdirs() || folder.isDirectory)
            check(targetContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            status("Opening camera; keep it facing an ordinary, evenly lit object")
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            lateinit var preview: TextureView
            runOnMainSync {
                host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                preview = TextureView(host)
                host.setContentView(preview, ViewGroup.LayoutParams(-1, -1))
            }
            waitForIdleSync()
            val camera = CameraEngine(targetContext)
            engine = camera
            val opened = CountDownLatch(1)
            val info = AtomicReference<CameraInfo?>()
            val cameraError = AtomicReference<String?>()
            runOnMainSync {
                camera.open(preview, CaptureQuality.FAST, { info.set(it); opened.countDown() }, {
                    cameraError.set(it); opened.countDown()
                })
            }
            check(opened.await(45, TimeUnit.SECONDS)) { "Camera did not open" }
            check(cameraError.get() == null) { cameraError.get().orEmpty() }
            val capabilities = info.get() ?: error("No camera capabilities")
            report.put("aeLockSupported", capabilities.aeLockSupported).put("awbLockSupported", capabilities.awbLockSupported)
            check(capabilities.aeLockSupported && capabilities.awbLockSupported) { "This validation requires both lock capabilities" }
            waitForUnlockedPreview(camera)
            runOnMainSync { camera.beginCaptureSession(true) }
            SystemClock.sleep(700)
            val captured = mutableListOf<CaptureMetadata>()
            val originals = mutableListOf<Pair<File, String>>()
            repeat(2) { index ->
                status("Taking brightness test photo ${index + 1}/2")
                val file = File(folder, "test-original-${index + 1}.jpg")
                val latch = CountDownLatch(1)
                val saved = AtomicReference<CaptureMetadata?>()
                val error = AtomicReference<String?>()
                runOnMainSync {
                    camera.capture(file, null, { saved.set(it); latch.countDown() }, { error.set(it); latch.countDown() })
                }
                check(latch.await(45, TimeUnit.SECONDS)) { "Photo timed out" }
                check(error.get() == null) { error.get().orEmpty() }
                val metadata = saved.get() ?: error("Missing camera metadata")
                val frame = JSONObject(metadata.cameraMetadataJson)
                val locks = frame.getJSONObject("brightnessLock")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val hash = sha256(file)
                val shot = JSONObject().put("file", file.name).put("sha256", hash)
                    .put("bytes", file.length()).put("width", bounds.outWidth).put("height", bounds.outHeight)
                    .put("metadata", frame)
                shots.put(shot)
                // The test acceptance journal is fsynced before the same API used by MainActivity.
                writeJson(File(folder, "accepted-${index + 1}.json"), shot.put("accepted", true))
                runOnMainSync { camera.resolveCapture(metadata, true) }
                check(bounds.outWidth == metadata.width && bounds.outHeight == metadata.height)
                check(file.length() == metadata.bytes && metadata.bytes > 1000)
                check(locks.getBoolean("requested") && locks.getBoolean("aeSupported") && locks.getBoolean("awbSupported"))
                check(locks.getBoolean("aeRequested") && locks.getBoolean("awbRequested")) { "Still request omitted a lock" }
                check(locks.getBoolean("aeApplied") && locks.getBoolean("awbApplied")) { "Camera did not apply both locks: $locks" }
                check(locks.getBoolean("retainedFromAcceptedPhoto") == (index > 0)) { "Acceptance did not retain the lock" }
                check(locks.isNull("fallbackReason")) { "Unexpected lock fallback: $locks" }
                captured += metadata
                originals += file to hash
                SystemClock.sleep(400)
            }
            val first = captured[0]; val second = captured[1]
            check(second.sensorTimestampNs > first.sensorTimestampNs)
            val firstExposure = first.exposureTimeNs; val secondExposure = second.exposureTimeNs
            if (firstExposure != null && secondExposure != null) {
                val difference = abs(firstExposure - secondExposure)
                report.put("exposureDifferenceNs", difference)
                check(difference <= max(1L, firstExposure / 100)) { "Exposure changed by more than 1% under AE lock" }
            }
            if (first.sensitivityIso != null && second.sensitivityIso != null)
                check(first.sensitivityIso == second.sensitivityIso) { "ISO changed under AE lock" }
            val firstGains = JSONObject(first.cameraMetadataJson).optJSONArray("colorCorrectionGains")
            val secondGains = JSONObject(second.cameraMetadataJson).optJSONArray("colorCorrectionGains")
            if (firstGains != null && secondGains != null) {
                check(firstGains.length() == secondGains.length())
                var maximumDifference = 0.0
                for (i in 0 until firstGains.length()) maximumDifference = max(maximumDifference, abs(firstGains.getDouble(i) - secondGains.getDouble(i)))
                report.put("maximumWhiteBalanceGainDifference", maximumDifference)
                check(maximumDifference <= .00001) { "White balance gains changed under AWB lock" }
            }
            originals.forEach { (file, hash) -> check(sha256(file) == hash) { "An original JPEG changed" } }
            status("Checking that ending the session restores automatic preview")
            runOnMainSync { camera.endCaptureSession() }
            waitForUnlockedPreview(camera)
            // A fresh automatic session also must not inherit the preceding sphere's locks.
            runOnMainSync { camera.beginCaptureSession(false) }
            waitForUnlockedPreview(camera)
            report.put("sessionEndUnlockVerified", true).put("automaticSessionUnlockVerified", true)
        } catch (error: Throwable) {
            failure = error
            report.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
        } finally {
            runCatching { runOnMainSync { engine?.endCaptureSession(); engine?.close(); activity?.finish() } }
                .onFailure { if (failure == null) failure = it }
        }
        report.put("passed", failure == null).put("durationMs", SystemClock.elapsedRealtime() - started)
        val path = File(folder, "report.json")
        runCatching { writeJson(path, report) }.onFailure { if (failure == null) failure = it }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("reportPath", path.absolutePath)
            putString("stream", if (failure == null) "\nPASS: two original JPEGs retained AE/AWB locks and consistent sensor values; session end restored automatic preview.\n"
                else "\nFAIL: $failure\n")
        })
    }

    private fun waitForUnlockedPreview(engine: CameraEngine) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val cameraRun = field(engine, "current") ?: error("Camera closed")
            val handler = field(cameraRun, "handler") as Handler
            val latch = CountDownLatch(1)
            val result = AtomicReference<TotalCaptureResult?>()
            check(handler.post { result.set(field(cameraRun, "latestPreview") as TotalCaptureResult?); latch.countDown() })
            check(latch.await(2, TimeUnit.SECONDS))
            val latest = result.get()
            if (latest != null && latest[CaptureResult.CONTROL_AE_LOCK] == false && latest[CaptureResult.CONTROL_AWB_LOCK] == false) return
            SystemClock.sleep(60)
        }
        error("Preview did not restore automatic AE/AWB")
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    private fun status(message: String) = sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    private fun writeJson(file: File, value: JSONObject) = FileOutputStream(file).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
