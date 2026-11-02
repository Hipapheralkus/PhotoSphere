package app.photosphere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.display.DisplayManager
import android.location.Location
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Camera2 owns JPEG production; this class never decodes or recompresses a photograph. */
class CameraEngine(context: Context) {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val displays = context.applicationContext.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: CameraRun? = null
    private var nextBrightnessSessionId = 0L
    @Volatile private var brightnessSession: BrightnessSession? = null

    private class BrightnessSession(val id: Long, val requested: Boolean) {
        @Volatile var acceptedPhotos = 0
        var lastResolvedTimestamp = 0L // Accessed only on main.
    }

    /** Called once after the new sphere's session record is durable. */
    fun beginCaptureSession(keepBrightnessSteady: Boolean): Unit = onMain {
        val settings = BrightnessSession(++nextBrightnessSessionId, keepBrightnessSteady)
        brightnessSession = settings
        current?.let { run -> run.handler.post { run.setCaptureSession(settings) } }
    }

    /** A rejected or unrecorded first photo must never establish the sphere's exposure. */
    fun resolveCapture(metadata: CaptureMetadata, accepted: Boolean): Unit = onMain {
        val settings = brightnessSession ?: return@onMain
        if (metadata.brightnessSessionId != settings.id) return@onMain
        // UNKNOWN timestamp sources may restart their clock after reopening the camera.
        if (metadata.sensorTimestampNs == settings.lastResolvedTimestamp) return@onMain
        settings.lastResolvedTimestamp = metadata.sensorTimestampNs
        if (accepted) settings.acceptedPhotos++
        current?.let { run -> run.handler.post { run.resolveCapture(metadata, accepted) } }
    }

    fun endCaptureSession(): Unit = onMain {
        brightnessSession = null
        current?.let { run -> run.handler.post { run.setCaptureSession(null) } }
    }

    val isReady: Boolean
        get() = current?.let { it.ready && !it.closing.get() && !it.busy.get() } == true

    /** Bounds of the complete camera frame inside the TextureView, excluding fit-centre bars. */
    fun previewContentBounds(): RectF? = current?.contentBounds?.let(::RectF)

    /** Refresh after an Activity configuration/layout change without reopening the capture session. */
    fun refreshPreviewTransform(): Unit = onMain { current?.refreshTransform() }

    fun open(
        view: TextureView,
        quality: CaptureQuality,
        onReady: (CameraInfo) -> Unit,
        onError: (String) -> Unit,
    ): Unit = onMain {
        close()
        val run = CameraRun(view, quality, onReady, onError)
        current = run
        view.isOpaque = false
        view.surfaceTextureListener = run.listener
        run.monitorDisplay()
        if (view.isAvailable) run.start()
    }

    fun capture(
        destination: File,
        location: Location?,
        onSaved: (CaptureMetadata) -> Unit,
        onError: (String) -> Unit,
    ) {
        val run = current
        if (run == null || !run.ready || run.closing.get()) {
            onMain { onError("The camera is not ready.") }
            return
        }
        if (!run.busy.compareAndSet(false, true)) {
            onMain { onError("A photograph is already being saved.") }
            return
        }
        val shot = Shot(destination, location?.let(::Location), onSaved, onError)
        if (!run.handler.post { run.beginShot(shot) }) {
            run.busy.set(false)
            shot.fail("The camera was closed before capture.")
        }
    }

    /** Captures awaiting the camera fail explicitly; a JPEG already being saved finishes safely. */
    fun close() {
        val run = current ?: return
        current = null
        run.preventReopen()
        run.shutdown("The camera was closed before the photograph was captured.")
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private data class Choice(val size: Size, val maximumMode: Boolean, val source: String)

    private inner class Shot(
        val destination: File,
        val location: Location?,
        val onSaved: (CaptureMetadata) -> Unit,
        val onError: (String) -> Unit,
    ) {
        val startedMs = SystemClock.elapsedRealtime()
        val completed = AtomicBoolean(false)
        var sent = false
        var saving = false
        var savedToDisk = false
        var threeATimedOut = false
        var orientation = 0
        var displayRotationAtCapture = 0
        var result: TotalCaptureResult? = null
        var brightnessSessionId: Long? = null
        var lockRetainedBeforeCapture = false
        val images = linkedMapOf<Long, ByteArray>()

        fun fail(message: String) {
            if (completed.compareAndSet(false, true)) onMain { onError(message) }
        }

        fun saved(metadata: CaptureMetadata) {
            if (completed.compareAndSet(false, true)) onMain { onSaved(metadata) }
        }
    }

    private inner class CameraRun(
        val view: TextureView,
        val quality: CaptureQuality,
        val onReady: (CameraInfo) -> Unit,
        val onError: (String) -> Unit,
    ) {
        val thread = HandlerThread("PhotoSphere-Camera").apply { start() }
        val handler = Handler(thread.looper)
        private val callbackExecutor = Executor { command -> handler.post(command) }
        private val saver = Executors.newSingleThreadExecutor { task ->
            Thread(task, "PhotoSphere-JPEG").apply { isDaemon = true }
        }
        val closing = AtomicBoolean(false)
        val busy = AtomicBoolean(false)
        @Volatile var ready = false
        private val started = AtomicBoolean(false)
        private val reportedError = AtomicBoolean(false)
        private var camera: CameraDevice? = null
        private var openPending = false
        private var autoReopen = false
        private var hasTextureFrame = false
        private var preparedInfo: CameraInfo? = null
        @Volatile var contentBounds: RectF? = null
        @Volatile private var displayRotationDegrees = 0
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private var surface: Surface? = null
        private lateinit var characteristics: CameraCharacteristics
        private lateinit var cameraId: String
        private lateinit var previewSize: Size
        private lateinit var choice: Choice
        private lateinit var choices: List<Choice>
        private var choiceIndex = 0
        private var attempt = 0
        private var previewBuilder: CaptureRequest.Builder? = null
        private var latestPreview: TotalCaptureResult? = null
        private var shot: Shot? = null
        private val notices = JSONArray()
        private val attempts = JSONArray()
        private lateinit var diagnostics: JSONObject
        private var requestedKeys = emptySet<CaptureRequest.Key<*>>()
        private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        private var captureSettings = brightnessSession
        private var exposurePolicy = ExposureLockPolicy(false, false, false)
        private var cameraRestartedWithinCaptureSession = false
        private var pendingDecisionTimestamp: Long? = null

        fun setCaptureSession(settings: BrightnessSession?) {
            if (closing.get()) return
            captureSettings = settings
            cameraRestartedWithinCaptureSession = settings?.acceptedPhotos?.let { it > 0 } == true
            exposurePolicy = ExposureLockPolicy(settings?.requested == true,
                ::characteristics.isInitialized && characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true && requestedKeys.contains(CaptureRequest.CONTROL_AE_LOCK),
                ::characteristics.isInitialized && characteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true && requestedKeys.contains(CaptureRequest.CONTROL_AWB_LOCK))
            pendingDecisionTimestamp = null
            updatePreviewLocks()
        }

        fun resolveCapture(metadata: CaptureMetadata, accepted: Boolean) {
            if (closing.get() || metadata.brightnessSessionId != captureSettings?.id ||
                metadata.sensorTimestampNs != pendingDecisionTimestamp) return
            pendingDecisionTimestamp = null
            val locks = JSONObject(metadata.cameraMetadataJson).getJSONObject("brightnessLock")
            if (exposurePolicy.resolve(accepted, locks.optBoolean("aeApplied"), locks.optBoolean("awbApplied")))
                updatePreviewLocks()
        }

        private fun applyExposureLocks(builder: CaptureRequest.Builder) {
            if (exposurePolicy.aeSupported) builder.set(CaptureRequest.CONTROL_AE_LOCK, exposurePolicy.aeLock)
            if (exposurePolicy.awbSupported) builder.set(CaptureRequest.CONTROL_AWB_LOCK, exposurePolicy.awbLock)
        }

        private fun updatePreviewLocks() {
            if (closing.get()) return
            val builder = previewBuilder ?: return
            try {
                applyExposureLocks(builder)
                session?.setRepeatingRequest(builder.build(), previewCallback, handler)
            } catch (error: Exception) {
                fatal("Could not update camera exposure: ${error.message}")
            }
        }

        private fun releaseCandidate() {
            pendingDecisionTimestamp = null
            if (exposurePolicy.resolve(false, false, false)) updatePreviewLocks()
        }

        private val displayListener: DisplayManager.DisplayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                // A 180-degree change can leave the TextureView dimensions unchanged.
                // Listen to the display as well as SurfaceTexture size callbacks.
                if (current === this@CameraRun && view.display?.displayId == displayId &&
                    displayRotationDegrees != (view.display?.rotation ?: Surface.ROTATION_0) * 90) {
                    refreshTransform()
                }
            }
        }

        fun monitorDisplay() { displays.registerDisplayListener(displayListener, main) }

        fun refreshTransform() {
            if (!closing.get()) transformPreview()
        }

        val listener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (autoReopen && current === this@CameraRun) open(view, quality, onReady, onError)
                else start()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                if (::previewSize.isInitialized) transformPreview()
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                autoReopen = current === this@CameraRun && !closing.get()
                shutdown("The camera preview closed before capture finished.", preserveListener = autoReopen)
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (!ready && !closing.get()) handler.post { hasTextureFrame = true; maybeReady() }
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            if (!started.compareAndSet(false, true) || closing.get()) return
            handler.post {
                try {
                    val all = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
                    val rear = all.filter { it.second[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
                    val selected = rear.maxByOrNull { (id, c) -> cameraScore(id, c) }
                        ?: error("No rear camera is available.")
                    cameraId = selected.first
                    characteristics = selected.second
                    requestedKeys = characteristics.availableCaptureRequestKeys.toSet()
                    setCaptureSession(captureSettings)
                    diagnostics = JSONObject().put("schemaVersion", 1)
                        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("sdk", Build.VERSION.SDK_INT).put("qualityRequested", quality.name)
                        .put("cameras", JSONArray().also { array -> all.forEach { (id, c) -> array.put(describeCamera(id, c)) } })
                        .put("notices", notices).put("configurationAttempts", attempts)
                    val map = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                        ?: error("The camera has no stream configuration map.")
                    val regular = (map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList())
                        .sortedByDescending(::area)
                    if (regular.isEmpty()) error("This rear camera does not expose JPEG still capture.")
                    val higher = if (quality == CaptureQuality.DETAIL) {
                        val maxMap = if (Build.VERSION.SDK_INT >= 31)
                            characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION] else null
                        val maxChoices = if (Build.VERSION.SDK_INT >= 31 && requestedKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE))
                            (maxMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()).map { Choice(it, true, "maximumResolution") }
                        else emptyList()
                        maxChoices + (map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList())
                            .map { Choice(it, false, "highResolution") }
                    } else emptyList()
                    choices = (higher + regular.map { Choice(it, false, "regular") })
                        .distinctBy { "${it.size}:${it.maximumMode}" }
                        .sortedWith(compareByDescending<Choice> { area(it.size) }.thenBy { it.maximumMode })
                    // Prefer the largest regular JPEG in FAST; never derive photos from preview buffers.
                    if (quality == CaptureQuality.DETAIL && area(choices.first().size) <= area(regular.first())) {
                        notices.put("Detail uses the same full-resolution JPEG as Fast: a larger capture size is not exposed by this camera.")
                    }
                    val targetRatio = regular.first().width.toDouble() / regular.first().height
                    previewSize = (map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList())
                        .filter { max(it.width, it.height) <= 1920 && min(it.width, it.height) <= 1080 }
                        .sortedWith(compareBy<Size> { abs(it.width.toDouble() / it.height - targetRatio) > .02 }
                            .thenByDescending(::area)).firstOrNull()
                        ?: error("The camera has no preview size at or below 1080p.")
                    val modes = characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
                    afMode = when {
                        modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                        else -> CaptureRequest.CONTROL_AF_MODE_OFF
                    }
                    onMain {
                        if (!closing.get() && current === this) {
                            transformPreview()
                            val texture = view.surfaceTexture
                            if (texture == null) fatal("The camera preview surface is unavailable.") else {
                                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                                val newSurface = Surface(texture)
                                if (!handler.post {
                                    if (closing.get()) newSurface.release() else {
                                        surface = newSurface
                                        try {
                                            openPending = true
                                            manager.openCamera(cameraId, deviceCallback, handler)
                                        } catch (e: Exception) {
                                            openPending = false
                                            fatal("Cannot open camera: ${e.message}")
                                        }
                                    }
                                }) newSurface.release()
                            }
                        }
                    }
                    handler.postDelayed({ if (!ready && !closing.get()) fatal("Opening the camera timed out.") }, 20_000)
                } catch (e: Exception) {
                    fatal("Camera setup failed: ${e.message}")
                }
            }
        }

        private val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                openPending = false
                if (closing.get()) { device.close(); thread.quitSafely() } else {
                    camera = device
                    configureNext()
                }
            }
            override fun onDisconnected(device: CameraDevice) {
                openPending = false
                device.close()
                if (closing.get()) thread.quitSafely() else fatal("The camera disconnected. Close and reopen capture.")
            }
            override fun onError(device: CameraDevice, error: Int) {
                openPending = false
                device.close()
                if (closing.get()) thread.quitSafely() else fatal("Camera error $error. Close and reopen capture.")
            }
        }

        private fun configureNext() {
            if (closing.get()) return
            if (choiceIndex >= choices.size) {
                fatal("No supported full-resolution JPEG and preview configuration could be opened.")
                return
            }
            val thisAttempt = ++attempt
            preparedInfo = null
            latestPreview = null
            hasTextureFrame = false
            choice = choices[choiceIndex++]
            reader?.close()
            val nextReader = ImageReader.newInstance(choice.size.width, choice.size.height, ImageFormat.JPEG, 2)
            reader = nextReader
            nextReader.setOnImageAvailableListener({ source -> receiveImages(source) }, handler)
            val attemptJson = JSONObject().put("size", sizeJson(choice.size)).put("source", choice.source)
            attempts.put(attemptJson)
            val outputs = listOf(OutputConfiguration(surface!!), OutputConfiguration(nextReader.surface).apply {
                if (Build.VERSION.SDK_INT >= 31 && choice.maximumMode)
                    addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            })
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (closing.get() || thisAttempt != attempt) { configured.close(); return }
                    session = configured
                    try {
                        previewBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface!!)
                            commonControls(this)
                        }
                        configured.setRepeatingRequest(previewBuilder!!.build(), previewCallback, handler)
                        attemptJson.put("configured", true)
                        if (choiceIndex > 1) notices.put("The requested larger JPEG configuration was unavailable; capture uses ${choice.size.width} × ${choice.size.height} original JPEGs.")
                        diagnostics.put("selected", JSONObject().put("cameraId", cameraId)
                            .put("jpeg", sizeJson(choice.size)).put("preview", sizeJson(previewSize))
                            .put("maximumResolutionMode", choice.maximumMode).put("source", choice.source)
                            .put("previewScale", "fitCenter").put("selection", "rear normal wide, logical/default camera preferred"))
                        val fov = portraitFov(characteristics, choice.size)
                        preparedInfo = CameraInfo(cameraId, choice.size.width, choice.size.height,
                            previewSize.width, previewSize.height, characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
                            fov.first, fov.second, choice.maximumMode, diagnostics.toString(2),
                            exposurePolicy.aeSupported, exposurePolicy.awbSupported)
                        // Wait for both a real preview result and a presented TextureView frame.
                    } catch (e: Exception) {
                        configured.close()
                        session = null
                        attemptJson.put("error", e.message)
                        handler.post { configureNext() }
                    }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    failed.close()
                    if (!closing.get() && thisAttempt == attempt) {
                        attemptJson.put("configured", false)
                        handler.post { configureNext() }
                    }
                }
            }
            try {
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, callbackExecutor, callback)
                val supported: Boolean? = try { camera!!.isSessionConfigurationSupported(config) }
                    catch (_: UnsupportedOperationException) { null }
                    catch (_: IllegalArgumentException) { false }
                attemptJson.put("supportQuery", supported ?: JSONObject.NULL)
                if (supported == false) handler.post { configureNext() }
                else camera!!.createCaptureSession(config)
            } catch (e: Exception) {
                attemptJson.put("error", e.message)
                handler.post { configureNext() }
            }
        }

        private fun commonControls(builder: CaptureRequest.Builder) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            applyExposureLocks(builder)
            if (Build.VERSION.SDK_INT >= 30 && requestedKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO))
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            if (requestedKeys.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (Build.VERSION.SDK_INT >= 31 && requestedKeys.contains(CaptureRequest.SCALER_ROTATE_AND_CROP))
                builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }

        private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (session !== this@CameraRun.session || closing.get()) return
                latestPreview = result
                maybeReady()
                val pending = shot
                if (pending != null && !pending.sent) prepareStill(pending, result)
            }
        }

        private fun maybeReady() {
            if (ready || closing.get() || !hasTextureFrame) return
            val prepared = preparedInfo ?: return
            val preview = latestPreview ?: return
            val fov = portraitFov(characteristics, choice.size, preview[CaptureResult.LENS_FOCAL_LENGTH],
                preview[CaptureResult.SCALER_CROP_REGION])
            diagnostics.getJSONObject("selected")
                .put("observedPreviewFocalLengthMm", preview[CaptureResult.LENS_FOCAL_LENGTH])
                .put("observedPreviewCropRegion", rectJson(preview[CaptureResult.SCALER_CROP_REGION]))
                .put("observedPhysicalCameraId", preview[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID])
                .put("horizontalFovDegrees", fov.first).put("verticalFovDegrees", fov.second)
                .put("fovIsEstimate", true)
            val info = prepared.copy(horizontalFov = fov.first, verticalFov = fov.second, diagnosticsJson = diagnostics.toString(2))
            ready = true
            onMain { if (!closing.get() && current === this) onReady(info) }
        }

        private fun threeAReady(result: CaptureResult?): Boolean {
            if (result == null) return false
            val af = result[CaptureResult.CONTROL_AF_STATE]
            val ae = result[CaptureResult.CONTROL_AE_STATE]
            val focusReady = afMode == CaptureRequest.CONTROL_AF_MODE_OFF || af == null ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            val exposureReady = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            return focusReady && exposureReady
        }

        private fun aeApplied(result: CaptureResult?): Boolean = result?.get(CaptureResult.CONTROL_AE_LOCK) == true
        private fun awbApplied(result: CaptureResult?): Boolean = result?.get(CaptureResult.CONTROL_AWB_LOCK) == true

        private fun prepareStill(pending: Shot, result: CaptureResult?) {
            if (pending.sent || result == null || !threeAReady(result)) return
            val ae = result[CaptureResult.CONTROL_AE_STATE]
            val awb = result[CaptureResult.CONTROL_AWB_STATE]
            val aeSettled = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            val awbSettled = awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
            if (exposurePolicy.prepare(aeSettled, awbSettled)) {
                // Wait for a result from the locked preview, not merely the submitted request.
                updatePreviewLocks()
                return
            }
            if (exposurePolicy.confirmed(aeApplied(result), awbApplied(result))) sendStill(pending)
        }

        fun beginShot(pending: Shot) {
            if (closing.get() || !ready || session == null) {
                busy.set(false)
                pending.fail("The camera closed before capture.")
                return
            }
            shot = pending
            pending.brightnessSessionId = captureSettings?.id
            if (pending.destination.exists()) {
                failShot(pending, "The destination already exists; no photograph was overwritten.")
                return
            }
            if (afMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                try {
                    val trigger = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface!!)
                        commonControls(this)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }
                    session!!.capture(trigger.build(), previewCallback, handler)
                } catch (e: Exception) { failShot(pending, "Could not start focus: ${e.message}"); return }
            } else prepareStill(pending, latestPreview)
            handler.postDelayed({
                if (shot === pending && !pending.sent && !pending.completed.get()) {
                    pending.threeATimedOut = true
                    if (exposurePolicy.settleTimedOut(aeApplied(latestPreview), awbApplied(latestPreview))) updatePreviewLocks()
                    sendStill(pending)
                }
            }, 1_200)
            handler.postDelayed({
                if (shot === pending && !pending.saving && !pending.completed.get())
                    failShot(pending, "Capture timed out waiting for matching image and sensor metadata. Please retry.")
            }, if (choice.maximumMode) 30_000 else 15_000)
        }

        private fun sendStill(pending: Shot) {
            if (pending.sent || pending.completed.get() || closing.get() || shot !== pending) return
            pending.sent = true
            // AF/AE may have taken time while the user rotated the phone. Sample the current
            // display on main immediately before dispatch, not when the hold gate first fired.
            onMain {
                if (!closing.get() && !pending.completed.get()) {
                    transformPreview()
                    val rotation = (view.display?.rotation ?: Surface.ROTATION_0) * 90
                    if (!handler.post {
                        if (shot === pending && !pending.completed.get() && !closing.get())
                            submitStill(pending, rotation)
                    }) pending.fail("The camera closed before the photograph could be submitted.")
                }
            }
        }

        private fun submitStill(pending: Shot, rotation: Int) {
            try {
                pending.lockRetainedBeforeCapture = exposurePolicy.retained
                pending.displayRotationAtCapture = rotation
                pending.orientation = ((characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90) - rotation + 360) % 360
                val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    // Keep the repeating preview active. Max-resolution-only and default surfaces
                    // must not be mixed in a single maximum-resolution capture request.
                    addTarget(reader!!.surface)
                    commonControls(this)
                    set(CaptureRequest.JPEG_ORIENTATION, pending.orientation)
                    set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                    pending.location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
                    if (Build.VERSION.SDK_INT >= 31 && choice.maximumMode)
                        set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    setTag(pending)
                }.build()
                session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (shot !== pending || pending.completed.get()) return
                        if (result[CaptureResult.SENSOR_TIMESTAMP] == null) {
                            failShot(pending, "The camera did not return a sensor timestamp for this photograph.")
                            return
                        }
                        pending.result = result
                        pairAndSave(pending)
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        failShot(pending, "The camera failed to capture the photograph (reason ${failure.reason}).")
                    }
                    override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                        failShot(pending, "The camera aborted the photograph. Please retry.")
                    }
                    override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                        failShot(pending, "The camera lost the photograph buffer. Please retry.")
                    }
                }, handler)
            } catch (e: Exception) { failShot(pending, "Capture failed: ${e.message}") }
        }

        private fun receiveImages(source: ImageReader) {
            try {
                while (true) {
                    val image = source.acquireNextImage() ?: break
                    try {
                        val pending = shot
                        if (source === reader && pending != null && pending.sent && !pending.saving && !pending.completed.get()) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            pending.images[image.timestamp] = bytes
                            while (pending.images.size > 2) pending.images.remove(pending.images.keys.first())
                            pairAndSave(pending)
                        }
                    } finally { image.close() }
                }
            } catch (e: Exception) {
                if (!closing.get()) shot?.let { failShot(it, "Could not read the photograph: ${e.message}") }
            }
        }

        private fun pairAndSave(pending: Shot) {
            val result = pending.result ?: return
            val timestamp = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            val jpeg = pending.images.remove(timestamp) ?: return
            if (pending.saving || pending.completed.get()) return
            pending.images.clear()
            val outputSize = jpegSize(jpeg) ?: choice.size
            val metadataJson = try { frameMetadata(result, pending, outputSize).toString() }
            catch (e: Exception) {
                failShot(pending, "Could not preserve photograph metadata: ${e.message}")
                return
            }
            val base = CaptureMetadata(timestamp, result[CaptureResult.SENSOR_EXPOSURE_TIME],
                result[CaptureResult.SENSOR_SENSITIVITY], result[CaptureResult.LENS_FOCAL_LENGTH],
                result[CaptureResult.LENS_FOCUS_DISTANCE], pending.orientation, outputSize.width, outputSize.height,
                jpeg.size.toLong(), 0L, metadataJson, pending.brightnessSessionId)
            pendingDecisionTimestamp = timestamp
            pending.saving = true
            try {
                saver.execute {
                    var temp: File? = null
                    try {
                        val parent = pending.destination.absoluteFile.parentFile ?: error("No destination directory.")
                        if (!parent.exists() && !parent.mkdirs()) error("Cannot create photograph directory.")
                        temp = File.createTempFile(".photosphere-", ".jpg.partial", parent)
                        FileOutputStream(temp).use { stream -> stream.write(jpeg); stream.fd.sync() }
                        if (pending.destination.exists()) error("The destination appeared during capture; it was not overwritten.")
                        try {
                            Files.move(temp.toPath(), pending.destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), pending.destination.toPath())
                        }
                        temp = null
                        pending.savedToDisk = true
                        busy.set(false)
                        pending.saved(base.copy(durationMs = SystemClock.elapsedRealtime() - pending.startedMs))
                    } catch (e: Exception) {
                        temp?.delete()
                        busy.set(false)
                        pending.fail("Could not save the original photograph: ${e.message}")
                    } finally {
                        handler.post {
                            if (shot === pending) {
                                if (!pending.savedToDisk) releaseCandidate()
                                shot = null; resetFocus()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                pending.saving = false
                failShot(pending, "The photograph could not be queued for saving: ${e.message}")
            }
        }

        private fun failShot(pending: Shot, message: String) {
            if (pending.saving || pending.completed.get()) return
            pending.images.clear()
            if (shot === pending) { releaseCandidate(); shot = null; busy.set(false); resetFocus() }
            pending.fail(message)
        }

        private fun resetFocus() {
            if (closing.get() || afMode != CaptureRequest.CONTROL_AF_MODE_AUTO) return
            try {
                previewBuilder?.let { builder ->
                    applyExposureLocks(builder)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                    session?.capture(builder.build(), null, handler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
            } catch (_: Exception) { /* A closing camera already reports its lifecycle error. */ }
        }

        private fun frameMetadata(result: TotalCaptureResult, pending: Shot, encoded: Size): JSONObject {
            val json = JSONObject().put("cameraId", cameraId).put("quality", quality.name)
                .put("jpegSize", sizeJson(encoded)).put("configuredJpegSize", sizeJson(choice.size))
                .put("requestedJpegOrientation", pending.orientation).put("maximumResolutionMode", choice.maximumMode)
                .put("displayRotationDegreesAtCapture", pending.displayRotationAtCapture)
                .put("threeAWaitTimedOut", pending.threeATimedOut).put("gpsRequested", pending.location != null)
                .put("captureResultFrameNumber", result.frameNumber)
                .put("brightnessLock", JSONObject()
                    .put("requested", exposurePolicy.requested)
                    .put("aeSupported", exposurePolicy.aeSupported).put("awbSupported", exposurePolicy.awbSupported)
                    .put("aeRequested", result.request[CaptureRequest.CONTROL_AE_LOCK] == true)
                    .put("awbRequested", result.request[CaptureRequest.CONTROL_AWB_LOCK] == true)
                    .put("aeApplied", aeApplied(result)).put("awbApplied", awbApplied(result))
                    .put("retainedFromAcceptedPhoto", pending.lockRetainedBeforeCapture)
                    .put("reacquiredAfterCameraRestart", cameraRestartedWithinCaptureSession)
                    .put("fallbackReason", exposurePolicy.fallbackReason ?: JSONObject.NULL))
                .put("sensorTimestampNs", result[CaptureResult.SENSOR_TIMESTAMP])
                .put("sensorTimestampSource", characteristics[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE])
                .put("sensorOrientation", characteristics[CameraCharacteristics.SENSOR_ORIENTATION])
                .put("exposureTimeNs", result[CaptureResult.SENSOR_EXPOSURE_TIME])
                .put("frameDurationNs", result[CaptureResult.SENSOR_FRAME_DURATION])
                .put("rollingShutterSkewNs", result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW])
                .put("sensitivityIso", result[CaptureResult.SENSOR_SENSITIVITY])
                .put("focalLengthMm", result[CaptureResult.LENS_FOCAL_LENGTH])
                .put("focusDistanceDiopters", result[CaptureResult.LENS_FOCUS_DISTANCE])
                .put("aperture", result[CaptureResult.LENS_APERTURE])
                .put("aeState", result[CaptureResult.CONTROL_AE_STATE])
                .put("afState", result[CaptureResult.CONTROL_AF_STATE])
                .put("awbState", result[CaptureResult.CONTROL_AWB_STATE])
                .put("cropRegion", rectJson(result[CaptureResult.SCALER_CROP_REGION]))
                .put("requestCropRegion", rectJson(result.request[CaptureRequest.SCALER_CROP_REGION]))
                .put("intrinsicCalibration", floatJson(result[CaptureResult.LENS_INTRINSIC_CALIBRATION]))
                .put("lensDistortion", floatJson(result[CaptureResult.LENS_DISTORTION]))
                .put("distortionCorrectionMode", result[CaptureResult.DISTORTION_CORRECTION_MODE])
                .put("opticalStabilizationMode", result[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE])
                .put("activePhysicalCameraId", result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID])
                .put("sensorPhysicalSizeMm", characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.let {
                    JSONArray().put(it.width).put(it.height)
                })
            if (Build.VERSION.SDK_INT >= 30) json.put("zoomRatio", result[CaptureResult.CONTROL_ZOOM_RATIO])
            val maximum = Build.VERSION.SDK_INT >= 31 && choice.maximumMode
            json.put("activeArray", rectJson(if (maximum)
                characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION]
                else characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]))
            json.put("calibrationIntrinsics", floatJson(if (maximum)
                characteristics[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION_MAXIMUM_RESOLUTION]
                else characteristics[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]))
            json.put("calibrationDistortion", floatJson(if (maximum)
                characteristics[CameraCharacteristics.LENS_DISTORTION_MAXIMUM_RESOLUTION]
                else characteristics[CameraCharacteristics.LENS_DISTORTION]))
            result[CaptureResult.COLOR_CORRECTION_GAINS]?.let {
                json.put("colorCorrectionGains", JSONArray().put(it.red).put(it.greenEven).put(it.greenOdd).put(it.blue))
            }
            result[CaptureResult.COLOR_CORRECTION_TRANSFORM]?.let {
                json.put("colorCorrectionTransform", JSONArray().also { array ->
                    for (row in 0..2) for (column in 0..2) array.put(it.getElement(column, row).toDouble())
                })
            }
            return json
        }

        private fun transformPreview() {
            val rotation = (view.display?.rotation ?: Surface.ROTATION_0) * 90
            displayRotationDegrees = rotation
            if (!::previewSize.isInitialized || view.width == 0 || view.height == 0) return
            // TextureView already applies sensor orientation. Undo its nonuniform stretch,
            // then apply display rotation. Fit-centre keeps the entire capture framing visible.
            val sensor = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
            val naturalWidth = if (sensor % 180 == 0) previewSize.width else previewSize.height
            val naturalHeight = if (sensor % 180 == 0) previewSize.height else previewSize.width
            val rotatedWidth = if (rotation % 180 == 0) naturalWidth else naturalHeight
            val rotatedHeight = if (rotation % 180 == 0) naturalHeight else naturalWidth
            val fit = min(view.width.toFloat() / rotatedWidth, view.height.toFloat() / rotatedHeight)
            val cx = view.width / 2f
            val cy = view.height / 2f
            contentBounds = RectF(cx - rotatedWidth * fit / 2, cy - rotatedHeight * fit / 2,
                cx + rotatedWidth * fit / 2, cy + rotatedHeight * fit / 2)
            view.setTransform(Matrix().apply {
                setScale(naturalWidth * fit / view.width, naturalHeight * fit / view.height, cx, cy)
                postRotate(-rotation.toFloat(), cx, cy)
            })
        }

        private fun fatal(message: String) {
            if (closing.get()) return
            if (reportedError.compareAndSet(false, true)) onMain { if (current === this) onError(message) }
            shutdown(message)
        }

        fun preventReopen() {
            autoReopen = false
            onMain {
                displays.unregisterDisplayListener(displayListener)
                if (view.surfaceTextureListener === listener) view.surfaceTextureListener = null
            }
        }

        fun shutdown(reason: String, preserveListener: Boolean = false) {
            if (!closing.compareAndSet(false, true)) return
            ready = false
            if (!preserveListener) preventReopen()
            else onMain { displays.unregisterDisplayListener(displayListener) }
            handler.post {
                shot?.let { if (!it.saving) { it.images.clear(); it.fail(reason) } }
                shot = null
                try { session?.stopRepeating() } catch (_: Exception) { }
                try { session?.close() } catch (_: Exception) { }
                try { camera?.close() } catch (_: Exception) { }
                try { reader?.close() } catch (_: Exception) { }
                surface?.release()
                session = null; camera = null; reader = null; surface = null
                saver.shutdown()
                // Keep the callback looper alive while openCamera is in flight, so onOpened
                // can close the returned device rather than leaking it after a lifecycle race.
                if (!openPending) thread.quitSafely()
            }
        }
    }

    private fun cameraScore(id: String, c: CameraCharacteristics): Double {
        val logical = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        val physical = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val focalLengths = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] ?: floatArrayOf()
        val closestFov = focalLengths.map { focal ->
            if (physical == null || focal <= 0) 82.0 else Math.toDegrees(2 * atan(hypot(physical.width.toDouble(), physical.height.toDouble()) / (2 * focal)))
        }.minByOrNull { abs(it - 82.0) } ?: 82.0
        return (if (id == "0") 200.0 else 0.0) + (if (logical) 100.0 else 0.0) - abs(closestFov - 82.0)
    }

    private fun portraitFov(c: CameraCharacteristics, size: Size, observedFocal: Float? = null, crop: Rect? = null): Pair<Double, Double> {
        val physical = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return 50.0 to 65.0
        val focal = (observedFocal ?: c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull())?.toDouble()
            ?.takeIf { it > 0 } ?: return 50.0 to 65.0
        var width = physical.width.toDouble()
        var height = physical.height.toDouble()
        c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { active ->
            if (crop != null && active.width() > 0 && active.height() > 0) {
                width *= crop.width().toDouble() / active.width()
                height *= crop.height().toDouble() / active.height()
            }
        }
        val ratio = size.width.toDouble() / size.height
        if (width / height > ratio) width = height * ratio else height = width / ratio
        val horizontal = Math.toDegrees(2 * atan(width / (2 * focal)))
        val vertical = Math.toDegrees(2 * atan(height / (2 * focal)))
        return if ((c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90) % 180 == 0)
            horizontal to vertical else vertical to horizontal
    }

    private fun describeCamera(id: String, c: CameraCharacteristics): JSONObject {
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val maxMap = if (Build.VERSION.SDK_INT >= 31)
            c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION] else null
        return JSONObject().put("id", id).put("facing", c[CameraCharacteristics.LENS_FACING])
            .put("hardwareLevel", c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL])
            .put("sensorOrientation", c[CameraCharacteristics.SENSOR_ORIENTATION])
            .put("capabilities", JSONArray((c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()).toList()))
            .put("physicalCameraIds", JSONArray(c.physicalCameraIds.toList()))
            .put("focalLengthsMm", floatJson(c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]))
            .put("sensorPhysicalSizeMm", c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.let { JSONArray().put(it.width).put(it.height) })
            .put("activeArray", rectJson(c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]))
            .put("intrinsics", floatJson(c[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]))
            .put("distortion", floatJson(c[CameraCharacteristics.LENS_DISTORTION]))
            .put("jpegRegular", streamSizes(map, false))
            .put("jpegHighResolution", streamSizes(map, true))
            .put("jpegMaximumResolution", streamSizes(maxMap, false))
            .put("rawAvailable", map?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true)
            .put("aeLockAvailable", c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE])
            .put("awbLockAvailable", c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE])
    }

    private fun streamSizes(map: StreamConfigurationMap?, high: Boolean): JSONArray {
        if (map == null) return JSONArray()
        val sizes = (if (high) map.getHighResolutionOutputSizes(ImageFormat.JPEG) else map.getOutputSizes(ImageFormat.JPEG)) ?: emptyArray()
        return JSONArray().also { array -> sizes.sortedByDescending(::area).forEach { size ->
            array.put(sizeJson(size)
                .put("minFrameDurationNs", runCatching { map.getOutputMinFrameDuration(ImageFormat.JPEG, size) }.getOrNull())
                .put("stallDurationNs", runCatching { map.getOutputStallDuration(ImageFormat.JPEG, size) }.getOrNull()))
        } }
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height
    private fun sizeJson(size: Size) = JSONObject().put("width", size.width).put("height", size.height)
    private fun rectJson(rect: Rect?): Any = rect?.let { JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom) } ?: JSONObject.NULL
    private fun floatJson(values: FloatArray?): Any = values?.let { JSONArray(it.toList()) } ?: JSONObject.NULL

    /** Read only JPEG SOF markers, without allocating a bitmap or touching the compressed pixels. */
    private fun jpegSize(bytes: ByteArray): Size? {
        fun u(index: Int) = bytes[index].toInt() and 255
        if (bytes.size < 4 || u(0) != 255 || u(1) != 216) return null
        var i = 2
        while (i + 3 < bytes.size) {
            if (u(i) != 255) return null
            while (i < bytes.size && u(i) == 255) i++
            if (i >= bytes.size) return null
            val marker = u(i++)
            if (marker == 217 || marker == 218) return null
            if (marker == 1 || marker in 208..215) continue
            if (i + 1 >= bytes.size) return null
            val length = (u(i) shl 8) or u(i + 1)
            if (length < 2 || i + length > bytes.size) return null
            if (marker in listOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207) && length >= 7) {
                val height = (u(i + 3) shl 8) or u(i + 4)
                val width = (u(i + 5) shl 8) or u(i + 6)
                return if (width > 0 && height > 0) Size(width, height) else null
            }
            i += length
        }
        return null
    }
}
