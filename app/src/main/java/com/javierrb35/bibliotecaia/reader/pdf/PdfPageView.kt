package com.javierrb35.bibliotecaia.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.javierrb35.bibliotecaia.reader.gestures.HorizontalSwipeGesture

/** Single page, bounded bitmap; pinch zoom, pan and double-tap reset. */
data class PdfPaintedMark(
    val annotationId: String, val bounds: PdfOcrRect, val kind: String = "HIGHLIGHT"
)

class PdfPageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()
    private var marks: List<PdfPaintedMark> = emptyList()
    private var onMarkedLongPress: ((List<String>) -> Unit)? = null
    private var onShortTap: (() -> Unit)? = null
    private var onUnmarkedLongPress: (() -> Unit)? = null
    private var onHorizontalSwipe: ((Int) -> Unit)? = null
    private val swipe = HorizontalSwipeGesture(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        maxOf(72f * resources.displayMetrics.density, resources.displayMetrics.widthPixels * .17f))
    private var multiTouch = false
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 212, 180, 131)
        style = Paint.Style.FILL
    }
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 177, 116, 37)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val noteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Stronger, warm shading distinguishes notes from plain underlines.
        color = Color.argb(135, 212, 180, 131)
        style = Paint.Style.FILL
    }
    fun onMarkedLongPress(listener: (List<String>) -> Unit) { onMarkedLongPress = listener }
    fun onShortTap(listener: () -> Unit) { onShortTap = listener }
    fun onUnmarkedLongPress(listener: () -> Unit) { onUnmarkedLongPress = listener }
    fun onHorizontalSwipe(listener: (Int) -> Unit) { onHorizontalSwipe = listener }
    fun showMarks(updated: List<PdfPaintedMark>) {
        marks = updated.filter { it.bounds.valid() }
        invalidate()
    }
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                constrainPan(); invalidate(); return true
            }
        })
    /** OCR rectangles are hit-tested in the displayed bitmap's geometry. */
    internal fun markedIdsAt(x: Float, y: Float): List<String> {
        val image = bitmap ?: return emptyList()
        if (marks.isEmpty()) return emptyList()
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return emptyList()
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val nx = point[0] / image.width
        val ny = point[1] / image.height
        fun matches(padX: Float, padY: Float): List<String> =
            marks.asReversed().filter {
                nx >= it.bounds.left - padX && nx <= it.bounds.right + padX &&
                    ny >= it.bounds.top - padY && ny <= it.bounds.bottom + padY
            }.map { it.annotationId }.distinct()
        val exact = matches(0f, 0f)
        if (exact.isNotEmpty()) return exact
        val scale = baseScale(image) * zoom
        val padPx = 5f * resources.displayMetrics.density
        return matches((padPx / (image.width * scale)).coerceAtMost(.012f),
            (padPx / (image.height * scale)).coerceAtMost(.012f))
    }

    private val gestures = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float): Boolean {
                // While enlarged, a horizontal drag pans the PDF; it never
                // turns a page (including when already at the pan boundary).
                if (zoom > 1.01f) {
                    panX -= distanceX
                    panY -= distanceY
                    constrainPan()
                    invalidate()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onShortTap?.invoke()
                performClick()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (multiTouch || scaleDetector.isInProgress) return
                val notes = markedIdsAt(e.x, e.y)
                if (notes.isNotEmpty()) onMarkedLongPress?.invoke(notes)
                else onUnmarkedLongPress?.invoke()
            }
        })
    init { contentDescription = "Página PDF. Pellizca para ampliar y arrastra para desplazarte."; isFocusable = true }
    fun display(value: Bitmap) {
        // The render thread may still hold the previous display list. Let GC release its bitmap.
        bitmap = value; marks = emptyList(); fit()
    }
    /** The visible bounded page bitmap; callers must not recycle it. */
    fun currentBitmap(): Bitmap? = bitmap
    fun clear() { bitmap = null; marks = emptyList(); invalidate() }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun fitWidth() {
        val image = bitmap ?: return
        zoom = ((width.toFloat() / image.width) / baseScale(image)).coerceIn(1f, 5f)
        panX = 0f; panY = ((image.height * baseScale(image) * zoom - height) / 2f).coerceAtLeast(0f)
        constrainPan(); invalidate()
    }
    fun changeZoom(factor: Float) { zoom = (zoom * factor).coerceIn(1f, 5f); constrainPan(); invalidate() }
    private fun baseScale(image: Bitmap): Float = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
    private fun constrainPan() {
        val image = bitmap ?: return
        val scale = baseScale(image) * zoom
        val maxX = ((image.width * scale - width) / 2f).coerceAtLeast(0f)
        val maxY = ((image.height * scale - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { constrainPan() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val scale = baseScale(image) * zoom
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - image.width * scale) / 2 + panX, (height - image.height * scale) / 2 + panY)
        canvas.drawBitmap(image, matrix, null)
        if (marks.isNotEmpty()) {
            // Draw in bitmap pixels through the same matrix as the PDF page.
            // This keeps every mark locked to its text through zoom and pan.
            canvas.save()
            canvas.concat(matrix)
            for (mark in marks) {
                val b = mark.bounds
                val left = b.left * image.width
                val top = b.top * image.height
                val right = b.right * image.width
                val bottom = b.bottom * image.height
                canvas.drawRect(left, top, right, bottom,
                    if (mark.kind == "NOTE") noteFill else markerFill)
                canvas.drawLine(left, bottom, right, bottom, markerStroke)
            }
            canvas.restore()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipe.down(event.x, event.y)
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                multiTouch = true
                swipe.cancel()
            }
            MotionEvent.ACTION_CANCEL -> swipe.cancel()
            MotionEvent.ACTION_MOVE -> if (zoom <= 1.01f && !multiTouch) {
                swipe.move(event.x, event.y, event.pointerCount)
            } else swipe.cancel()
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && !multiTouch) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val change = if (!multiTouch && zoom <= 1.01f)
                swipe.up(event.x, event.y, event.pointerCount) else 0
            if (change != 0) onHorizontalSwipe?.invoke(change)
            multiTouch = false
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
