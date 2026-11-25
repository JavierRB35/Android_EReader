package com.javierrb35.bibliotecaia.reader.annotations

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A TextView in an outer ScrollView cannot reliably drive that viewport from
 * Android's private Editor handle windows. Supply explicit draggable endpoints
 * without replacing native long-press, selection spans or contextual actions.
 * One range is always local to the currently rendered text/anchor.
 */
internal class EdgeSelectionController(
    private val scroll: SavedMarkScrollView,
    private val text: TextView
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val density = text.resources.displayMetrics.density
    private val size = (44f * density).roundToInt().coerceAtLeast(1)
    private val origin = IntArray(2)
    private val textOrigin = IntArray(2)
    private val viewport = Rect()
    private val start = Grip(true)
    private val end = Grip(false)
    private var active: Grip? = null
    private var fixed = 0
    private var buffer: Spannable? = null
    private var pointerX = 0f
    private var pointerY = 0f
    private var gripOffsetX = 0f
    private var gripOffsetY = 0f
    private var moved = false
    private var lastFrame = 0L
    private var remainder = 0f
    private var observer: ViewTreeObserver? = null
    private var disposed = false
    private var unavailable = false
    val isDragging: Boolean get() = active != null

    // Expose real popup coordinates to the instrumented input-pipeline test.
    internal fun gripCenterOnScreen(leading: Boolean): Pair<Float, Float>? {
        val grip = if (leading) start else end
        return if (grip.popup.isShowing) grip.centerOnScreen() else null
    }

    // Use public APIs only. Older Android retains its native handles as a
    // fallback; the additional handles are offset horizontally on that API.
    private val originalLeft = if (Build.VERSION.SDK_INT >= 29) text.textSelectHandleLeft else null
    private val originalRight = if (Build.VERSION.SDK_INT >= 29) text.textSelectHandleRight else null
    private var nativeHidden = false

    init {
        scroll.addOnAttachStateChangeListener(this)
        if (scroll.isAttachedToWindow) onViewAttachedToWindow(scroll)
    }

    override fun onViewAttachedToWindow(v: View) {
        if (disposed) return
        observer = scroll.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onViewDetachedFromWindow(v: View) {
        removeObserver()
        reset()
    }

    private fun removeObserver() {
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
        observer = null
    }

    fun dispose() {
        disposed = true
        removeObserver()
        scroll.removeOnAttachStateChangeListener(this)
        reset()
    }

    private fun hideNative() {
        if (Build.VERSION.SDK_INT >= 29 && !nativeHidden) {
            fun transparent() = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setSize(1, 1)
            }
            text.setTextSelectHandleLeft(transparent())
            text.setTextSelectHandleRight(transparent())
            nativeHidden = true
        }
    }

    private fun restoreNative() {
        if (Build.VERSION.SDK_INT >= 29 && nativeHidden) {
            originalLeft?.let { text.setTextSelectHandleLeft(it) }
            originalRight?.let { text.setTextSelectHandleRight(it) }
            nativeHidden = false
        }
    }

    private fun reset() {
        finishDrag()
        start.hide()
        end.hide()
        restoreNative()
    }

    private fun visibleViewport(): Boolean {
        if (!scroll.isShown || !text.isShown || !scroll.hasWindowFocus() ||
            !scroll.getLocalVisibleRect(viewport)) return false
        scroll.getLocationOnScreen(origin)
        // getLocalVisibleRect uses the scrolled content coordinate system.
        viewport.offset(origin[0] - scroll.scrollX, origin[1] - scroll.scrollY)
        return viewport.width() > 0 && viewport.height() > 0
    }

    private fun range(): Pair<Int, Int>? {
        val a = text.selectionStart
        val b = text.selectionEnd
        if (a < 0 || b < 0 || a == b || maxOf(a, b) > text.text.length) return null
        return minOf(a, b) to maxOf(a, b)
    }

    override fun onPreDraw(): Boolean {
        if (disposed || unavailable) return true
        val selection = range()
        if (selection == null || !text.hasFocus() || !visibleViewport() ||
            (active != null && text.text !== buffer)) {
            reset()
            return true
        }
        val layout = text.layout ?: return true
        text.getLocationOnScreen(textOrigin)
        try {
            // Native handles are separate sub-panels. Hide them before adding
            // ours above the text; reset restores them if the popup cannot open.
            hideNative()
            start.place(selection.first, layout)
            end.place(selection.second, layout)
        } catch (_: android.view.WindowManager.BadTokenException) {
            unavailable = true
            reset()
        }
        return true
    }

    private inner class Grip(val leading: Boolean) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffd4b483.toInt() }
        private val view = object : View(text.context) {
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                canvas.drawRect(cx - density, 0f, cx + density, 12f * density, paint)
                canvas.drawCircle(cx, 14f * density, 8f * density, paint)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean = touch(this@Grip, event)
        }.apply {
            contentDescription = if (leading) "Inicio de selección · arrastrar" else "Fin de selección · arrastrar"
            isClickable = true
        }
        val popup = PopupWindow(view, size, size, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isTouchable = true
            setWindowLayoutType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL)
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
        }
        fun centerOnScreen(): Pair<Float, Float> {
            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            return (pos[0] + size / 2f) to (pos[1] + 14f * density)
        }
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE

        fun place(offset: Int, layout: android.text.Layout) {
            val line = layout.getLineForOffset(offset)
            val anchorX = textOrigin[0] + text.totalPaddingLeft - text.scrollX +
                layout.getPrimaryHorizontal(offset)
            val anchorY = textOrigin[1] + text.totalPaddingTop - text.scrollY +
                layout.getLineBottom(line)
            if (active !== this && (anchorY < viewport.top || anchorY >= viewport.bottom)) {
                hide()
                return
            }
            // Separate the touch targets even for a one-character selection.
            // On older Android leave additional room for the system handles.
            val shift = (if (leading) -1f else 1f) *
                (if (Build.VERSION.SDK_INT < 29) 26f else 12f) * density
            var x = (anchorX + shift - size / 2f).roundToInt()
                .coerceIn(viewport.left, maxOf(viewport.left, viewport.right - size))
            var y = anchorY.coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - size))
            // The active popup remains attached to the same window throughout
            // the gesture, including when the endpoint moves off the viewport.
            if (Build.VERSION.SDK_INT < 29) {
                val frame = Rect()
                scroll.getWindowVisibleDisplayFrame(frame)
                x -= frame.left
                y -= frame.top
            }
            if (!popup.isShowing) {
                popup.showAtLocation(scroll, Gravity.TOP or Gravity.LEFT, x, y)
            } else if (lastX != x || lastY != y) {
                popup.update(x, y, size, size)
            }
            lastX = x
            lastY = y
        }

        fun hide() {
            if (popup.isShowing) popup.dismiss()
            lastX = Int.MIN_VALUE
            lastY = Int.MIN_VALUE
        }
    }

    private fun touch(grip: Grip, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val selection = range() ?: return false
                val spannable = text.text as? Spannable ?: return false
                if (active != null || !visibleViewport()) return false
                active = grip
                fixed = if (grip.leading) selection.second else selection.first
                buffer = spannable
                pointerX = event.rawX
                pointerY = event.rawY
                text.getLocationOnScreen(textOrigin)
                val layout = text.layout ?: run { finishDrag(); return false }
                val offset = if (grip.leading) selection.first else selection.second
                val line = layout.getLineForOffset(offset)
                gripOffsetX = pointerX - (textOrigin[0] + text.totalPaddingLeft -
                    text.scrollX + layout.getPrimaryHorizontal(offset))
                gripOffsetY = pointerY - (textOrigin[1] + text.totalPaddingTop -
                    text.scrollY + layout.getLineBottom(line) - 1)
                moved = false
                lastFrame = SystemClock.uptimeMillis()
                remainder = 0f
                scroll.parent?.requestDisallowInterceptTouchEvent(true)
                scroll.postOnAnimation(frame)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (active === grip) {
                moved = true
                pointerX = event.rawX
                pointerY = event.rawY
                if (visibleViewport()) updateRange()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> if (active === grip) {
                if (event.actionMasked == MotionEvent.ACTION_UP && moved && visibleViewport()) {
                    pointerX = event.rawX
                    pointerY = event.rawY
                    updateRange()
                }
                finishDrag()
                scroll.invalidate()
                return true
            }
        }
        return active === grip
    }

    private fun finishDrag() {
        if (active != null) scroll.parent?.requestDisallowInterceptTouchEvent(false)
        active = null
        buffer = null
        remainder = 0f
        scroll.removeCallbacks(frame)
    }

    private fun updateRange() {
        val grip = active ?: return
        val current = buffer ?: return
        if (text.text !== current || current.isEmpty()) {
            finishDrag()
            return
        }
        text.getLocationOnScreen(textOrigin)
        val x = (pointerX - gripOffsetX).coerceIn(viewport.left.toFloat(), (viewport.right - 1).toFloat())
        val y = (pointerY - gripOffsetY)
            .coerceIn(viewport.top.toFloat(), (viewport.bottom - 1).toFloat())
        val offset = text.getOffsetForPosition(x - textOrigin[0], y - textOrigin[1])
        // Do not cross or collapse the opposite handle; keep Android's
        // layout-derived character boundary (including surrogate pairs).
        if ((grip.leading && offset >= fixed) || (!grip.leading && offset <= fixed)) return
        val a = if (grip.leading) offset else fixed
        val b = if (grip.leading) fixed else offset
        if (text.selectionStart != a || text.selectionEnd != b) {
            Selection.setSelection(current, a, b)
            text.invalidate()
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (active == null) return
            if (!visibleViewport() || range() == null || text.text !== buffer) {
                reset()
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastFrame).coerceIn(0L, 50L) / 1000f
            lastFrame = now
            val velocity = SelectionEdgeSpeed.velocity(
                pointerY - viewport.top, viewport.height().toFloat(), density)
            if (velocity == 0f) {
                remainder = 0f
            } else {
                val delta = velocity * elapsed + remainder
                val pixels = delta.toInt()
                remainder = delta - pixels
                if (pixels != 0) {
                    val before = scroll.scrollY
                    scroll.scrollBy(0, pixels)
                    if (scroll.scrollY != before) { moved = true; updateRange() }
                }
            }
            if (active != null) scroll.postOnAnimation(this)
        }
    }
}
