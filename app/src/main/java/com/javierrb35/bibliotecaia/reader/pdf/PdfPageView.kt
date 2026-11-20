package com.javierrb35.bibliotecaia.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Single page, bounded bitmap; pinch zoom, pan and double-tap reset. */
class PdfPageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()
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
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
    })
    init { contentDescription = "Página PDF. Pellizca para ampliar y arrastra para desplazarte."; isFocusable = true }
    fun display(value: Bitmap) {
        // The render thread may still hold the previous display list. Let GC release its bitmap.
        bitmap = value; fit()
    }
    /** The visible bounded page bitmap; callers must not recycle it. */
    fun currentBitmap(): Bitmap? = bitmap
    fun clear() { bitmap = null; invalidate() }
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
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
