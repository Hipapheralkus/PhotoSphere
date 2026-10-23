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
    private val poseHistory = PoseHistory()
    private var screen = "home"
    private var quality = CaptureQuality.FAST
    private var locationEnabled = false
    private var lastLocation: Location? = null
    private var cameraInfo: CameraInfo? = null
    private var pose: Pose? = null
    private var plan = emptyList<CaptureTarget>()
    private var session: CaptureSession? = null
    private var targetIndex = 0
    private val completed = mutableSetOf<Int>()
    private var busy = false
    private var paused = false
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
    private lateinit var cameraRoot: FrameLayout
    private lateinit var previewPane: FrameLayout
    private lateinit var captureHeader: LinearLayout
    private lateinit var captureControls: LinearLayout
    private var displayRotation = Surface.ROTATION_0
    private var rotationGeneration = 0
    private var shellRoot: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        store = SessionStore(this)
        camera = CameraEngine(this)
        tracker = OrientationTracker(this, ::onPose)
        quality = if (getPreferences(0).getBoolean("detail", false)) CaptureQuality.DETAIL else CaptureQuality.FAST
        locationEnabled = getPreferences(0).getBoolean("location", false) && hasLocationPermission()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        showHome()
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
        sessionGeneration++
        tracker.stop()
        camera.close()
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
        camera.close()
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
        intro.addView(text("Look around. Follow the light.\nWe’ll take each photo when you’re still.", if (landscape) 15 else 17, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        intro.addView(OrbitIllustration(this), LinearLayout.LayoutParams(-1, dp(if (landscape) 128 else 186)))
        val modes = row().apply { gravity = Gravity.TOP }
        modes.addView(modeCard("Everyday", "Quick & full resolution", CaptureQuality.FAST), LinearLayout.LayoutParams(0, dp(91), 1f).apply { marginEnd = dp(10) })
        modes.addView(modeCard("More detail", "Larger if supported", CaptureQuality.DETAIL), LinearLayout.LayoutParams(0, dp(91), 1f))
        choices.addView(modes)
        choices.addView(space(if (landscape) 8 else 17))
        val loc = Switch(this).apply {
            text = "Remember this place"
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
        choices.addView(text("Optional GPS. Your photos stay on your phone.", 13, muted))
        choices.addView(space(if (landscape) 12 else 24))
        choices.addView(button("Start a sphere", true) { startRequested() }, LinearLayout.LayoutParams(-1, dp(if (landscape) 48 else 58)))
        choices.addView(space(10))
        choices.addView(button("Your captures", false) { showSessions() }, LinearLayout.LayoutParams(-1, dp(if (landscape) 48 else 52)))
        choices.addView(space(18))
        choices.addView(text("CAPTURE PROTOTYPE", 10, mint, true).apply { letterSpacing = .16f; gravity = Gravity.CENTER })
        choices.addView(space(7))
        choices.addView(text("Originals saved for stitching. Sphere assembly comes next.", 12, muted).apply { gravity = Gravity.CENTER })
        body.addView(space(12))
        installRoot(host)
    }

    private fun modeCard(title: String, subtitle: String, mode: CaptureQuality): View {
        val selected = mode == quality
        return column(14).apply {
            background = rounded(if (selected) Color.rgb(33, 61, 46) else card, 20, if (selected) mint else Color.rgb(43, 62, 52))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            contentDescription = "$title. $subtitle. ${if (selected) "Selected" else "Not selected"}"
            addView(text("${if (selected) "●" else "○"}  $title", 17, if (selected) mint else white, true))
            addView(space(7))
            addView(text(subtitle, 11, muted))
            setOnClickListener {
                quality = mode
                getPreferences(0).edit().putBoolean("detail", mode == CaptureQuality.DETAIL).apply()
                showHome()
            }
        }
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
        }
    }

    private fun showCapture() {
        screen = "capture"
        sessionGeneration++
        session = null; targetIndex = 0; completed.clear(); paused = false; busy = false
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
        map = SphereMapView(this)
        previewPane.addView(captureHeader, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        previewPane.addView(map, FrameLayout.LayoutParams(-1, dp(132), Gravity.TOP))
        captureControls = column(24).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0C1815.toInt(), 0xEB0C1815.toInt(), 0x000C1815))
            setPadding(dp(24), dp(50), dp(24), dp(22))
        }
        statusTitle = text("Let’s find your starting point", 27, white, true).apply { gravity = Gravity.CENTER }
        statusDetail = text("Portrait or landscape. Keep the lens in one place as you turn.", 15, muted).apply { gravity = Gravity.CENTER; minHeight = dp(48); setLineSpacing(dp(2).toFloat(), 1f) }
        captureControls.addView(statusTitle)
        captureControls.addView(space(10))
        captureControls.addView(statusDetail)
        captureControls.addView(space(17))
        action = button("Checking camera…", true) {
            primaryCaptureAction()
        }.apply { isEnabled = false }
        captureControls.addView(action, LinearLayout.LayoutParams(-1, dp(56)))
        finish = button("Save & finish", false) { requestFinish() }.apply { visibility = View.GONE }
        captureControls.addView(finish, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
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
        finish.layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
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
        if (cameraOpening || screen != "capture" || !activityResumed) return
        cameraOpening = true
        val generation = sessionGeneration
        camera.open(preview, quality, { info ->
            if (generation != sessionGeneration || screen != "capture") return@open
            cameraInfo = info
            fitGuidanceToPreview()
            if (session == null) {
                plan = CapturePlan.generate(info.horizontalFov, info.verticalFov)
                counter.text = "Ready to look around"
                map.setPlan(plan.map { it.yaw to it.pitch }, completed, 0)
                action.text = "Start here"
                statusTitle.text = "Let’s find your starting point"
                statusDetail.text = "Portrait or landscape. Keep the lens in one place as you turn."
            } else action.text = if (paused) "Resume capture" else "Pause capture"
            if (session != null && paused) {
                statusTitle.text = "Ready when you are"
                statusDetail.text = "Keep the lens near the same spot, then resume."
            }
            resolution.text = String.format(Locale.US, "%.1f MP originals  ·  %s", info.megapixels, if (quality == CaptureQuality.FAST) "Everyday" else "More detail")
            action.isEnabled = guidanceNotice == null
        }, { error ->
            if (generation != sessionGeneration || screen != "capture") return@open
            cameraOpening = false
            paused = true
            statusTitle.text = "Camera needs a moment"
            statusDetail.text = error
            action.text = "Try camera again"
            action.isEnabled = true
        })
    }

    private fun primaryCaptureAction() {
        if (busy) return
        if (!camera.isReady) {
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
                    busy = false; paused = false
                    if (!activityResumed || interrupted) { interrupted = true; finishSession(); return@post }
                    action.text = "Pause capture"; action.isEnabled = true
                    finish.visibility = View.VISIBLE
                    updateProgress()
                }
            } catch (e: Exception) { main.post { busy = false; action.isEnabled = true; showError("Couldn’t start capture", e) } }
        }
    }

    private fun togglePause() {
        if (busy) return
        paused = !paused
        guidance.reset()
        action.text = if (paused) "Resume capture" else "Pause capture"
        statusTitle.text = if (paused) "Take your time" else "Find the next light"
        statusDetail.text = if (paused) "Your photos are safe. Keep the lens near the same spot when you resume." else "Move gently. Stop when the two circles meet."
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
        if (now < savedUntilMs) return
        val target = TargetSelector.select(value, plan, completed, targetIndex) ?: return
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
                    statusDetail.text = "Choose any direction. Bring a light into the center."
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
                    statusDetail.text = "Choose any direction. Bring a light into the center."
                }
            }
        }
        if (state.ready) capture(target, value)
    }

    private fun capture(target: CaptureTarget, shutterPose: Pose) {
        val current = session ?: return
        if (busy || paused) return
        busy = true
        guidance.markCaptureStarted()
        action.isEnabled = false; finish.isEnabled = false
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
                                    if (!accepted) {
                                        captureFailed("The phone moved while taking the photo. The original is preserved; hold still and try this direction again.")
                                        return@post
                                    }
                                    completed.add(target.id)
                                    busy = false
                                    lastShotMs = metadata.durationMs
                                    savedUntilMs = SystemClock.elapsedRealtime() + 420
                                    guidance.reset()
                                    action.isEnabled = true; finish.isEnabled = true
                                    statusTitle.text = "Got it ✓"
                                    statusDetail.text = "Original saved. Explore in any direction."
                                    overlay.update(0.0, 0.0, 0.0, 1.0, "saved")
                                    overlay.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    updateProgress()
                                    if (completed.size == plan.size || interrupted) finishSession()
                                }
                            } catch (e: Exception) { main.post { captureFailed("The photo is on disk, but its session record couldn’t be saved. ${e.message}") } }
                        }
                    }, { error -> if (generation == sessionGeneration && screen == "capture") captureFailed(error) })
                }
            } catch (e: Exception) { main.post { captureFailed(e.message ?: "Couldn’t create the original file.") } }
        }
    }

    private fun captureFailed(error: String) {
        busy = false; paused = true
        guidance.reset()
        if (screen != "capture") return
        if (interrupted && session != null) { finishSession(); return }
        action.isEnabled = true; finish.isEnabled = true
        action.text = "Try this photo again"
        statusTitle.text = "Let’s try that one again"
        statusDetail.text = error
        overlay.update(0.0, 0.0, 0.0, 0.0, "paused")
    }

    private fun updateProgress() {
        counter.text = "${completed.size} of ${plan.size} photos"
        map.setPlan(plan.map { it.yaw to it.pitch }, completed, targetIndex.coerceAtMost(plan.lastIndex))
        counter.contentDescription = "${completed.size} of ${plan.size} photographs captured"
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
        paused = true
        guidance.reset()
        AlertDialog.Builder(this).setTitle("Save this capture?")
            .setMessage("${completed.size} original photos will stay safe. You can export them from Your captures. A new capture starts a new sphere.")
            .setPositiveButton("Save & finish") { _, _ -> finishSession() }
            .setNegativeButton("Keep going") { _, _ -> paused = wasPaused }
            .setOnCancelListener { paused = wasPaused }.show()
    }

    private fun finishSession() {
        if (isDestroyed || io.isShutdown) return
        val current = session ?: return
        if (endingSession) return
        endingSession = true
        val generation = sessionGeneration
        paused = true; busy = true
        tracker.stop(); camera.close(); cameraOpening = false
        io.execute {
            try {
                store.finish(current)
                main.post {
                    if (isDestroyed || generation != sessionGeneration) return@post
                    busy = false
                    endingSession = false
                    session = null
                    sessionGeneration++
                    showSaved(completed.size, plan.size)
                }
            } catch (e: Exception) { main.post {
                if (isDestroyed || generation != sessionGeneration) return@post
                busy = false; endingSession = false
                statusTitle.text = "Your originals are still safe"
                statusDetail.text = "The session couldn’t be finalized. Try saving again."
                action.text = "Retry saving"; action.isEnabled = true
                action.setOnClickListener { finishSession() }
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
        content.addView(text(if (interrupted) "Capture was interrupted, so we saved your originals. Start a new sphere to keep its alignment accurate." else "Export your originals for stitching. This prototype doesn’t assemble the sphere yet.", 15, muted).apply { gravity = Gravity.CENTER })
        content.addView(space(34))
        content.addView(button("View & export capture", true) { showSessions() }, LinearLayout.LayoutParams(-1, dp(58)))
        content.addView(space(10))
        content.addView(button("Back to start", false) { showHome() }, LinearLayout.LayoutParams(-1, dp(52)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(content)
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        installRoot(host)
    }

    private fun showSessions() {
        screen = "sessions"
        val host = FrameLayout(this).apply { setBackgroundColor(ink) }
        val scroll = ScrollView(this)
        val content = column(24)
        scroll.addView(content); host.addView(scroll)
        content.addView(button("‹  Back", false) { showHome() }, LinearLayout.LayoutParams(dp(110), dp(48)))
        content.addView(space(22))
        content.addView(text("Your captures", 34, white, true))
        content.addView(space(10))
        content.addView(text("Full-resolution originals. Ready for what comes next.", 16, muted))
        content.addView(space(24))
        val loading = text("Finding your photos…", 16, muted)
        content.addView(loading)
        installRoot(host)
        io.execute {
            try {
                val entries = store.listSessions()
                main.post {
                    if (screen != "sessions") return@post
                    content.removeView(loading)
                    if (entries.isEmpty()) {
                        content.addView(OrbitIllustration(this), LinearLayout.LayoutParams(-1, dp(210)))
                        content.addView(text("Your first sphere starts here.", 21, white, true))
                        content.addView(space(18))
                        content.addView(button("Start a sphere", true) { startRequested() }, LinearLayout.LayoutParams(-1, dp(58)))
                    }
                    entries.forEach { summary ->
                        val item = column(20).apply { background = rounded(card, 22) }
                        item.addView(text(SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()).format(Date(summary.createdAtMs)), 19, white, true))
                        item.addView(space(9))
                        item.addView(text("${summary.capturedCount} / ${summary.targetCount} photos  ·  ${formatBytes(summary.bytes)}", 15, mint))
                        item.addView(space(7))
                        item.addView(text(if (summary.capturedCount == summary.targetCount && summary.targetCount > 0) "All planned directions captured" else "Partial capture · originals preserved", 13, muted))
                        item.addView(space(16))
                        item.addView(button("Export originals", true) { requestExport(summary) }, LinearLayout.LayoutParams(-1, dp(50)))
                        content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
                    }
                    content.addView(text("Export before uninstalling. ZIP files contain original photos and any location you enabled.", 13, muted))
                }
            } catch (e: Exception) { main.post { loading.text = "Couldn’t read captures: ${e.message}" } }
        }
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
        paused = true; guidance.reset()
        AlertDialog.Builder(this).setTitle("Your camera").setMessage(message)
            .setPositiveButton("Done", null).setOnDismissListener { paused = wasPaused }.show()
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
