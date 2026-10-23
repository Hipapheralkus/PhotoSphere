package app.photosphere

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt

/** An offline viewer for a completed stitch. It never decodes the high-resolution export JPEG. */
class PanoramaViewerActivity : Activity() {
    private val ink = Color.rgb(12, 24, 21)
    private val mint = Color.rgb(184, 243, 211)
    private val white = Color.rgb(245, 248, 246)
    private val muted = Color.rgb(179, 198, 187)
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "PhotoSphere-ViewerIO") }
    private lateinit var viewer: SphereViewerView
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var resolution: TextView
    private lateinit var coverage: TextView
    private lateinit var export: Button
    private lateinit var details: Button
    private var panorama: PanoramaResult? = null
    private var sessionId = ""
    private var pendingExportPath: String? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        sessionId = intent.getStringExtra(SESSION_ID).orEmpty()
        pendingExportPath = savedInstanceState?.getString("exportPath")
        buildUi()
        if (savedInstanceState != null) viewer.restoreView(
            savedInstanceState.getFloat("yaw", 0f),
            savedInstanceState.getFloat("pitch", 0f),
            savedInstanceState.getFloat("fov", 70f),
        )
        io.execute {
            try {
                val directory = sessionDirectory()
                val result = StitchRepository.result(applicationContext, sessionId)
                    ?: error("This capture doesn’t have a finished panorama yet.")
                requireOwnedFile(directory, result.image)
                requireOwnedFile(directory, result.preview)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    panorama = result
                    export.isEnabled = !exporting
                    details.isEnabled = true
                    val percentage = floor(result.coverageFraction.coerceIn(0.0, 1.0) * 1000.0) / 10.0
                    coverage.text = String.format(Locale.US, "%.1f%% coverage estimate", percentage)
                    updateResolution()
                    viewer.loadPanorama(result.preview)
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed && !isFinishing) showViewerError(error.message ?: "The panorama could not be opened.") }
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(ink) }
        viewer = SphereViewerView(this).apply {
            onPreviewReady = { width, height ->
                previewWidth = width; previewHeight = height
                loading.visibility = View.GONE
                updateResolution()
            }
            onViewerError = ::showViewerError
        }
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(25))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF50C1815.toInt(), 0xD90C1815.toInt(), 0x000C1815))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", false) { finish() }.apply { contentDescription = "Back to your captures" },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        heading.addView(text("Your sphere", 22, white, true).apply { gravity = Gravity.CENTER })
        coverage = text("Opening your panorama", 12, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }
        heading.addView(coverage)
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        details = button("Info", false) { showDetails() }.apply { isEnabled = false; contentDescription = "Panorama resolution and coverage details" }
        header.addView(details, LinearLayout.LayoutParams(dp(58), dp(48)))
        top.addView(header)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(29), dp(22), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF50C1815.toInt(), 0xD90C1815.toInt(), 0x000C1815))
        }
        bottom.addView(text("Drag to explore · Pinch to zoom", 13, white).apply { gravity = Gravity.CENTER })
        resolution = text("A lightweight preview opens here. Export keeps the full resolution.", 11, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(13))
        }
        bottom.addView(resolution)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Reset view", false) { viewer.resetView() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
        export = button("Export JPEG", true) { requestExport() }.apply { isEnabled = false }
        actions.addView(export, LinearLayout.LayoutParams(0, dp(50), 1.3f))
        bottom.addView(actions)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = rounded(Color.argb(230, 12, 24, 21), 22)
        }
        spinner = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(mint) }
        loading.addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)))
        loadingText = text("Opening your sphere…", 16, white).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onResume() { super.onResume(); if (::viewer.isInitialized) viewer.onResume() }
    override fun onPause() { if (::viewer.isInitialized) viewer.onPause(); super.onPause() }
    override fun onDestroy() { if (::viewer.isInitialized) viewer.dispose(); io.shutdown(); super.onDestroy() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // GLSurfaceView receives its new size and redraws once; its texture and view angles persist.
        viewer.requestRender()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("yaw", viewer.yawDegrees)
        outState.putFloat("pitch", viewer.pitchDegrees)
        outState.putFloat("fov", viewer.verticalFovDegrees)
        outState.putString("exportPath", pendingExportPath)
        super.onSaveInstanceState(outState)
    }

    private fun updateResolution() {
        if (exporting) return
        val result = panorama ?: return
        resolution.text = if (previewWidth > 0)
            "Preview $previewWidth × $previewHeight · Export ${result.width} × ${result.height}"
        else "Opening preview · Export ${result.width} × ${result.height}"
    }

    private fun showViewerError(message: String) {
        if (isDestroyed || isFinishing) return
        loading.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        loadingText.text = message
        if (panorama == null) coverage.text = "Panorama unavailable"
    }

    private fun showDetails() {
        val result = panorama ?: return
        val message = buildString {
            append("Export: ${result.width} × ${result.height} pixels\n")
            if (previewWidth > 0) append("Viewing preview: $previewWidth × $previewHeight pixels\n")
            append("${result.sourceCount} source photos\n")
            append(String.format(Locale.US, "Estimated photographed coverage: %.2f%%\n", result.coverageFraction * 100.0))
            append("\nThe exported JPEG keeps the full output resolution and panorama metadata. The interactive view uses a smaller preview.\n")
            append("\nCoverage is an estimate. Unphotographed areas may remain.\n")
            if (result.warnings.isNotEmpty()) {
                append("\n")
                append(result.warnings.joinToString("\n\n"))
            }
        }
        AlertDialog.Builder(this).setTitle("About this sphere").setMessage(message).setPositiveButton("Done", null).show()
    }

    private fun requestExport() {
        val result = panorama ?: return
        if (exporting) return
        pendingExportPath = result.image.absolutePath
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TITLE, "PhotoSphere-$sessionId.jpg")
            }, EXPORT_REQUEST)
        } catch (error: Exception) {
            AlertDialog.Builder(this).setTitle("Couldn’t open a save location")
                .setMessage(error.message ?: "Please try again.").setPositiveButton("OK", null).show()
        }
    }

    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = pendingExportPath ?: return
        exporting = true
        export.isEnabled = false
        export.text = "Saving JPEG…"
        resolution.text = "Saving the full-resolution JPEG…"
        val resolver = applicationContext.contentResolver
        io.execute {
            try {
                val source = requireOwnedFile(sessionDirectory(), File(path))
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                    output.flush()
                } ?: error("The selected save location is unavailable.")
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    finishExport()
                    Toast.makeText(this, "Full-resolution panorama exported", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    finishExport()
                    AlertDialog.Builder(this).setTitle("Couldn’t export this panorama")
                        .setMessage(error.message ?: "The original panorama is still safe. Please try another location.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun finishExport() {
        exporting = false
        export.isEnabled = panorama != null
        export.text = "Export JPEG"
        updateResolution()
    }

    private fun sessionDirectory(): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid capture identifier." }
        val root = File(filesDir, "sessions").canonicalFile
        return File(root, sessionId).canonicalFile.also {
            require(it.parentFile == root && it.isDirectory) { "This capture could not be found." }
        }
    }

    private fun requireOwnedFile(directory: File, candidate: File): File = candidate.canonicalFile.also {
        require(it.toPath().startsWith(directory.toPath()) && it != directory && it.isFile) { "The panorama file is unavailable." }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun button(value: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 15f; isAllCaps = false; includeFontPadding = false
        setTextColor(if (primary) ink else white)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(if (primary) mint else Color.rgb(29, 49, 40), 17)
        stateListAnimator = null
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val SESSION_ID = "SESSION_ID"
        private const val EXPORT_REQUEST = 731
    }
}
