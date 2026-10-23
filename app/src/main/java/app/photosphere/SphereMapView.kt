package app.photosphere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** A target map, not an assertion of exact photographed or stitchable coverage. */
class SphereMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val fontScale = resources.configuration.fontScale
    private val mint = Color.rgb(184, 243, 211)
    private val paper = Color.rgb(245, 248, 246)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bounds = RectF()
    private val mapBounds = RectF()
    private var positions = FloatArray(0)
    private var captured = BooleanArray(0)
    private var active = -1
    private var countLabel = "0 / 0"
    private var labelSize = dp(10f)
    private var showFooter = true
    private var compact = false
    private var headerBaseline = dp(24f)
    private var lastSavedCount = -1

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Sphere target map. No photo targets yet."
    }

    /** Points are (yaw, pitch) degrees; IDs refer to their position in this list. */
    fun setPlan(points: List<Pair<Double, Double>>, capturedIds: Set<Int>, activeIndex: Int) {
        var changed = false
        if (captured.size != points.size) {
            positions = FloatArray(points.size * 2)
            captured = BooleanArray(points.size)
            changed = true
        }
        var savedCount = 0
        points.forEachIndexed { index, point ->
            val yaw = if (point.first.isFinite()) point.first else 0.0
            val pitch = if (point.second.isFinite()) point.second.coerceIn(-90.0, 90.0) else 0.0
            val normalizedYaw = ((yaw + 180.0) % 360.0 + 360.0) % 360.0
            val x = (normalizedYaw / 360.0).toFloat()
            val y = ((90.0 - pitch) / 180.0).toFloat()
            val isSaved = index in capturedIds
            if (positions[index * 2] != x || positions[index * 2 + 1] != y || captured[index] != isSaved) changed = true
            positions[index * 2] = x
            positions[index * 2 + 1] = y
            captured[index] = isSaved
            if (captured[index]) savedCount++
        }
        val newActive = if (activeIndex in points.indices) activeIndex else -1
        if (!changed && active == newActive && savedCount == lastSavedCount) return
        active = newActive
        lastSavedCount = savedCount
        countLabel = "$savedCount / ${points.size}"
        contentDescription = buildString {
            append("Sphere target map. $savedCount of ${points.size} photo targets saved. ")
            if (active >= 0) append("Current target ${active + 1}. ")
            append("Filled dots are saved photos, hollow dots are remaining targets, and the large ring is the current target. ")
            append("The map shows 360 degrees around you and 180 degrees from above to below.")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthMeasureSpec),
            resolveSize(dp(150f).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        compact = h / density < 110f
        showFooter = h / density >= 125f && fontScale < 1.5f
        headerBaseline = dp(if (compact) 19f else 24f)
        val graphTop = dp((if (compact) 30f else 36f) + max(0f, fontScale - 1f) * 10f)
        val graphBottom = h - dp(if (showFooter) 24f else if (compact) 9f else 12f)
        mapBounds.set(dp(21f), graphTop, max(dp(22f), w - dp(21f)), max(graphTop + dp(1f), graphBottom))
        labelSize = dp(if (w / density < 250f) 10f else 11f) * fontScale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(19, 36, 30)
        canvas.drawRoundRect(bounds, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(24, 184, 243, 211)
        canvas.drawRoundRect(bounds, dp(20f), dp(20f), paint)

        paint.style = Paint.Style.FILL
        paint.textSize = labelSize
        paint.color = Color.argb(180, 245, 248, 246)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("YOUR SPHERE", dp(17f), headerBaseline, paint)
        paint.color = mint
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(countLabel, width - dp(17f), headerBaseline, paint)

        // Equirectangular graticule. No filled polygons implying verified coverage.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.7f)
        for (line in 0..6) {
            val x = mapBounds.left + mapBounds.width() * line / 6f
            paint.color = Color.argb(if (line == 3) 43 else 21, 184, 243, 211)
            canvas.drawLine(x, mapBounds.top, x, mapBounds.bottom, paint)
        }
        for (line in 0..6) {
            val y = mapBounds.top + mapBounds.height() * line / 6f
            paint.color = Color.argb(if (line == 3) 55 else 22, 184, 243, 211)
            canvas.drawLine(mapBounds.left, y, mapBounds.right, y, paint)
        }

        for (index in captured.indices) {
            if (index == active) continue
            val x = mapBounds.left + positions[index * 2] * mapBounds.width()
            val y = mapBounds.top + positions[index * 2 + 1] * mapBounds.height()
            paint.style = if (captured[index]) Paint.Style.FILL else Paint.Style.STROKE
            paint.color = if (captured[index]) mint else Color.argb(115, 245, 248, 246)
            paint.strokeWidth = dp(1f)
            canvas.drawCircle(x, y, dp(if (captured[index]) { if (compact) 2.6f else 3f } else { if (compact) 1.8f else 2f }), paint)
        }

        if (active in captured.indices) {
            val x = mapBounds.left + positions[active * 2] * mapBounds.width()
            val y = mapBounds.top + positions[active * 2 + 1] * mapBounds.height()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(19, 36, 30)
            canvas.drawCircle(x, y, dp(if (compact) 6.5f else 8f), paint)
            paint.style = Paint.Style.STROKE
            paint.color = mint
            paint.strokeWidth = dp(1.8f)
            canvas.drawCircle(x, y, dp(if (compact) 4.8f else 5.8f), paint)
            paint.style = Paint.Style.FILL
            paint.color = paper
            canvas.drawCircle(x, y, dp(1.8f), paint)
        }

        if (showFooter) {
            paint.style = Paint.Style.FILL
            paint.textSize = dp(9f) * fontScale
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.argb(150, 245, 248, 246)
            canvas.drawText("360° around", dp(17f), height - dp(8f), paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("180° above + below", width - dp(17f), height - dp(8f), paint)
        }
    }

    private fun dp(value: Float) = value * density
}
