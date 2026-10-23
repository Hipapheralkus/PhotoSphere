package app.photosphere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * A lightweight viewfinder guide. Positive yaw is right; positive pitch is up.
 * Angles are degrees in the portrait viewfinder coordinate system.
 * State comes from the capture controller: this view never starts its own animator.
 */
class CaptureOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val mint = Color.rgb(184, 243, 211)
    private val paper = Color.rgb(245, 248, 246)
    private val amber = Color.rgb(255, 210, 140)
    private val ink = Color.rgb(12, 24, 21)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcBounds = RectF()
    private val pillBounds = RectF()
    private val arrow = Path()
    private val check = Path()

    private var yaw = 0.0
    private var pitch = 0.0
    private var roll = 0.0
    private var progress = 0.0
    private var phase = "paused"
    private var horizontalFov = 50.0
    private var verticalFov = 65.0
    private var cameraPitch = 0.0
    private var cameraRoll = 0.0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Capture paused."
        setWillNotDraw(false)
    }

    fun update(
        yawError: Double,
        pitchError: Double,
        rollError: Double,
        holdProgress: Double,
        phase: String,
        horizontalFov: Double = 50.0,
        verticalFov: Double = 65.0,
        cameraPitch: Double = 0.0,
        cameraRoll: Double = rollError,
    ) {
        yaw = if (yawError.isFinite()) wrapDegrees(yawError) else 0.0
        pitch = if (pitchError.isFinite()) pitchError.coerceIn(-180.0, 180.0) else 0.0
        roll = if (rollError.isFinite()) wrapDegrees(rollError) else 0.0
        progress = if (holdProgress.isFinite()) holdProgress.coerceIn(0.0, 1.0) else 0.0
        this.horizontalFov = if (horizontalFov.isFinite()) horizontalFov.coerceIn(15.0, 150.0) else 50.0
        this.verticalFov = if (verticalFov.isFinite()) verticalFov.coerceIn(15.0, 150.0) else 65.0
        this.cameraPitch = if (cameraPitch.isFinite()) cameraPitch.coerceIn(-90.0, 90.0) else 0.0
        this.cameraRoll = if (cameraRoll.isFinite()) wrapDegrees(cameraRoll) else 0.0
        if (this.phase != phase) {
            this.phase = phase
            // Sensor updates can arrive at 60 Hz. Accessibility text changes only at transitions.
            contentDescription = when (phase) {
                "hold" -> "Aligned. Hold the phone still while the ring fills."
                "capture" -> "Taking the photo. Keep the phone still."
                "saved" -> "Photo saved."
                "paused" -> "Capture paused."
                else -> "Move the target circle onto the center guide. Keep the lens in one place."
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = dp(28f)
        val isSaved = phase == "saved"
        val isCapturing = phase == "capture"
        val isHolding = phase == "hold"
        val isPaused = phase == "paused"

        if (isSaved) {
            fill(mint)
            canvas.drawCircle(cx, cy, dp(32f), paint)
            check.rewind()
            check.moveTo(cx - dp(12f), cy)
            check.lineTo(cx - dp(3f), cy + dp(9f))
            check.lineTo(cx + dp(14f), cy - dp(10f))
            stroke(ink, 3f)
            canvas.drawPath(check, paint)
            return
        }

        // Four open corners leave the subject unobstructed while providing a fixed destination.
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val guideColor = if (isHolding || isCapturing) mint else paper
        for (i in 0..3) {
            stroke(Color.argb(if (isPaused) 70 else 120, 0, 0, 0), 5f)
            canvas.drawArc(arcBounds, 9f + i * 90f, 72f, false, paint)
            stroke(withAlpha(guideColor, if (isPaused) 105 else 235), 1.5f)
            canvas.drawArc(arcBounds, 9f + i * 90f, 72f, false, paint)
        }
        fill(Color.argb(125, 0, 0, 0))
        canvas.drawCircle(cx, cy, dp(3.7f), paint)
        fill(withAlpha(guideColor, if (isPaused) 125 else 240))
        canvas.drawCircle(cx, cy, dp(1.8f), paint)

        if (isPaused) return

        if (isHolding || isCapturing) {
            val holdRadius = dp(36f)
            arcBounds.set(cx - holdRadius, cy - holdRadius, cx + holdRadius, cy + holdRadius)
            stroke(Color.argb(100, 12, 24, 21), 5f)
            canvas.drawCircle(cx, cy, holdRadius, paint)
            stroke(mint, 3.5f)
            val sweep = if (isCapturing) 360f else (progress * 360.0).toFloat()
            if (sweep > 0f) canvas.drawArc(arcBounds, -90f, sweep, false, paint)
            if (isCapturing) {
                // A steady filled center marks exposure, with no spinner or synthetic timing.
                fill(mint)
                canvas.drawCircle(cx, cy, dp(6f), paint)
            }
        } else {
            drawTarget(canvas, cx, cy)
        }

        drawLevel(canvas, cx, min(height - dp(32f), cy + dp(97f)))
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float) {
        val radians = Math.PI / 180.0
        val yawRadians = yaw * radians
        val cameraPitchRadians = cameraPitch * radians
        val targetPitchRadians = (cameraPitch + pitch).coerceIn(-90.0, 90.0) * radians
        val rollRadians = cameraRoll * radians
        // Rotate the spherical target into the camera's own right/up/forward axes.
        // Using only delta-yaw exaggerates horizontal error in the upper/lower rows.
        val right = cos(targetPitchRadians) * sin(yawRadians)
        val up = cos(cameraPitchRadians) * sin(targetPitchRadians) -
            sin(cameraPitchRadians) * cos(targetPitchRadians) * cos(yawRadians)
        val z = sin(cameraPitchRadians) * sin(targetPitchRadians) +
            cos(cameraPitchRadians) * cos(targetPitchRadians) * cos(yawRadians)
        val screenRight = right * cos(rollRadians) - up * sin(rollRadians)
        val screenUp = right * sin(rollRadians) + up * cos(rollRadians)
        val fx = width * 0.5 / tan(horizontalFov * radians * 0.5)
        val fy = height * 0.5 / tan(verticalFov * radians * 0.5)
        var dx: Float
        var dy: Float
        if (z > 0.05) {
            dx = (fx * screenRight / z).toFloat()
            dy = (-fy * screenUp / z).toFloat()
        } else {
            // A target behind the camera still points along the shortest angular direction.
            val angularX = yaw / horizontalFov * width
            val angularY = -pitch / verticalFov * height
            dx = (angularX * cos(rollRadians) + angularY * sin(rollRadians)).toFloat()
            dy = (-angularX * sin(rollRadians) + angularY * cos(rollRadians)).toFloat()
        }
        val inset = dp(43f)
        val availableX = max(dp(1f), cx - inset)
        val availableY = max(dp(1f), cy - inset)
        val overflow = max(abs(dx) / availableX, abs(dy) / availableY)
        val offscreen = overflow > 1f || z <= 0.05
        if (offscreen) {
            val divisor = max(1f, overflow)
            dx /= divisor
            dy /= divisor
            drawEdgeArrow(canvas, cx + dx, cy + dy, dx, dy)
            return
        }

        val tx = cx + dx
        val ty = cy + dy
        val targetRadius = dp(21f)
        fill(Color.argb(26, 184, 243, 211))
        canvas.drawCircle(tx, ty, targetRadius, paint)
        stroke(Color.argb(125, 0, 0, 0), 5.5f)
        canvas.drawCircle(tx, ty, targetRadius, paint)
        stroke(mint, 2.5f)
        canvas.drawCircle(tx, ty, targetRadius, paint)
        // Cardinal notches distinguish the moving target from the stationary open guide.
        stroke(mint, 2f)
        canvas.drawLine(tx - dp(5f), ty, tx + dp(5f), ty, paint)
        canvas.drawLine(tx, ty - dp(5f), tx, ty + dp(5f), paint)
    }

    private fun drawEdgeArrow(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val radius = dp(21f)
        fill(Color.argb(210, 12, 24, 21))
        canvas.drawCircle(x, y, radius, paint)
        stroke(Color.argb(90, 184, 243, 211), 1f)
        canvas.drawCircle(x, y, radius, paint)
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        canvas.save()
        canvas.rotate(angle, x, y)
        arrow.rewind()
        arrow.moveTo(x - dp(4f), y - dp(7f))
        arrow.lineTo(x + dp(3f), y)
        arrow.lineTo(x - dp(4f), y + dp(7f))
        stroke(mint, 2.5f)
        canvas.drawPath(arrow, paint)
        canvas.restore()
    }

    private fun drawLevel(canvas: Canvas, cx: Float, cy: Float) {
        val halfWidth = dp(27f)
        pillBounds.set(cx - dp(43f), cy - dp(16f), cx + dp(43f), cy + dp(16f))
        fill(Color.argb(165, 12, 24, 21))
        canvas.drawRoundRect(pillBounds, dp(16f), dp(16f), paint)
        stroke(Color.argb(145, 245, 248, 246), 1f)
        canvas.drawLine(cx - halfWidth, cy, cx + halfWidth, cy, paint)
        canvas.drawLine(cx - halfWidth, cy - dp(4f), cx - halfWidth, cy + dp(4f), paint)
        canvas.drawLine(cx + halfWidth, cy - dp(4f), cx + halfWidth, cy + dp(4f), paint)
        val levelColor = if (abs(roll) <= 3.0) mint else amber
        canvas.save()
        canvas.rotate(roll.coerceIn(-35.0, 35.0).toFloat(), cx, cy)
        stroke(levelColor, 2.5f)
        canvas.drawLine(cx - dp(18f), cy, cx + dp(18f), cy, paint)
        fill(levelColor)
        canvas.drawCircle(cx, cy, dp(3f), paint)
        canvas.restore()
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, widthDp: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = dp(widthDp)
    }

    private fun dp(value: Float) = value * density
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
    private fun wrapDegrees(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
