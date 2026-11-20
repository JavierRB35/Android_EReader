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
    private var onMarkedTap: ((List<String>) -> Unit)? = null
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
    fun onMarkedTap(listener: (List<String>) -> Unit) { onMarkedTap = listener }
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
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX; panY -= distanceY; constrainPan(); invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val image = bitmap
            if (image != null && marks.isNotEmpty()) {
                val inverse = Matrix()
                if (matrix.invert(inverse)) {
                    val p = floatArrayOf(e.x, e.y)
                    inverse.mapPoints(p)
                    val nx = p[0] / image.width
                    val ny = p[1] / image.height
                    // Same position can contain a NOTE and an underline (or
                    // several notes). Scriptura lets the reader choose which
                    // annotation to open instead of silently taking the last.
                    val hits = marks.asReversed().filter {
                        nx >= it.bounds.left && nx <= it.bounds.right &&
                            ny >= it.bounds.top && ny <= it.bounds.bottom
                    }.map { it.annotationId }.distinct()
                    if (hits.isNotEmpty()) {
                        onMarkedTap?.invoke(hits)
                        return true
                    }
                }
            }
            performClick()
            return true
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
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
