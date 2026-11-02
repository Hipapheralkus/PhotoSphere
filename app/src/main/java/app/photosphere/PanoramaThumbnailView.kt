package app.photosphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.View
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Decodes only visible gallery previews. A shared small cache never retains the master JPEG. */
class PanoramaThumbnailView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val badge = RectF()
    private val clip = Path()
    private var preview: File? = null
    private var key: String? = null
    private var loadingKey: String? = null
    private var failedKey: String? = null
    private var generation = 0

    fun showPreview(file: File?) {
        if (key == file?.absolutePath) return
        generation++
        preview = file; key = file?.absolutePath; loadingKey = null; failedKey = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((320 * density).toInt(), widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(width / 2, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset(); clip.addRoundRect(bounds, 18 * density, 18 * density, Path.Direction.CW)
        badge.set(w - 66 * density, 12 * density, w - 12 * density, 39 * density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        canvas.clipPath(clip)
        val bitmap = key?.let(cache::get)
        if (bitmap != null) canvas.drawBitmap(bitmap, null, bounds, paint)
        else {
            paint.color = Color.rgb(33, 58, 45); canvas.drawRect(bounds, paint)
            paint.color = Color.rgb(184, 243, 211); paint.textAlign = Paint.Align.CENTER
            paint.textSize = 33 * density
            canvas.drawText("◉", width * .5f, height * .48f, paint)
            paint.textSize = 13 * density
            canvas.drawText(if (key == null) "Creating your sphere" else if (failedKey == key) "Open to look around" else "Opening preview…",
                width * .5f, height * .48f + 28 * density, paint)
            requestPreview()
        }
        paint.color = Color.argb(220, 12, 24, 21)
        canvas.drawRoundRect(badge, 13 * density, 13 * density, paint)
        paint.color = Color.rgb(245, 248, 246); paint.textSize = 13 * density; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("360°", badge.centerX(), badge.centerY() + 4 * density, paint)
        canvas.restoreToCount(saved)
    }

    private fun requestPreview() {
        val file = preview ?: return
        val wanted = key ?: return
        if (loadingKey == wanted || failedKey == wanted || !isAttachedToWindow) return
        loadingKey = wanted
        val expected = generation
        val owner = WeakReference(this)
        try {
            decoder.execute {
                if (owner.get()?.let { it.isAttachedToWindow && it.generation == expected } != true) return@execute
                val bitmap = try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    check(options.outWidth > 0 && options.outWidth.toLong() == options.outHeight.toLong() * 2L)
                    var sample = 1
                    while (options.outWidth / sample > 640) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false
                    })
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                if (bitmap != null) cache.put(wanted, bitmap)
                owner.get()?.let { view -> view.post {
                    if (view.generation == expected && view.key == wanted && view.isAttachedToWindow) {
                        view.loadingKey = null
                        if (bitmap == null) view.failedKey = wanted
                        view.invalidate()
                    }
                } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            loadingKey = null
            postDelayed({ if (isAttachedToWindow && generation == expected) invalidate() }, 150L)
        }
    }

    override fun onDetachedFromWindow() {
        generation++; loadingKey = null
        super.onDetachedFromWindow()
    }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private val decoder = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, LinkedBlockingQueue(8)) { task ->
            Thread(task, "PhotoSphere-GalleryPreview")
        }
    }
}
