package app.photosphere

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/** Native views and a direct preview keep the capture path small and responsive. */
class MainActivity : Activity() {
    private val ink = Color.rgb(12, 24, 21)
    private val card = Color.rgb(23, 42, 35)
    private val mint = Color.rgb(184, 243, 211)
    private val white = Color.rgb(245, 248, 246)
    private val muted = Color.rgb(159, 183, 171)
    private val amber = Color.rgb(255, 210, 140)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: SessionStore
    private lateinit var camera: CameraEngine
    private lateinit var tracker: OrientationTracker
    private val guidance = Guidance()
    private val retryPolicy = CaptureRetryPolicy()
    private val poseHistory = PoseHistory()
    private var screen = "home"
    private var quality = CaptureQuality.FAST
    private var overlap = CaptureOverlap.EFFICIENT
    private var keepBrightnessSteady = true
    private var locationEnabled = false
    private var lastLocation: Location? = null
    private var cameraInfo: CameraInfo? = null
    private var pose: Pose? = null
    private var plan = emptyList<CaptureTarget>()
    private var session: CaptureSession? = null
    private var targetIndex = 0
    private var manualTargetId: Int? = null
    private var retryAtMs = 0L
    private var pendingCameraError: String? = null
    private val completed = mutableSetOf<Int>()
    private var busy = false
    private var paused = false
    private var pauseVersion = 0
    private var activityResumed = false
    private var cameraOpening = false
    private var savedUntilMs = 0L
    private var lastTextUpdateMs = 0L
    private var sessionGeneration = 0
    private var exportSummary: SessionSummary? = null
    private var lastShotMs: Long? = null
    private var guidanceNotice: String? = null
    private var interrupted = false
    private var endingSession = false
    private lateinit var preview: TextureView
    private lateinit var overlay: CaptureOverlay
    private lateinit var map: SphereMapView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var counter: TextView
    private lateinit var resolution: TextView
    private lateinit var action: Button
    private lateinit var finish: Button
    private lateinit var directionAction: Button
    private lateinit var captureOptions: LinearLayout
    private lateinit var cameraRoot: FrameLayout
    private lateinit var previewPane: FrameLayout
    private lateinit var captureHeader: LinearLayout
    private lateinit var captureControls: LinearLayout
    private var displayRotation = Surface.ROTATION_0
    private var rotationGeneration = 0
    private var shellRoot: View? = null
    private val stitchObservers = mutableListOf<AutoCloseable>()
    private var savedSessionId: String? = null
    private var pendingStitch: Pair<String, StitchQuality>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        store = SessionStore(this)
        camera = CameraEngine(this)
        tracker = OrientationTracker(this, ::onPose)
        quality = if (getPreferences(0).getBoolean("detail", false)) CaptureQuality.DETAIL else CaptureQuality.FAST
        overlap = if (getPreferences(0).getBoolean("extraOverlap", false)) CaptureOverlap.EXTRA else CaptureOverlap.EFFICIENT
        keepBrightnessSteady = getPreferences(0).getBoolean("keepBrightnessSteady", true)
        locationEnabled = getPreferences(0).getBoolean("location", false) && hasLocationPermission()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        if (intent.hasExtra("stitchSessionId")) showSessions() else showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("stitchSessionId") && screen != "capture") showSessions()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (screen == "capture" && !interrupted) {
            startTracking()
            if (preview.isAvailable) openCamera()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (screen) {
            "capture" -> {
                syncDisplayRotation()
                layoutCapture()
            }
            "home" -> showHome()
            "saved" -> showSaved(completed.size, plan.size)
            "sessions" -> showSessions()
        }
    }

    private fun syncDisplayRotation() {
        val next = display?.rotation ?: Surface.ROTATION_0
        tracker.setDisplayRotation(next)
        if (next != displayRotation) {
            displayRotation = next
            rotationGeneration++
            guidance.reset()
            poseHistory.clear()
        }
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onPause() {
        activityResumed = false
        if (screen == "capture") {
            retryAtMs = 0L
            pauseVersion++
            if (session != null || busy) LocalDiagnostics.record(this, "capture_interrupted", session?.id, mapOf("busy" to busy, "savedCount" to completed.size))
            interrupted = session != null || busy
            paused = session != null
            guidance.reset()
            tracker.stop()
            camera.close()
            cameraOpening = false
            if (paused && ::action.isInitialized) action.text = "Resume capture"
            // A stopped orientation sensor may acquire a different reference. Preserve a partial
            // session instead of silently attaching new photos to an untrustworthy coordinate frame.
            if (session != null && !busy) finishSession()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stitchObservers.forEach { it.close() }
        stitchObservers.clear()
        sessionGeneration++
        tracker.stop()
        camera.endCaptureSession(); camera.close()
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Used on Android 12 and earlier")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses OnBackInvokedDispatcher registered above.
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (screen == "capture") requestFinish()
        else if (screen != "home") showHome()
        else finish()
    }

    private fun installRoot(view: View) {
        stitchObservers.forEach { it.close() }
        stitchObservers.clear()
        shellRoot = view
        view.setOnApplyWindowInsetsListener { v, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(view)
        window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        view.requestApplyInsets()
    }

    private fun showHome() {
        screen = "home"
        tracker.stop()
        camera.endCaptureSession(); camera.close()
        cameraOpening = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val host = FrameLayout(this).apply { setBackgroundColor(ink) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = column(24)
        scroll.addView(body)
        host.addView(scroll)
        val brand = row()
        brand.addView(text("◉  PhotoSphere", 23, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(chip("EARLY ACCESS"))
        body.addView(brand)
        val landscape = isLandscape()
        body.addView(space(if (landscape) 18 else 22))
        val sections = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val intro = column(0)
        val choices = column(0)
        sections.addView(intro, if (landscape) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(28) } else LinearLayout.LayoutParams(-1, -2))
        sections.addView(choices, if (landscape) LinearLayout.LayoutParams(0, -2, 1.1f) else LinearLayout.LayoutParams(-1, -2))
        body.addView(sections)
        intro.addView(text("A whole place.\nOne memory.", if (landscape) 31 else 39, white, true).apply { letterSpacing = -0.035f; setLineSpacing(0f, 1.02f) })
        intro.addView(space(12))
        intro.addView(text("Keep the camera lens over one spot.\nMove yourself around it.", if (landscape) 15 else 17, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        intro.addView(OrbitIllustration(this), LinearLayout.LayoutParams(-1, dp(if (landscape) 128 else 186)))
        choices.addView(button("Start a sphere", true) { startRequested() }, LinearLayout.LayoutParams(-1, dp(if (landscape) 52 else 58)))
        choices.addView(space(10))
        choices.addView(button("Gallery", false) { showSessions() }, LinearLayout.LayoutParams(-1, dp(52)))
        choices.addView(space(if (landscape) 10 else 18))
        val loc = Switch(this).apply {
            text = "Save this place’s location"
            contentDescription = "Save GPS location with photos. Optional."
            setTextColor(white); textSize = 16f
            isChecked = locationEnabled
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !hasLocationPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
                } else {
                    locationEnabled = checked
                    getPreferences(0).edit().putBoolean("location", checked).apply()
                }
            }
        }
        choices.addView(loc)
        choices.addView(text("Optional. The location stays with your photos.", 13, muted))
        choices.addView(space(12))
        choices.addView(button("Capture options", false) { showCaptureOptions() }.apply { textSize = 14f }, LinearLayout.LayoutParams(-1, dp(48)))
        choices.addView(space(14))
        choices.addView(text("ALL ON YOUR PHONE", 10, mint, true).apply { letterSpacing = .16f; gravity = Gravity.CENTER })
        choices.addView(space(7))
        choices.addView(text("Capture. Create your sphere. Look around.", 12, muted).apply { gravity = Gravity.CENTER })
        body.addView(space(12))
        installRoot(host)
    }

    private fun showCaptureOptions() {
        val body = column(22)
        body.addView(text("Ready for everyday moments. These extras are optional.", 15, muted))
        body.addView(space(14))
        body.addView(Switch(this).apply {
            text = "Keep brightness steady"; textSize = 17f; setTextColor(white); minHeight = dp(52)
            isChecked = keepBrightnessSteady
            setOnCheckedChangeListener { _, checked ->
                keepBrightnessSteady = checked
                getPreferences(0).edit().putBoolean("keepBrightnessSteady", checked).apply()
            }
        })
        body.addView(text("Uses the first good photo to keep brightness and color more consistent. Turn off for automatic adjustments. Unsupported cameras stay automatic.", 13, muted))
        body.addView(space(16))
        body.addView(Switch(this).apply {
            text = "More detail"; textSize = 17f; setTextColor(white); minHeight = dp(52)
            isChecked = quality == CaptureQuality.DETAIL
            setOnCheckedChangeListener { _, checked ->
                quality = if (checked) CaptureQuality.DETAIL else CaptureQuality.FAST
                getPreferences(0).edit().putBoolean("detail", checked).apply()
            }
        })
        body.addView(text("Larger originals when your camera supports them. Saving can take longer.", 13, muted))
        body.addView(space(16))
        body.addView(Switch(this).apply {
            text = "Extra overlap"; textSize = 17f; setTextColor(white); minHeight = dp(52)
            isChecked = overlap == CaptureOverlap.EXTRA
            setOnCheckedChangeListener { _, checked ->
                overlap = if (checked) CaptureOverlap.EXTRA else CaptureOverlap.EFFICIENT
                getPreferences(0).edit().putBoolean("extraOverlap", checked).apply()
            }
        })
        body.addView(text("More stops can help in plain rooms and tricky scenes. Leave off for a quicker capture.", 13, muted))
        AlertDialog.Builder(this).setTitle("Capture options").setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Done", null).show()
    }

    private fun startRequested() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 20)
        } else showCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCapture()
            else AlertDialog.Builder(this).setTitle("Let’s open the camera")
                .setMessage("PhotoSphere needs camera access to capture your surroundings. You can enable it in app settings.")
                .setPositiveButton("App settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                .setNegativeButton("Not now", null).show()
        } else if (requestCode == 21) {
            locationEnabled = hasLocationPermission()
            getPreferences(0).edit().putBoolean("location", locationEnabled).apply()
            showHome()
        } else if (requestCode == 22) {
            pendingStitch?.let { (id, quality) -> launchStitch(id, quality) }
            pendingStitch = null
        }
    }

    private fun showCapture() {
        screen = "capture"
        sessionGeneration++
        session = null; targetIndex = 0; completed.clear(); paused = false; busy = false
        manualTargetId = null; retryAtMs = 0L; retryPolicy.reset()
        pendingCameraError = null
        cameraInfo = null; pose = null; guidanceNotice = null; lastShotMs = null
        interrupted = false; endingSession = false
        poseHistory.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val host = FrameLayout(this).apply { setBackgroundColor(ink) }
        cameraRoot = FrameLayout(this)
        host.addView(cameraRoot, FrameLayout.LayoutParams(-1, -1))
        previewPane = FrameLayout(this)
        cameraRoot.addView(previewPane, FrameLayout.LayoutParams(-1, -1))
        preview = TextureView(this)
        previewPane.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = CaptureOverlay(this)
        previewPane.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        captureHeader = column(20).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE60C1815.toInt(), 0x000C1815))
        }
        val header = row()
        header.addView(button("‹", false) { requestFinish() }.apply { contentDescription = "Save and leave capture" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = column(0).apply { gravity = Gravity.CENTER }
        heading.addView(text("YOUR SPHERE", 10, mint, true).apply { letterSpacing = .15f; gravity = Gravity.CENTER })
        counter = text("Getting ready", 18, white, true).apply { gravity = Gravity.CENTER }
        heading.addView(counter)
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("···", false) { showCameraDetails() }.apply { contentDescription = "Camera details" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        captureHeader.addView(header)
        captureHeader.addView(space(10))
        resolution = text("Checking your camera…", 12, muted).apply { gravity = Gravity.CENTER }
        captureHeader.addView(resolution)
        map = SphereMapView(this).apply {
            isEnabled = false
            onTargetSelected = ::selectDirection
            onChooseDirection = ::showDirectionChooser
        }
        previewPane.addView(captureHeader, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        previewPane.addView(map, FrameLayout.LayoutParams(-1, dp(132), Gravity.TOP))
        captureControls = column(24).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0C1815.toInt(), 0xEB0C1815.toInt(), 0x000C1815))
            setPadding(dp(24), dp(50), dp(24), dp(22))
        }
        statusTitle = text("Let’s find your starting point", 27, white, true).apply { gravity = Gravity.CENTER }
        statusDetail = text("Keep the camera lens over one spot. Move yourself around it.", 15, muted).apply { gravity = Gravity.CENTER; minHeight = dp(48); setLineSpacing(dp(2).toFloat(), 1f) }
        captureControls.addView(statusTitle)
        captureControls.addView(space(10))
        captureControls.addView(statusDetail)
        captureControls.addView(space(17))
        action = button("Checking camera…", true) {
            primaryCaptureAction()
        }.apply { isEnabled = false }
        captureControls.addView(action, LinearLayout.LayoutParams(-1, dp(56)))
        captureOptions = row().apply { visibility = View.GONE }
        directionAction = button("Choose direction", false) {
            if (manualTargetId != null) followAim() else showDirectionChooser()
        }.apply { textSize = 13f }
        captureOptions.addView(directionAction, LinearLayout.LayoutParams(0, dp(48), 1.3f).apply { marginEnd = dp(8) })
        finish = button("Finish", false) { requestFinish() }
        captureOptions.addView(finish, LinearLayout.LayoutParams(0, dp(48), 1f))
        captureControls.addView(captureOptions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        cameraRoot.addView(captureControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        cameraRoot.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layoutCapture()
        }
        previewPane.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitGuidanceToPreview() }
        captureControls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayInsets() }
        captureHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionMap()
            updateOverlayInsets()
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) { if (activityResumed) openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { camera.close(); cameraOpening = false; return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        installRoot(host)
        cameraRoot.post { layoutCapture() }
        if (activityResumed) startTracking()
        refreshLocation()
    }

    private fun startTracking() {
        syncDisplayRotation()
        if (!tracker.start()) {
            guidanceNotice = "This phone needs an orientation sensor for guided capture."
            statusTitle.text = "Guidance unavailable"
            statusDetail.text = guidanceNotice
            action.isEnabled = false
        }
    }

    /** Resize existing views: keeping the TextureView attached preserves the camera session. */
    private fun layoutCapture() {
        if (screen != "capture" || !::previewPane.isInitialized || cameraRoot.width == 0) return
        val landscape = isLandscape()
        val controlsWidth = if (landscape) (cameraRoot.width * .39f).roundToInt().coerceIn(dp(260), dp(340)) else cameraRoot.width
        previewPane.layoutParams = FrameLayout.LayoutParams(if (landscape) cameraRoot.width - controlsWidth else -1, -1, Gravity.TOP or Gravity.LEFT)
        captureControls.layoutParams = FrameLayout.LayoutParams(if (landscape) controlsWidth else -1, if (landscape) -1 else -2,
            if (landscape) Gravity.RIGHT else Gravity.BOTTOM)
        captureControls.gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.TOP
        captureControls.setPadding(dp(20), dp(if (landscape) 16 else 50), dp(20), dp(if (landscape) 16 else 22))
        captureControls.background = if (landscape) rounded(ink, 0) else
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0C1815.toInt(), 0xEB0C1815.toInt(), 0x000C1815))
        captureHeader.setPadding(dp(16), dp(if (landscape) 10 else 20), dp(16), dp(10))
        resolution.visibility = if (landscape) View.GONE else View.VISIBLE
        captureHeader.getChildAt(1).visibility = if (landscape) View.GONE else View.VISIBLE
        statusTitle.textSize = if (landscape) 24f else 27f
        statusDetail.textSize = if (landscape) 14f else 15f
        action.layoutParams = LinearLayout.LayoutParams(-1, dp(if (landscape) 52 else 56))
        positionMap()
        previewPane.post { camera.refreshPreviewTransform(); fitGuidanceToPreview() }
    }

    private fun positionMap() {
        if (screen != "capture" || !::map.isInitialized) return
        val landscape = isLandscape()
        val params = FrameLayout.LayoutParams(-1, dp(if (landscape) 90 else 132), if (landscape) Gravity.BOTTOM else Gravity.TOP).apply {
            leftMargin = dp(16); rightMargin = dp(16)
            topMargin = if (landscape) 0 else captureHeader.bottom
            bottomMargin = if (landscape) dp(8) else 0
        }
        val old = map.layoutParams as? FrameLayout.LayoutParams
        if (old?.height != params.height || old.gravity != params.gravity || old.topMargin != params.topMargin || old.leftMargin != params.leftMargin) {
            map.layoutParams = params
            map.post { updateOverlayInsets() }
        }
    }

    private fun fitGuidanceToPreview() {
        if (screen != "capture" || cameraInfo == null) return
        camera.refreshPreviewTransform()
        val bounds = camera.previewContentBounds() ?: return
        val w = bounds.width().roundToInt(); val h = bounds.height().roundToInt()
        if (w <= 0 || h <= 0) return
        val old = overlay.layoutParams as FrameLayout.LayoutParams
        if (old.width != w || old.height != h) {
            overlay.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
        overlay.post { updateOverlayInsets() }
    }

    private fun updateOverlayInsets() {
        if (screen != "capture" || !::overlay.isInitialized || overlay.height == 0) return
        val topEdge = if (isLandscape()) captureHeader.bottom else map.bottom
        val bottomEdge = if (isLandscape()) map.top else captureControls.top
        overlay.setOcclusionInsets(max(0, topEdge - overlay.top).toFloat(), max(0, overlay.bottom - bottomEdge).toFloat())
    }

    private fun openCamera() {
        if (cameraOpening || camera.isReady || screen != "capture" || !activityResumed || interrupted || endingSession) return
        cameraOpening = true
        val generation = sessionGeneration
        camera.open(preview, quality, { info ->
            if (generation != sessionGeneration || screen != "capture") return@open
            cameraOpening = false
            pendingCameraError = null
            cameraInfo = info
            fitGuidanceToPreview()
            if (session == null) {
                plan = CapturePlan.generate(info.horizontalFov, info.verticalFov, overlap)
                counter.text = "${plan.size} stops · any order"
                map.setPlan(plan.map { it.yaw to it.pitch }, completed, 0)
                action.text = "Start here"
                statusTitle.text = "Let’s find your starting point"
                statusDetail.text = "Keep the camera lens over one spot. Move yourself around it."
                if (keepBrightnessSteady && !info.aeLockSupported)
                    statusDetail.append(" This camera keeps brightness automatic.")
            } else action.text = if (paused) "Resume capture" else "Pause capture"
            if (session != null && paused) {
                statusTitle.text = "Ready when you are"
                statusDetail.text = "Keep the camera lens over one spot. Move yourself around it when you resume."
            }
            resolution.text = String.format(Locale.US, "%.1f MP originals  ·  %s", info.megapixels, if (quality == CaptureQuality.FAST) "Everyday" else "More detail")
            action.isEnabled = guidanceNotice == null
        }, { error ->
            if (generation != sessionGeneration || screen != "capture") return@open
            cameraOpening = false
            if (session != null) {
                // Camera shutdown can report both an overall error and a shot callback.
                // Keep the shot's durable write/record as the single owner of busy until it ends.
                if (busy) { pendingCameraError = error; return@open }
                captureFailed(error, CaptureRetryPolicy.classifyCameraMessage(error), generation)
                return@open
            }
            paused = true
            statusTitle.text = "Camera needs a moment"
            statusDetail.text = error
            action.text = "Try camera again"
            action.isEnabled = true
        })
    }

    private fun primaryCaptureAction() {
        if (busy) return
        // The visible Pause action keeps its meaning even while an automatic camera reopen waits.
        if (session != null && !paused) { togglePause(); return }
        if (!camera.isReady) {
            if (session != null) { paused = false; retryPolicy.reset(); retryAtMs = 0L; guidance.reset() }
            action.isEnabled = false
            camera.close()
            cameraOpening = false
            openCamera()
        } else if (session == null) beginSession() else togglePause()
    }

    private fun beginSession() {
        val info = cameraInfo ?: return
        if (pose == null || busy) {
            statusTitle.text = "Finding your direction"
            statusDetail.text = "Waiting for the motion sensors."
            return
        }
        tracker.resetYawAnchor()
        guidance.reset()
        busy = true
        action.isEnabled = false
        val generation = sessionGeneration
        val gps = currentCaptureLocation()
        io.execute {
            try {
                val created = store.create(info, plan, quality, gps)
                main.post {
                    if (generation != sessionGeneration || screen != "capture") return@post
                    session = created
                    camera.beginCaptureSession(keepBrightnessSteady)
                    LocalDiagnostics.record(this, "capture_session_started", created.id, mapOf("targetCount" to plan.size, "quality" to quality.name, "overlap" to overlap.name,
                        "keepBrightnessSteady" to keepBrightnessSteady, "aeLockSupported" to info.aeLockSupported, "awbLockSupported" to info.awbLockSupported))
                    busy = false; paused = false
                    if (!activityResumed || interrupted) { interrupted = true; finishSession(); return@post }
                    action.text = "Pause capture"; action.isEnabled = true
                    captureOptions.visibility = View.VISIBLE
                    map.isEnabled = true
                    updateProgress()
                }
            } catch (e: Exception) { main.post {
                if (generation != sessionGeneration || screen != "capture" || isDestroyed) return@post
                busy = false; action.isEnabled = true; showError("Couldn’t start capture", e)
            } }
        }
    }

    private fun togglePause() {
        if (busy) return
        paused = !paused
        pauseVersion++
        retryAtMs = 0L
        if (!paused) retryPolicy.reset()
        guidance.reset()
        action.text = if (paused) "Resume capture" else "Pause capture"
        statusTitle.text = if (paused) "Take your time" else "Find the next light"
        statusDetail.text = if (paused) "Your photos are safe. Keep the camera lens over one spot. Move yourself around it." else "Move gently around the lens. Stop when the two circles meet."
        if (paused) overlay.update(0.0, 0.0, 0.0, 0.0, "paused")
    }

    private fun onPose(value: Pose) {
        // Also catches a 180° rotation, where Android may keep the same configuration size.
        if (screen == "capture" && display?.rotation != displayRotation) {
            syncDisplayRotation()
            fitGuidanceToPreview()
            return
        }
        pose = value
        poseHistory.add(value)
        if (screen != "capture" || !activityResumed || session == null || paused || busy || completed.size >= plan.size) return
        val now = SystemClock.elapsedRealtime()
        if (retryAtMs > 0L) {
            if (now < retryAtMs) return
            retryAtMs = 0L
            guidance.reset() // Retry never reuses a partly filled hold ring: earn a fresh 650 ms hold.
            if (!camera.isReady) {
                camera.close(); cameraOpening = false; openCamera()
                return
            }
        }
        if (!camera.isReady || cameraOpening) return
        if (now < savedUntilMs) return
        val target = manualTargetId?.let { id -> plan.firstOrNull { it.id == id && id !in completed } }
            ?: TargetSelector.select(value, plan, completed, targetIndex) ?: return
        if (target.id != targetIndex) {
            targetIndex = target.id
            guidance.reset()
            updateProgress()
        }
        val state = guidance.evaluate(value, target, now)
        val info = cameraInfo ?: return
        val roll = if (abs(target.pitch) >= 75) 0.0 else value.roll
        val holding = state.sensorsFresh && state.aligned && state.level && state.still && (state.captureSafe || state.settling)
        val phase = if (holding) "hold" else "aim"
        val sideways = displayRotation % 2 != 0
        overlay.update(state.deltaYaw, state.deltaPitch, roll, state.holdProgress, phase,
            if (sideways) info.verticalFov else info.horizontalFov,
            if (sideways) info.horizontalFov else info.verticalFov, value.pitch, value.roll)
        if (now - lastTextUpdateMs >= 100) {
            lastTextUpdateMs = now
            when {
                !state.sensorsFresh -> {
                    statusTitle.text = "Waiting for motion sensors"
                    statusDetail.text = "Hold still for a moment before the next photo."
                }
                holding -> {
                    statusTitle.text = "Hold here"
                    statusDetail.text = "Keep the circles together while the ring fills."
                }
                state.angularError > 3.0 -> {
                    statusTitle.text = if (state.angularError <= 6.0) "Almost there" else directionText(state.deltaYaw, state.deltaPitch, target.pitch)
                    statusDetail.text = if (manualTargetId != null) "Your chosen direction. Follow my aim lets you change freely." else "Point anywhere, or tap the map to choose a different light."
                }
                abs(wrapDegrees(roll)) > 4.0 -> {
                    statusTitle.text = "Level your phone"
                    statusDetail.text = "Gently rotate it until the small line is level."
                }
                value.angularSpeed > 3.0 -> {
                    statusTitle.text = "You’re in the right spot"
                    statusDetail.text = "Now stop moving. We’ll take it from here."
                }
                else -> {
                    statusTitle.text = directionText(state.deltaYaw, state.deltaPitch, target.pitch)
                    statusDetail.text = if (manualTargetId != null) "Your chosen direction. Bring its light into the center." else "Point anywhere, or tap the map to choose a different light."
                }
            }
        }
        if (state.ready) capture(target, value)
    }

    private fun capture(target: CaptureTarget, shutterPose: Pose) {
        val current = session ?: return
        if (busy || paused || !activityResumed || interrupted || endingSession || !camera.isReady) return
        busy = true
        guidance.markCaptureStarted()
        action.isEnabled = false; finish.isEnabled = false
        directionAction.isEnabled = false; map.isEnabled = false
        statusTitle.text = "Taking your photo…"
        statusDetail.text = "Keep still for one more moment."
        overlay.update(0.0, 0.0, 0.0, 1.0, "capture")
        val generation = sessionGeneration
        val shutterRotationGeneration = rotationGeneration
        val location = lastLocation?.takeIf { locationEnabled && locationIsFresh(it) }
        io.execute {
            try {
                val destination = store.allocatePhoto(current, target)
                main.post {
                    if (generation != sessionGeneration || screen != "capture" || !activityResumed) {
                        busy = false; guidance.reset()
                        if (interrupted && session != null) finishSession()
                        return@post
                    }
                    camera.capture(destination, location, saved@{ metadata ->
                        if (isDestroyed || io.isShutdown) return@saved
                        val frame = org.json.JSONObject(metadata.cameraMetadataJson)
                        val association = poseHistory.associate(shutterPose, metadata.sensorTimestampNs, frame.optInt("sensorTimestampSource", 0) == 1)
                        frame.put("poseAssociation", association.source)
                        frame.put("poseTimestampOffsetNs", association.offsetNs ?: org.json.JSONObject.NULL)
                        frame.put("captureRequestPoseTimestampNs", shutterPose.timestampNs)
                        val exposureGate = Guidance().evaluate(association.pose, target, association.pose.timestampNs / 1_000_000L)
                        val accepted = shutterRotationGeneration == rotationGeneration && (association.offsetNs == null || exposureGate.captureSafe)
                        frame.put("exposurePoseAccepted", accepted)
                        val associatedMetadata = metadata.copy(cameraMetadataJson = frame.toString())
                        io.execute {
                            try {
                                store.recordCapture(current, target, association.pose, associatedMetadata, destination, location?.asCaptureLocation(), association.samples, accepted)
                                main.post {
                                    if (generation != sessionGeneration || screen != "capture") return@post
                                    camera.resolveCapture(metadata, accepted)
                                    if (!accepted) {
                                        LocalDiagnostics.record(this, "capture_rejected", current.id, mapOf("targetId" to target.id, "reason" to "exposure_motion_or_rotation"))
                                        val deferred = pendingCameraError
                                        if (deferred != null && CaptureRetryPolicy.classifyCameraMessage(deferred) == CaptureFailureKind.PERMANENT)
                                            captureFailed(deferred, CaptureFailureKind.PERMANENT, generation)
                                        else captureFailed("The phone moved during that photo.", CaptureFailureKind.MOTION, generation)
                                        return@post
                                    }
                                    completed.add(target.id)
                                    LocalDiagnostics.record(this, "capture_saved", current.id, mapOf("targetId" to target.id, "savedCount" to completed.size, "durationMs" to metadata.durationMs))
                                    if (manualTargetId == target.id) manualTargetId = null
                                    retryPolicy.reset(); retryAtMs = 0L
                                    busy = false
                                    lastShotMs = metadata.durationMs
                                    savedUntilMs = SystemClock.elapsedRealtime() + 420
                                    guidance.reset()
                                    action.isEnabled = true; finish.isEnabled = true
                                    directionAction.isEnabled = true; map.isEnabled = true
                                    statusTitle.text = "Got it ✓"
                                    statusDetail.text = "Keep the lens over the same spot as you turn."
                                    val brightness = frame.optJSONObject("brightnessLock")
                                    if (keepBrightnessSteady && brightness?.optBoolean("aeApplied") != true)
                                        statusDetail.text = "Brightness is automatic for this photo. Keep the lens over the same spot."
                                    resolution.text = String.format(Locale.US, "%.1f MP originals  ·  %s", cameraInfo?.megapixels ?: 0.0,
                                        if (brightness?.optBoolean("aeApplied") == true) "Steady brightness" else "Automatic brightness")
                                    overlay.update(0.0, 0.0, 0.0, 1.0, "saved")
                                    overlay.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    updateProgress()
                                    if (completed.size == plan.size || interrupted) finishSession()
                                    else pendingCameraError?.let { captureFailed(it, CaptureRetryPolicy.classifyCameraMessage(it), generation) }
                                }
                            } catch (e: Exception) { main.post {
                                camera.resolveCapture(metadata, false)
                                captureFailed("The photo is on disk, but its session record couldn’t be saved. ${e.message}", CaptureFailureKind.PERMANENT, generation)
                            } }
                        }
                    }, { error ->
                        val cause = pendingCameraError ?: error
                        captureFailed(cause, CaptureRetryPolicy.classifyCameraMessage(cause), generation)
                    })
                }
            } catch (e: Exception) { main.post { captureFailed(e.message ?: "Couldn’t create the original file.", CaptureFailureKind.PERMANENT, generation) } }
        }
    }

    private fun captureFailed(error: String, kind: CaptureFailureKind, generation: Int) {
        if (isDestroyed || generation != sessionGeneration || screen != "capture" || endingSession) return
        val wasPaused = paused
        pendingCameraError = null
        busy = false
        guidance.reset()
        savedUntilMs = 0L
        if (interrupted && session != null) { finishSession(); return }
        action.isEnabled = true; finish.isEnabled = true
        directionAction.isEnabled = true; map.isEnabled = true
        val decision = retryPolicy.failed(kind)
        LocalDiagnostics.record(this, "capture_failure", session?.id, mapOf("kind" to kind.name, "attempt" to decision.attempt,
            "automaticRetry" to (!wasPaused && activityResumed && decision.retryDelayMs != null), "targetId" to targetIndex))
        if (!wasPaused && activityResumed && decision.retryDelayMs != null) {
            retryAtMs = SystemClock.elapsedRealtime() + decision.retryDelayMs
            action.text = "Pause capture"
            statusTitle.text = if (kind == CaptureFailureKind.MOTION) "Let’s get a steadier photo" else "Camera is catching up"
            statusDetail.text = if (kind == CaptureFailureKind.MOTION) "$error Keep the circles together; we’ll retry automatically." else "We’ll retry automatically. Your saved photos are safe."
            overlay.update(0.0, 0.0, 0.0, 0.0, "aim")
            return
        }
        retryAtMs = 0L
        paused = true
        pauseVersion++
        action.text = "Try this photo again"
        statusTitle.text = if (kind == CaptureFailureKind.PERMANENT) "Your originals are safe" else "Take a moment"
        statusDetail.text = if (kind == CaptureFailureKind.PERMANENT) error else "$error Three automatic retries didn’t finish this photo. Try again when you’re ready."
        overlay.update(0.0, 0.0, 0.0, 0.0, "paused")
    }

    private fun updateProgress() {
        counter.text = "${completed.size} of ${plan.size} photos"
        map.setPlan(plan.map { it.yaw to it.pitch }, completed, targetIndex.coerceAtMost(plan.lastIndex))
        map.setSelectionLocked(manualTargetId != null)
        directionAction.text = if (manualTargetId != null) "Follow my aim" else "Choose direction"
        directionAction.contentDescription = if (manualTargetId != null) "Cancel the selected direction and follow wherever I point" else "Choose the next uncaptured direction from a list"
        counter.contentDescription = "${completed.size} of ${plan.size} photographs captured"
    }

    private fun selectDirection(index: Int) {
        if (screen != "capture" || session == null || busy || interrupted || endingSession) return
        val target = plan.getOrNull(index)?.takeIf { it.id !in completed } ?: return
        manualTargetId = target.id; targetIndex = target.id
        LocalDiagnostics.record(this, "direction_selected", session?.id, mapOf("targetId" to target.id))
        guidance.reset(); savedUntilMs = 0L
        updateProgress()
        if (!paused) {
            statusTitle.text = "Your next direction is set"
            statusDetail.text = "Move toward its light. Follow my aim lets you choose freely again."
        }
        map.announceForAccessibility("Direction ${index + 1} selected. Move toward its light.")
    }

    private fun followAim() {
        if (screen != "capture" || session == null || busy || interrupted || endingSession) return
        manualTargetId = null
        LocalDiagnostics.record(this, "direction_follow_aim", session?.id)
        guidance.reset(); savedUntilMs = 0L
        updateProgress()
        if (!paused) {
            statusTitle.text = "Explore in any direction"
            statusDetail.text = "Point away from people or movement. We’ll choose the nearest remaining light."
        }
    }

    private fun showDirectionChooser() {
        if (screen != "capture" || session == null || busy || interrupted || endingSession) return
        val currentPose = pose
        val remaining = plan.filter { it.id !in completed }.sortedBy { target ->
            if (currentPose == null) target.id.toDouble() else {
                val p = Math.toRadians(currentPose.pitch); val tp = Math.toRadians(target.pitch)
                -sin(p) * sin(tp) - cos(p) * cos(tp) * cos(Math.toRadians(wrapDegrees(target.yaw - currentPose.yaw)))
            }
        }
        if (remaining.isEmpty()) return
        val wasPaused = paused
        val generation = sessionGeneration
        val pauseMarker = pauseVersion
        paused = true; guidance.reset()
        val labels = arrayOf("Follow my aim · choose freely") + remaining.map { target ->
            val height = when {
                target.pitch >= 75 -> "Straight up"
                target.pitch <= -75 -> "Straight down"
                target.pitch > 10 -> "Above eye level"
                target.pitch < -10 -> "Below eye level"
                else -> "Eye level"
            }
            val delta = wrapDegrees(target.yaw - (currentPose?.yaw ?: 0.0))
            val turn = if (abs(target.pitch) >= 75) "" else if (abs(delta) < 8) " · ahead" else " · ${abs(delta).roundToInt()}° ${if (delta > 0) "right" else "left"}"
            "${target.id + 1}. $height$turn"
        }
        AlertDialog.Builder(this).setTitle("Choose your next direction")
            .setItems(labels) { _, which ->
                if (generation == sessionGeneration && screen == "capture" && !interrupted) {
                    if (which == 0) followAim() else selectDirection(plan.indexOf(remaining[which - 1]))
                }
            }.setNegativeButton("Cancel", null).setOnDismissListener {
                if (generation == sessionGeneration && pauseMarker == pauseVersion && screen == "capture" && !interrupted && activityResumed) {
                    paused = wasPaused
                    guidance.reset()
                }
            }.show()
    }

    private fun directionText(yaw: Double, pitch: Double, targetPitch: Double): String = when {
        abs(pitch) > 16 && targetPitch > 75 -> "Look straight up"
        abs(pitch) > 16 && targetPitch < -75 -> "Look straight down"
        abs(pitch) > abs(yaw) -> if (pitch > 0) "Tilt a little higher" else "Tilt a little lower"
        else -> if (yaw > 0) "Turn gently right" else "Turn gently left"
    }

    private fun requestFinish() {
        if (busy) { toast("Finishing this photo. Just a moment."); return }
        if (session == null) { sessionGeneration++; showHome(); return }
        val wasPaused = paused
        val generation = sessionGeneration
        val pauseMarker = pauseVersion
        paused = true
        guidance.reset()
        val canCreate = completed.size >= 2
        fun restoreCapture() {
            if (generation == sessionGeneration && pauseMarker == pauseVersion && screen == "capture" && !interrupted && activityResumed) {
                paused = wasPaused; guidance.reset()
            }
        }
        AlertDialog.Builder(this).setTitle(if (canCreate) "Create your sphere now?" else "Save this capture?")
            .setMessage("${completed.size} original photos will stay safe." + if (canCreate) " We’ll stitch them into a Photo Sphere. Uncaptured areas will stay blank." else " Capture at least two directions to create a sphere.")
            .setPositiveButton(if (canCreate) "Save & create" else "Save & finish") { _, _ ->
                if (generation == sessionGeneration) finishSession(createSphere = canCreate)
            }
            .setNegativeButton("Keep going") { _, _ -> restoreCapture() }
            .setOnCancelListener { restoreCapture() }.show()
    }

    private fun finishSession(createSphere: Boolean = false) {
        if (isDestroyed || io.isShutdown) return
        val current = session ?: return
        if (endingSession) return
        endingSession = true
        val generateAfterFinish = createSphere || completed.size == plan.size
        val generation = sessionGeneration
        retryAtMs = 0L
        paused = true; busy = true
        tracker.stop(); camera.endCaptureSession(); camera.close(); cameraOpening = false
        io.execute {
            try {
                store.finish(current)
                main.post {
                    if (isDestroyed || generation != sessionGeneration) return@post
                    busy = false
                    endingSession = false
                    savedSessionId = current.id
                    LocalDiagnostics.record(this, "capture_session_finished", current.id, mapOf("savedCount" to completed.size,
                        "targetCount" to plan.size, "createSphere" to (generateAfterFinish && activityResumed && !interrupted)))
                    session = null
                    sessionGeneration++
                    showSaved(completed.size, plan.size)
                    if (generateAfterFinish && activityResumed && !interrupted) launchStitch(current.id, StitchQuality.FULL)
                }
            } catch (e: Exception) { main.post {
                if (isDestroyed || generation != sessionGeneration) return@post
                busy = false; endingSession = false
                statusTitle.text = "Your originals are still safe"
                statusDetail.text = "The session couldn’t be finalized. Try saving again."
                action.text = "Retry saving"; action.isEnabled = true
                action.setOnClickListener { finishSession(createSphere = generateAfterFinish) }
                finish.isEnabled = true
                showError("Couldn’t finish the session", e)
            } }
        }
    }

    private fun showSaved(count: Int, total: Int) {
        screen = "saved"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val host = FrameLayout(this).apply { setBackgroundColor(ink) }
        val content = column(28).apply { gravity = Gravity.CENTER }
        content.addView(text("✓", 70, mint, true).apply { gravity = Gravity.CENTER })
        content.addView(space(20))
        content.addView(text(if (count == total) "Every direction. Saved." else "Your moment is safe.", 31, white, true).apply { gravity = Gravity.CENTER })
        content.addView(space(14))
        content.addView(text("$count full-resolution originals\n${if (count == total) "All planned directions captured." else "$count of $total directions captured."}", 17, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(6).toFloat(), 1f) })
        content.addView(space(24))
        content.addView(text(if (interrupted) "Capture was interrupted, so we saved your originals. You can create a partial view or start a new sphere." else "Create an interactive Photo Sphere from your originals. Your photos stay safe on this phone.", 15, muted).apply { gravity = Gravity.CENTER })
        content.addView(space(34))
        content.addView(button(if (count >= 2) "Create your sphere" else "Gallery", true) {
            val id = savedSessionId
            if (count >= 2 && id != null) requestStitch(id) else showSessions()
        }, LinearLayout.LayoutParams(-1, dp(58)))
        content.addView(space(10))
        content.addView(button("Back to start", false) { showHome() }, LinearLayout.LayoutParams(-1, dp(52)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(content)
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        installRoot(host)
    }

    private data class GalleryEntry(val summary: SessionSummary, val result: PanoramaResult?, val state: StitchJobState?)

    private fun showSessions() {
        screen = "sessions"
        val host = FrameLayout(this).apply { setBackgroundColor(ink) }
        val scroll = ScrollView(this)
        val content = column(24)
        scroll.addView(content); host.addView(scroll)
        val navigation = row()
        navigation.addView(button("‹  Back", false) { showHome() }, LinearLayout.LayoutParams(dp(100), dp(48)))
        navigation.addView(space(0), LinearLayout.LayoutParams(0, 0, 1f))
        navigation.addView(button("New sphere", true) { startRequested() }, LinearLayout.LayoutParams(dp(140), dp(48)))
        content.addView(navigation)
        content.addView(space(22))
        content.addView(text("Gallery", 34, white, true))
        content.addView(space(8))
        content.addView(text("Step back into the moment.", 16, muted))
        content.addView(space(24))
        val loading = text("Finding your spheres…", 16, muted)
        content.addView(loading)
        installRoot(host)
        io.execute {
            try {
                val entries = store.listSessions().map { summary ->
                    GalleryEntry(summary, runCatching { StitchRepository.result(this, summary.id) }.getOrNull(),
                        runCatching { StitchRepository.state(this, summary.id) }.getOrNull())
                }.filter { it.summary.capturedCount > 0 || it.summary.recoveredOriginalCount > 0 || it.result != null || it.state?.isActive == true }
                main.post {
                    if (screen != "sessions" || shellRoot !== host || isDestroyed) return@post
                    content.removeView(loading)
                    val spheres = entries.filter { it.result != null || it.state?.isActive == true }
                        .sortedWith(compareByDescending<GalleryEntry> { it.state?.isActive == true }.thenByDescending { it.summary.createdAtMs })
                    val saved = entries.filter { it.result == null && it.state?.isActive != true }
                    if (spheres.isEmpty()) {
                        content.addView(OrbitIllustration(this), LinearLayout.LayoutParams(-1, dp(if (isLandscape()) 130 else 180)))
                        content.addView(text(if (saved.isEmpty()) "Your first sphere starts here." else "Your photos are ready for the next step.", 21, white, true))
                        content.addView(space(10))
                        content.addView(text(if (saved.isEmpty()) "Capture a place and it will appear here, ready to explore." else "Create a sphere from a saved capture below.", 15, muted))
                        content.addView(space(22))
                    } else addGalleryCards(content, spheres)
                    if (saved.isNotEmpty()) {
                        val savedBody = column(0).apply { visibility = if (spheres.isEmpty()) View.VISIBLE else View.GONE }
                        var built = false
                        fun populateSaved() {
                            if (!built) { built = true; addGalleryCards(savedBody, saved) }
                        }
                        if (savedBody.visibility == View.VISIBLE) populateSaved()
                        val savedToggle = button("Saved captures · ${saved.size}", false) {
                            populateSaved()
                            savedBody.visibility = if (savedBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }.apply { contentDescription = "Show or hide ${saved.size} saved captures that have not become spheres yet" }
                        content.addView(savedToggle, LinearLayout.LayoutParams(-1, dp(52)))
                        content.addView(text("Your originals, ready whenever you are.", 13, muted).apply { setPadding(0, dp(9), 0, dp(15)) })
                        content.addView(savedBody)
                    }
                    content.addView(space(10))
                    content.addView(text("Stored on this phone. Open a sphere to export a copy before uninstalling.", 13, muted))
                }
            } catch (e: Exception) { main.post {
                if (screen == "sessions" && shellRoot === host && !isDestroyed) loading.text = "Couldn’t open your gallery. Please try again."
            } }
        }
    }

    private fun addGalleryCards(parent: LinearLayout, entries: List<GalleryEntry>) {
        var pair: LinearLayout? = null
        entries.forEachIndexed { index, entry ->
            val item = column(16).apply { background = rounded(card, 22) }
            val date = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(entry.summary.createdAtMs))
            val thumbnail = PanoramaThumbnailView(this).apply {
                visibility = if (entry.result != null || entry.state?.isActive == true) View.VISIBLE else View.GONE
                contentDescription = "Open sphere captured $date"
                isFocusable = true
            }
            item.addView(thumbnail, LinearLayout.LayoutParams(-1, -2))
            item.addView(text(date, 18, white, true).apply { setPadding(0, dp(13), 0, dp(10)) })
            addSphereActions(item, entry.summary, entry.result, entry.state, thumbnail)
            if (isLandscape()) {
                if (index % 2 == 0) {
                    pair = row().apply { gravity = Gravity.TOP }
                    parent.addView(pair, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
                }
                pair!!.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index % 2 == 0) marginEnd = dp(8) else marginStart = dp(8)
                })
                if (index == entries.lastIndex && index % 2 == 0)
                    pair!!.addView(space(0), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(8) })
            } else parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
    }

    private fun addSphereActions(item: LinearLayout, summary: SessionSummary, initialResult: PanoramaResult?,
        initialState: StitchJobState?, thumbnail: PanoramaThumbnailView) {
        var available = initialResult
        var latestState = initialState
        val info = text("", 14, muted)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
        }
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val view = button("View sphere", true) { openSphere(summary.id) }
        val generate = button("Create sphere", true) { requestStitch(summary.id) }
        val more = button("···", false) { showGalleryMore(summary, available, latestState) }.apply { contentDescription = "More actions for this capture" }
        val cancel = button("Stop creating", false) { StitchRepository.cancel(this, summary.id) }.apply { visibility = View.GONE }
        thumbnail.setOnClickListener { if (available != null) openSphere(summary.id) }
        fun render(state: StitchJobState?) {
            latestState = state
            if (state?.result != null) available = state.result
            val active = state?.isActive == true
            thumbnail.showPreview(available?.preview)
            thumbnail.visibility = if (available != null || active) View.VISIBLE else View.GONE
            thumbnail.isEnabled = available != null
            view.visibility = if (available != null) View.VISIBLE else View.GONE
            generate.visibility = if (active || available != null) View.GONE else View.VISIBLE
            generate.isEnabled = summary.capturedCount >= 2
            generate.text = if (state?.status == StitchStatus.FAILED) "Try creating again" else "Create sphere"
            cancel.visibility = if (active) View.VISIBLE else View.GONE
            cancel.isEnabled = state?.status != StitchStatus.CANCELLING
            progress.visibility = if (active) View.VISIBLE else View.GONE
            progress.progress = state?.progress ?: 0
            progress.isIndeterminate = active && (state?.progress ?: 0) == 0
            info.text = when {
                state?.status == StitchStatus.CANCELLING -> "Stopping. Your originals are safe."
                active -> "Creating your sphere · ${state?.progress ?: 0}%"
                available != null -> if (available!!.coverageFraction < .995) "Ready to explore · partial view" else "Ready to look around"
                summary.capturedCount == 0 && summary.recoveredOriginalCount > 0 -> "Recovered originals · ready to export"
                state?.status in setOf(StitchStatus.FAILED, StitchStatus.INTERRUPTED, StitchStatus.CANCELLED) -> "Your originals are safe. Create a sphere when you’re ready."
                summary.capturedCount < 2 -> "${summary.capturedCount} saved photo. A sphere needs at least two."
                summary.capturedCount < summary.targetCount -> "${summary.capturedCount} saved photos · partial capture"
                else -> "All ${summary.capturedCount} photos saved · ready to create"
            }
        }
        item.addView(info)
        item.addView(progress, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(10) })
        actions.addView(view, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(generate, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(more, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10) })
        item.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        item.addView(cancel, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        render(initialState)
        item.post {
            if (screen == "sessions" && item.isAttachedToWindow && !isDestroyed) {
                stitchObservers += StitchRepository.observe(this, summary.id) { state ->
                    if (screen == "sessions" && item.isAttachedToWindow && !isDestroyed) render(state)
                }
            }
        }
    }

    private fun showGalleryMore(summary: SessionSummary, result: PanoramaResult?, state: StitchJobState?) {
        val options = mutableListOf("Photo details", "Export original photos (ZIP)")
        if (result != null && state?.isActive != true) options += "Create this sphere again"
        AlertDialog.Builder(this).setTitle("This capture").setItems(options.toTypedArray()) { _, selected ->
            when (selected) {
                0 -> AlertDialog.Builder(this).setTitle("Photo details").setMessage(buildString {
                    append("${summary.capturedCount} of ${summary.targetCount} directions saved\n")
                    append("Original photos: ${formatBytes(summary.bytes)}\n")
                    result?.let {
                        append("\nSphere: ${it.width} × ${it.height} pixels\n")
                        append(String.format(Locale.US, "Estimated photographed coverage: %.1f%%\n", it.coverageFraction * 100))
                    }
                    if (state?.status in setOf(StitchStatus.FAILED, StitchStatus.INTERRUPTED)) append("\n${state?.message}\n")
                    append("\nOriginal exports contain the photos, camera and motion records, and any location you enabled.")
                }).setPositiveButton("Done", null).show()
                1 -> requestExport(summary)
                2 -> requestStitch(summary.id)
            }
        }.setNegativeButton("Done", null).show()
    }

    private fun openSphere(id: String) {
        startActivity(Intent(this, PanoramaViewerActivity::class.java).putExtra(PanoramaViewerActivity.SESSION_ID, id))
    }

    private fun requestStitch(id: String) {
        val loading = AlertDialog.Builder(this).setTitle("Preparing your sphere")
            .setMessage("Checking your original photos…").create()
        loading.show()
        io.execute {
            try {
                val plan = StitchRepository.plan(this, id)
                main.post {
                    if (isDestroyed || isFinishing || !loading.isShowing) return@post
                    loading.dismiss()
                    val note = "We’ll create it on your phone while you keep browsing. Your original photos stay safe." +
                        if (!plan.completePlanned) "\n\nThis is a partial capture. Uncaptured areas will stay blank." else ""
                    AlertDialog.Builder(this).setTitle("Create your sphere?").setMessage(note)
                        .setPositiveButton("Create") { _, _ ->
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("notificationAsked", false)) {
                                pendingStitch = id to StitchQuality.FULL
                                getPreferences(0).edit().putBoolean("notificationAsked", true).apply()
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
                            } else launchStitch(id, StitchQuality.FULL)
                        }.setNegativeButton("Later", null).show()
                }
            } catch (error: Exception) { main.post {
                if (!isDestroyed && !isFinishing && loading.isShowing) { loading.dismiss(); showError("Couldn’t prepare this capture", error) }
            } }
        }
    }

    private fun launchStitch(id: String, quality: StitchQuality) {
        if (!StitchRepository.start(this, id, quality)) toast("A sphere is already being created. You can follow its progress here.")
        showSessions()
    }

    private fun requestExport(summary: SessionSummary) {
        exportSummary = summary
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "PhotoSphere-${summary.id}.zip")
        }, 30)
    }

    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 30 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val summary = exportSummary ?: return
        val dialog = AlertDialog.Builder(this).setTitle("Saving your originals")
            .setMessage("Writing the ZIP file. Your capture remains safe on this phone.").setCancelable(false).create()
        dialog.show()
        io.execute {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output -> store.exportZip(summary, output) }
                    ?: error("The selected location is unavailable.")
                main.post { if (!isFinishing && !isDestroyed) { dialog.dismiss(); toast("Originals exported") } }
            } catch (e: Exception) { main.post { if (!isFinishing && !isDestroyed) { dialog.dismiss(); showError("Couldn’t export originals", e) } } }
        }
    }

    private fun showCameraDetails() {
        if (busy) { toast("Finishing this photo. Just a moment."); return }
        val info = cameraInfo
        val message = if (info == null) "The camera is still being checked." else buildString {
            append("Original photo: ${info.jpegWidth} × ${info.jpegHeight}\n")
            append("Preview: ${info.previewWidth} × ${info.previewHeight}\n")
            append("Camera ID: ${info.cameraId}\n")
            append("Capture: ${if (info.maxResolutionMode) "Maximum sensor resolution" else "Standard sensor mode"}\n")
            append("Tracking: orientation + gyroscope\n")
            append("Position / sideways drift: not measured\n")
            append("GPS: ${if (locationEnabled && lastLocation != null) "Available" else if (locationEnabled) "Waiting for a fresh location" else "Off"}\n")
            lastShotMs?.let { append("Last photo saved in: $it ms\n") }
            append("\nComplete camera capabilities and capture metadata are included in every exported session.")
        }
        val wasPaused = paused
        val generation = sessionGeneration
        val pauseMarker = pauseVersion
        paused = true; guidance.reset()
        AlertDialog.Builder(this).setTitle("Your camera").setMessage(message)
            .setPositiveButton("Done", null).setOnDismissListener {
                if (generation == sessionGeneration && pauseMarker == pauseVersion && screen == "capture" && !interrupted && activityResumed) {
                    paused = wasPaused; guidance.reset()
                }
            }.show()
    }

    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshLocation() {
        if (!locationEnabled || !hasLocationPermission()) return
        try {
            val manager = getSystemService(LocationManager::class.java)
            lastLocation = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter(::locationIsFresh).maxByOrNull { it.elapsedRealtimeNanos }
            if (Build.VERSION.SDK_INT >= 30) {
                val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                val cancel = CancellationSignal()
                manager.getCurrentLocation(provider, cancel, mainExecutor) { fresh -> if (fresh != null && locationIsFresh(fresh)) lastLocation = fresh }
                main.postDelayed({ cancel.cancel() }, 15_000)
            }
        } catch (_: SecurityException) { locationEnabled = false }
          catch (_: IllegalArgumentException) { /* GPS unavailable; capture remains usable. */ }
    }

    private fun locationIsFresh(location: Location): Boolean = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..300_000_000_000L
    private fun Location.asCaptureLocation() = CaptureLocation(latitude, longitude, if (hasAltitude()) altitude else null, if (hasAccuracy()) accuracy else null, time)
    private fun currentCaptureLocation() = lastLocation?.takeIf { locationEnabled && locationIsFresh(it) }?.asCaptureLocation()
    private fun showError(title: String, error: Exception) { if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: "Please try again.").setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun formatBytes(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun column(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun button(value: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 16f; isAllCaps = false; includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) ink else white)
        background = rounded(if (primary) mint else card, 18)
        stateListAnimator = null
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { click() }
    }
    private fun chip(value: String) = text(value, 9, mint, true).apply {
        letterSpacing = .10f; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(card, 10)
    }
    private fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }

    private inner class OrbitIllustration(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval = RectF()
        override fun onDraw(canvas: Canvas) {
            val cx = width * .5f; val cy = height * .43f; val radius = min(width * .25f, height * .33f)
            paint.style = Paint.Style.FILL; paint.color = card
            canvas.drawCircle(cx, cy, radius * 1.13f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Color.rgb(76, 115, 91)
            canvas.drawCircle(cx, cy, radius, paint)
            oval.set(cx - radius, cy - radius * .31f, cx + radius, cy + radius * .31f); canvas.drawOval(oval, paint)
            oval.set(cx - radius * .43f, cy - radius, cx + radius * .43f, cy + radius); canvas.drawOval(oval, paint)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            paint.color = mint; paint.strokeWidth = dp(2).toFloat()
            oval.set(cx - radius - dp(11), cy - radius - dp(11), cx + radius + dp(11), cy + radius + dp(11))
            canvas.drawArc(oval, -55f, 95f, false, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx + radius * .79f, cy - radius * .61f, dp(5).toFloat(), paint)
            paint.color = muted; paint.textSize = dp(10).toFloat(); paint.textAlign = Paint.Align.CENTER
            canvas.drawText("ALL AROUND. ABOVE. BELOW.", cx, cy + radius + dp(28), paint)
        }
    }
}
