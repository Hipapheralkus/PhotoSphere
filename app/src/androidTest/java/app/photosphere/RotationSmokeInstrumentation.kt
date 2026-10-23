package app.photosphere

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Explicit androidTest-only runner. Uses the ordinary capture UI and reads private state only
 * for assertions. It pauses the newly created session before the first 650 ms capture hold.
 */
class RotationSmokeInstrumentation : Instrumentation() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val started = SystemClock.elapsedRealtime()
        val runId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val outputDirectory = File(targetContext.cacheDir, "rotation-validation/$runId")
        val states = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("startedAtMs", System.currentTimeMillis()).put("states", states)
        var activity: MainActivity? = null
        var testSession: CaptureSession? = null
        var failure: Throwable? = null
        val pauseGuardStopped = AtomicBoolean(false)
        var pauseGuard: Runnable? = null
        try {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Cannot create rotation report directory" }
            check(targetContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Grant PhotoSphere CAMERA permission before running rotation validation"
            }
            check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the phone before running the interactive preview rotation test"
            }
            progress("Launching the ordinary PhotoSphere activity")
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = host
            onMain { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil("initial portrait activity", 15_000) {
                !host.isDestroyed && read<Boolean>(host, "activityResumed") &&
                    host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    host.display?.rotation == Surface.ROTATION_0 &&
                    findButton(host.window.decorView, "Start a sphere") != null
            }
            onMain {
                val start = findButton(host.window.decorView, "Start a sphere") ?: error("Start a sphere button missing")
                check(start.isEnabled && start.performClick()) { "Start a sphere button was not clickable" }
            }
            progress("Waiting for the real camera preview and Start here")
            waitUntil("ready capture screen", 45_000) {
                read<String>(host, "screen") == "capture" &&
                    read<CameraEngine>(host, "camera").isReady &&
                    read<Button>(host, "action").let { it.isEnabled && it.text.toString() == "Start here" } &&
                    read<Any?>(host, "pose") != null
            }

            val paused = CountDownLatch(1)
            val pauseFailure = AtomicReference<Throwable?>()
            // This guard clicks the normal pause button as soon as asynchronous session creation
            // completes. No private controller state is mutated and no photograph is requested.
            val guard = object : Runnable {
                override fun run() {
                    if (pauseGuardStopped.get() || host.isDestroyed) return
                    try {
                        val session = read<CaptureSession?>(host, "session")
                        if (session != null && !read<Boolean>(host, "busy")) {
                            val action = read<Button>(host, "action")
                            if (!read<Boolean>(host, "paused") && action.isEnabled && action.text.toString() == "Pause capture") {
                                action.performClick()
                            }
                            if (read<Boolean>(host, "paused")) {
                                pauseGuardStopped.set(true)
                                paused.countDown()
                                return
                            }
                        }
                        ui.postDelayed(this, 10L)
                    } catch (error: Throwable) {
                        pauseFailure.set(error)
                        pauseGuardStopped.set(true)
                        paused.countDown()
                    }
                }
            }
            pauseGuard = guard
            onMain {
                check(read<Button>(host, "action").performClick()) { "Start here button was not clickable" }
                ui.post(guard)
            }
            check(paused.await(10, TimeUnit.SECONDS)) { "The new session did not become safely paused" }
            pauseFailure.get()?.let { throw it }
            var baseline: Baseline? = null
            onMain {
                val created = read<CaptureSession?>(host, "session") ?: error("No capture session was created")
                testSession = created
                val preview = read<TextureView>(host, "preview")
                val engine = read<CameraEngine>(host, "camera")
                val run = read<Any?>(engine, "current") ?: error("Camera has no active run")
                baseline = Baseline(
                    created, preview, preview.surfaceTexture ?: error("Preview has no surface"),
                    engine, run, read<Any?>(run, "session") ?: error("Camera has no capture session"),
                    read<TextView>(host, "counter"), read<TextView>(host, "counter").text.toString(),
                    read<Int>(host, "sessionGeneration"), read<Int>(host, "targetIndex"),
                )
                check(created.capturedCount == 0 && read<Set<Int>>(host, "completed").isEmpty()) {
                    "A photograph was accepted before the test could pause; no further captures will be attempted"
                }
                check(File(created.directory, "originals").listFiles().orEmpty().isEmpty()) {
                    "An original appeared before the test paused"
                }
                report.put("sessionId", created.id).put("activityIdentity", System.identityHashCode(host))
            }
            val initial = baseline ?: error("Cannot establish rotation baseline")
            snapshot(host, initial, "portrait_start", Surface.ROTATION_0, false, outputDirectory, states)

            val rotations = listOf(
                Rotation("landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90, true),
                Rotation("reverse_landscape", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, Surface.ROTATION_270, true),
                Rotation("portrait_return", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Surface.ROTATION_0, false),
            )
            for (rotation in rotations) {
                progress("Rotating paused capture to ${rotation.name}")
                onMain { host.requestedOrientation = rotation.requested }
                waitUntil("${rotation.name} layout and camera", 15_000) {
                    assertPersistentCapture(host, initial)
                    val orientation = if (rotation.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    host.resources.configuration.orientation == orientation &&
                        host.display?.rotation == rotation.display &&
                        read<Int>(host, "displayRotation") == rotation.display &&
                        layoutReady(host, rotation.landscape) && initial.camera.isReady
                }
                // Require the settled state to remain unchanged through more preview frames.
                SystemClock.sleep(350L)
                snapshot(host, initial, rotation.name, rotation.display, rotation.landscape, outputDirectory, states)
            }
            report.put("cameraSessionPreserved", true).put("captureSessionPreserved", true)
                .put("previewSurfacePreserved", true).put("unsolicitedCaptureCount", 0)
        } catch (error: Throwable) {
            failure = error
            report.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
            if (outputDirectory.isDirectory) runCatching { screenshot(File(outputDirectory, "failure.png")) }
        } finally {
            pauseGuardStopped.set(true)
            pauseGuard?.let { ui.removeCallbacks(it) }
            try {
                onMain {
                    activity?.let { host ->
                        if (!host.isDestroyed) {
                            // If an early failure occurred, still identify only the session created
                            // through this test's Start here action for transparent cleanup reporting.
                            if (testSession == null) testSession = read<CaptureSession?>(host, "session")
                            host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            host.finish()
                        }
                    }
                }
                waitForIdleSync()
            } catch (error: Throwable) {
                report.put("cleanupError", error.toString())
                if (failure == null) failure = error
            }
            // Keep the empty test session for normal lifecycle finalization. Do not race its IO
            // executor or remove a source if an unexpected capture caused the test to fail.
            testSession?.let {
                report.put("retainedTestSessionId", it.id)
                    .put("retainedTestSessionPath", it.directory.absolutePath)
                    .put("sessionCleanup", "Activity finished normally; session retained, no files deleted")
            }
        }
        report.put("passed", failure == null).put("durationMs", SystemClock.elapsedRealtime() - started)
        val reportFile = File(outputDirectory, "report.json")
        try {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Cannot create report directory" }
            FileOutputStream(reportFile).use { stream ->
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
        } catch (error: Throwable) { if (failure == null) failure = error }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("reportPath", reportFile.absolutePath)
            putString("screenshotDirectory", outputDirectory.absolutePath)
            putString("retainedTestSessionId", testSession?.id)
            putString("stream", if (failure == null)
                "\nPASS: portrait, landscape, reverse landscape, and portrait return preserve the activity, paused session, camera session and preview surface; landscape controls do not overlap the preview.\n"
                else "\nFAIL: $failure\n")
        })
    }

    private data class Rotation(val name: String, val requested: Int, val display: Int, val landscape: Boolean)
    private data class Baseline(
        val session: CaptureSession,
        val preview: TextureView,
        val surface: Any,
        val camera: CameraEngine,
        val cameraRun: Any,
        val cameraSession: Any,
        val counter: TextView,
        val counterText: String,
        val generation: Int,
        val targetIndex: Int,
    )

    /** All activity/view assertions run on main; a replacement activity destroys this instance. */
    private fun assertPersistentCapture(host: MainActivity, initial: Baseline) {
        check(!host.isDestroyed && !host.isFinishing) { "Rotation destroyed or finished the original Activity" }
        check(read<Boolean>(host, "activityResumed")) { "Rotation paused the Activity" }
        check(read<String>(host, "screen") == "capture") { "Rotation left the capture screen" }
        check(read<CaptureSession?>(host, "session") === initial.session) { "Rotation replaced the capture session object" }
        check(read<CaptureSession>(host, "session").id == initial.session.id) { "Rotation changed the capture session ID" }
        check(initial.session.status == "capturing") { "Rotation finalized the active capture session" }
        check(read<Int>(host, "sessionGeneration") == initial.generation) { "Rotation changed the capture generation" }
        check(read<Int>(host, "targetIndex") == initial.targetIndex && initial.session.capturedCount == 0) { "Rotation advanced capture progress" }
        check(read<Set<Int>>(host, "completed").isEmpty()) { "The paused test unexpectedly accepted a photograph" }
        check(read<Boolean>(host, "paused") && !read<Boolean>(host, "busy")) { "The capture is no longer safely paused" }
        check(!read<Boolean>(host, "interrupted") && !read<Boolean>(host, "endingSession")) { "Rotation interrupted or ended capture" }
        check(read<TextView>(host, "counter") === initial.counter && initial.counter.text.toString() == initial.counterText) {
            "Rotation replaced or changed the capture counter"
        }
        check(read<TextureView>(host, "preview") === initial.preview) { "Rotation replaced the TextureView" }
        check(initial.preview.isAvailable && initial.preview.surfaceTexture === initial.surface) { "Rotation destroyed or replaced the preview surface" }
        check(read<CameraEngine>(host, "camera") === initial.camera) { "Rotation replaced the CameraEngine" }
        check(read<Any?>(initial.camera, "current") === initial.cameraRun) { "Rotation reopened the camera run" }
        check(read<Any?>(initial.cameraRun, "session") === initial.cameraSession) { "Rotation replaced the Camera2 capture session" }
        check(File(initial.session.directory, "originals").listFiles().orEmpty().isEmpty()) { "A photograph was taken while the test was paused" }
    }

    private fun layoutReady(host: MainActivity, landscape: Boolean): Boolean {
        val preview = read<TextureView>(host, "preview")
        val pane = read<View>(host, "previewPane")
        val controls = read<View>(host, "captureControls")
        val overlay = read<View>(host, "overlay")
        if (preview.width <= 0 || preview.height <= 0 || overlay.width <= 0 || overlay.height <= 0) return false
        if (landscape && Rect.intersects(bounds(pane), bounds(controls))) return false
        val camera = read<CameraEngine>(host, "camera")
        val frame = camera.previewContentBounds() ?: return false
        return abs(overlay.width - frame.width()) <= 2f && abs(overlay.height - frame.height()) <= 2f
    }

    private fun snapshot(
        host: MainActivity, initial: Baseline, name: String, expectedRotation: Int, landscape: Boolean,
        directory: File, states: JSONArray,
    ) {
        val state = JSONObject().put("name", name)
        onMain {
            assertPersistentCapture(host, initial)
            check(initial.camera.isReady) { "Camera is not ready in $name" }
            check(host.display?.rotation == expectedRotation) { "Unexpected display rotation in $name" }
            check(layoutReady(host, landscape)) { "Preview and overlay geometry have not settled in $name" }
            val preview = bounds(initial.preview)
            val pane = bounds(read<View>(host, "previewPane"))
            val controls = bounds(read<View>(host, "captureControls"))
            val overlay = bounds(read<View>(host, "overlay"))
            val action = read<Button>(host, "action")
            val visibleAction = Rect()
            check(action.getGlobalVisibleRect(visibleAction) && visibleAction.width() >= action.width - 1 &&
                visibleAction.height() >= action.height - 1) { "Capture pause/resume button is clipped in $name" }
            check(preview.contains(overlay.centerX(), overlay.centerY())) { "Overlay centre is outside the preview in $name" }
            check(abs(overlay.exactCenterX() - preview.exactCenterX()) <= 2f &&
                abs(overlay.exactCenterY() - preview.exactCenterY()) <= 2f) { "Overlay is not centred on the preview in $name" }
            if (landscape) check(!Rect.intersects(pane, controls)) { "Landscape controls overlap the preview pane" }
            val frame = initial.camera.previewContentBounds() ?: error("Preview frame bounds missing")
            check(frame.left >= -1 && frame.top >= -1 && frame.right <= initial.preview.width + 1 &&
                frame.bottom <= initial.preview.height + 1) { "Camera frame exceeds the TextureView" }
            val info = read<CameraInfo>(host, "cameraInfo")
            val sensorSwap = info.sensorOrientation % 180 != 0
            val naturalW = if (sensorSwap) info.previewHeight else info.previewWidth
            val naturalH = if (sensorSwap) info.previewWidth else info.previewHeight
            val displaySwap = expectedRotation == Surface.ROTATION_90 || expectedRotation == Surface.ROTATION_270
            val expectedRatio = if (displaySwap) naturalH.toDouble() / naturalW else naturalW.toDouble() / naturalH
            check(abs(frame.width().toDouble() / frame.height() - expectedRatio) < .002) { "Camera frame aspect ratio is stretched in $name" }
            state.put("displayRotationDegrees", expectedRotation * 90)
                .put("activityIdentity", System.identityHashCode(host)).put("sessionId", initial.session.id)
                .put("sessionIdentity", System.identityHashCode(initial.session))
                .put("cameraSessionIdentity", System.identityHashCode(initial.cameraSession))
                .put("previewSurfaceIdentity", System.identityHashCode(initial.surface))
                .put("counter", initial.counter.text.toString()).put("cameraReady", true).put("paused", true)
                .put("previewBounds", rectJson(preview)).put("previewPaneBounds", rectJson(pane))
                .put("controlsBounds", rectJson(controls)).put("overlayBounds", rectJson(overlay))
                .put("cameraFrameInPreview", JSONArray().put(frame.left).put(frame.top).put(frame.right).put(frame.bottom))
                .put("landscapeAreasDoNotOverlap", !landscape || !Rect.intersects(pane, controls))
        }
        val screenshotFile = File(directory, "$name.png")
        screenshot(screenshotFile)
        state.put("screenshot", screenshotFile.absolutePath)
        states.put(state)
    }

    private fun screenshot(file: File) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("Could not capture the test screen")
        try {
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode screenshot" }
                stream.fd.sync()
            }
        } finally { bitmap.recycle() }
    }

    private fun waitUntil(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(40L)
        }
        error("Timed out waiting for $description")
    }

    /** Assertions must fail the runner, rather than crash the Activity's main looper. */
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun bounds(view: View): Rect {
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }

    private fun rectJson(rect: Rect) = JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label && root.visibility == View.VISIBLE) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findButton(root.getChildAt(index), label)?.let { return it }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        field.get(owner) as T
    }

    private fun progress(message: String) = sendStatus(1, Bundle().apply { putString("stream", "$message\n") })
}
