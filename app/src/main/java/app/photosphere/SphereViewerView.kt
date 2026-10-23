package app.photosphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** One GPU-projected preview texture. Frames are requested only for input, upload or resizing. */
class SphereViewerView(context: Context) : GLSurfaceView(context) {
    var onPreviewReady: ((width: Int, height: Int) -> Unit)? = null
    var onViewerError: ((String) -> Unit)? = null
    @Volatile var yawDegrees = 0f
        private set
    @Volatile var pitchDegrees = 0f
        private set
    @Volatile var verticalFovDegrees = 70f
        private set

    private val decoder = Executors.newSingleThreadExecutor { task -> Thread(task, "PhotoSphere-ViewerDecode") }
    private val loadGeneration = AtomicInteger(0)
    private val pendingLock = Any()
    private var pending: PendingBitmap? = null
    @Volatile private var disposed = false
    @Volatile private var previewFile: File? = null
    @Volatile private var textureLimit = 0
    private val sphereRenderer = SphereRenderer()
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!detector.scaleFactor.isFinite() || detector.scaleFactor <= 0f) return false
            verticalFovDegrees = (verticalFovDegrees / detector.scaleFactor).coerceIn(30f, 100f)
            requestRender()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean = performClick()
        override fun onDoubleTap(event: MotionEvent): Boolean { resetView(); return true }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling.isInProgress || current.pointerCount > 1 || width == 0 || height == 0) return true
            val aspect = width.toDouble() / height
            val horizontalFov = Math.toDegrees(2.0 * atan(tan(Math.toRadians(verticalFovDegrees.toDouble()) * .5) * aspect))
            yawDegrees = wrap(yawDegrees + distanceX * horizontalFov.toFloat() / width)
            pitchDegrees = (pitchDegrees - distanceY * verticalFovDegrees / height).coerceIn(-89.9f, 89.9f)
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(sphereRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
        isFocusable = true
        contentDescription = "Interactive panorama preview. Drag to look around, pinch to zoom, or double tap to reset."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** This must be the persisted, small preview JPEG, never the high-resolution master. */
    fun loadPanorama(preview: File) {
        previewFile = preview
        if (textureLimit > 0) scheduleDecode()
    }

    fun resetView() = restoreView(0f, 0f, 70f)

    fun restoreView(yaw: Float, pitch: Float, fov: Float) {
        yawDegrees = if (yaw.isFinite()) wrap(yaw) else 0f
        pitchDegrees = if (pitch.isFinite()) pitch.coerceIn(-89.9f, 89.9f) else 0f
        verticalFovDegrees = if (fov.isFinite()) fov.coerceIn(30f, 100f) else 70f
        requestRender()
    }

    /** Activity calls this once, after onPause, when it is destroyed. */
    fun dispose() {
        disposed = true
        loadGeneration.incrementAndGet()
        decoder.shutdownNow()
        synchronized(pendingLock) { pending?.bitmap?.recycle(); pending = null }
        onPreviewReady = null
        onViewerError = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageView"
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> yawDegrees = wrap(yawDegrees + 30f)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> yawDegrees = wrap(yawDegrees - 30f)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        requestRender()
        return true
    }

    private data class PendingBitmap(val generation: Int, val bitmap: Bitmap)

    private fun scheduleDecode() {
        val source = previewFile ?: return
        val limit = min(4096, textureLimit)
        if (disposed || limit < 2) return
        val generation = loadGeneration.incrementAndGet()
        try {
            decoder.execute {
                var bitmap: Bitmap? = null
                try {
                    if (disposed || generation != loadGeneration.get()) return@execute
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.absolutePath, bounds)
                    check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() == bounds.outHeight.toLong() * 2L) {
                        "The panorama preview is missing or is not a 2:1 image."
                    }
                    var sample = 1
                    while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit / 2) sample *= 2
                    bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }) ?: error("The panorama preview could not be decoded.")
                    // GLES2 supports seamless repeat for power-of-two textures on every device.
                    val textureWidth = Integer.highestOneBit(min(limit, bitmap!!.width))
                    val textureHeight = max(1, textureWidth / 2)
                    if (bitmap!!.width != textureWidth || bitmap!!.height != textureHeight) {
                        val resized = Bitmap.createScaledBitmap(bitmap!!, textureWidth, textureHeight, true)
                        if (resized !== bitmap) bitmap!!.recycle()
                        bitmap = resized
                    }
                    if (disposed || generation != loadGeneration.get()) return@execute
                    synchronized(pendingLock) {
                        if (disposed || generation != loadGeneration.get()) return@execute
                        pending?.bitmap?.recycle()
                        pending = PendingBitmap(generation, bitmap!!)
                        bitmap = null
                    }
                    queueEvent {
                        val upload = synchronized(pendingLock) {
                            pending?.takeIf { it.generation == generation }?.also { pending = null }
                        } ?: return@queueEvent
                        try {
                            if (!disposed && generation == loadGeneration.get()) {
                                val failure = sphereRenderer.upload(upload.bitmap)
                                if (failure == GLES20.GL_NO_ERROR) {
                                    val w = upload.bitmap.width; val h = upload.bitmap.height
                                    post { if (!disposed) onPreviewReady?.invoke(w, h) }
                                } else if (textureLimit > 512) {
                                    textureLimit = max(512, textureLimit / 2)
                                    scheduleDecode()
                                } else reportError("This device could not open the panorama preview.")
                            }
                        } catch (error: Exception) {
                            reportError(error.message ?: "The panorama preview could not be uploaded.")
                        } finally { upload.bitmap.recycle() }
                    }
                    requestRender()
                } catch (_: OutOfMemoryError) {
                    if (textureLimit > 512 && !disposed && generation == loadGeneration.get()) {
                        textureLimit = max(512, textureLimit / 2)
                        scheduleDecode()
                    } else reportError("There isn’t enough memory to open this preview. Close another app and try again.")
                } catch (error: Exception) {
                    if (!disposed && generation == loadGeneration.get()) reportError(error.message ?: "The panorama preview could not be opened.")
                } finally { bitmap?.recycle() }
            }
        } catch (_: RejectedExecutionException) { /* Activity was destroyed during a GL callback. */ }
    }

    private fun reportError(message: String) { post { if (!disposed) onViewerError?.invoke(message) } }

    private fun wrap(degrees: Float) = ((degrees + 180f) % 360f + 360f) % 360f - 180f

    private inner class SphereRenderer : Renderer {
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        private var program = 0
        private var texture = 0
        private var positionHandle = 0
        private var rightHandle = 0
        private var upHandle = 0
        private var forwardHandle = 0
        private var projectionHandle = 0
        private var textureHandle = 0
        private var aspect = 1f

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            texture = 0
            program = 0
            GLES20.glClearColor(12f / 255f, 24f / 255f, 21f / 255f, 1f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            try {
                val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
                val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
                val candidate = GLES20.glCreateProgram()
                GLES20.glAttachShader(candidate, vertex)
                GLES20.glAttachShader(candidate, fragment)
                GLES20.glLinkProgram(candidate)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                val status = IntArray(1)
                GLES20.glGetProgramiv(candidate, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val reason = GLES20.glGetProgramInfoLog(candidate)
                    GLES20.glDeleteProgram(candidate)
                    error("The panorama renderer could not start: $reason")
                }
                program = candidate
                positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
                rightHandle = GLES20.glGetUniformLocation(program, "uRight")
                upHandle = GLES20.glGetUniformLocation(program, "uUp")
                forwardHandle = GLES20.glGetUniformLocation(program, "uForward")
                projectionHandle = GLES20.glGetUniformLocation(program, "uProjection")
                textureHandle = GLES20.glGetUniformLocation(program, "uPanorama")
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, status, 0)
                textureLimit = min(4096, status[0])
                check(textureLimit >= 2) { "The device does not support panorama textures." }
                scheduleDecode()
            } catch (error: Exception) { reportError(error.message ?: "The panorama renderer could not start.") }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / max(1, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0 || texture == 0) return
            val yaw = Math.toRadians(yawDegrees.toDouble())
            val pitch = Math.toRadians(pitchDegrees.toDouble())
            val sy = sin(yaw).toFloat(); val cy = cos(yaw).toFloat()
            val sp = sin(pitch).toFloat(); val cp = cos(pitch).toFloat()
            val tangent = tan(Math.toRadians(verticalFovDegrees.toDouble()) * .5).toFloat()
            GLES20.glUseProgram(program)
            GLES20.glUniform3f(rightHandle, cy, 0f, -sy)
            GLES20.glUniform3f(upHandle, -sy * sp, cp, -cy * sp)
            GLES20.glUniform3f(forwardHandle, sy * cp, sp, cy * cp)
            GLES20.glUniform2f(projectionHandle, tangent * aspect, tangent)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(textureHandle, 0)
            quad.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        fun upload(bitmap: Bitmap): Int {
            if (program == 0) return GLES20.GL_INVALID_OPERATION
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            texture = names[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, names, 0)
                texture = 0
            }
            return error
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val reason = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("The panorama shader could not start: $reason")
            }
            return shader
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
attribute vec2 aPosition;
varying mediump vec2 vPosition;
void main() {
    vPosition = aPosition;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""
        private const val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying mediump vec2 vPosition;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uForward;
uniform vec2 uProjection;
uniform sampler2D uPanorama;
const float PI = 3.141592653589793;
void main() {
    vec3 ray = normalize(uForward + uRight * vPosition.x * uProjection.x + uUp * vPosition.y * uProjection.y);
    float longitude = atan(ray.x, ray.z);
    float latitude = asin(clamp(ray.y, -1.0, 1.0));
    vec2 uv = vec2(longitude / (2.0 * PI) + 0.5, 0.5 - latitude / PI);
    gl_FragColor = vec4(texture2D(uPanorama, uv).rgb, 1.0);
}
"""
    }
}
