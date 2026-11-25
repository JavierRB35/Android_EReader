package com.javierrb35.bibliotecaia.reader.annotations

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.assertTrue

/**
 * Exercise the complete Android input pipeline, including the Activity window,
 * ancestor interception and TextView's native selection handling.
 *
 * Direct TextView.dispatchTouchEvent() / ScrollView.dispatchTouchEvent() tests
 * bypass part of that pipeline; they cannot establish a physical tap works.
 */
internal object PlatformNoteTap {
    fun tapOnText(instrumentation: Instrumentation, view: TextView, offset: Int, durationMs: Long = 75L) =
        touchOnText(instrumentation, view, offset, false, durationMs)

    fun holdOnText(instrumentation: Instrumentation, view: TextView, offset: Int) =
        touchOnText(instrumentation, view, offset, true, 75L)

    private fun touchOnText(
        instrumentation: Instrumentation, view: TextView, offset: Int, hold: Boolean, durationMs: Long
    ) {
        val point = FloatArray(2)
        instrumentation.runOnMainSync {
            val layout = requireNotNull(view.layout) { "Reader text has no layout" }
            require(offset in 0 until view.text.length) { "Invalid text offset" }
            val line = layout.getLineForOffset(offset)
            // Hit the middle of the actual glyph rather than a cursor boundary.
            val x0 = layout.getPrimaryHorizontal(offset)
            val x1 = layout.getPrimaryHorizontal((offset + 1).coerceAtMost(view.text.length))
            val localX = view.totalPaddingLeft + (x0 + x1) / 2f
            val localY = view.totalPaddingTop +
                (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val windowPos = IntArray(2)
            view.getLocationOnScreen(windowPos)
            point[0] = windowPos[0] + localX
            point[1] = windowPos[1] + localY
            val visible = Rect()
            assertTrue("Selected marked character must be visible on screen",
                view.getGlobalVisibleRect(visible))
            assertTrue("Selected marked character is outside visible bounds: $visible / ${point.toList()}",
                point[0] >= visible.left && point[0] < visible.right &&
                    point[1] >= visible.top && point[1] < visible.bottom)
        }
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime,
            MotionEvent.ACTION_DOWN, point[0], point[1], 0)
        try {
            instrumentation.sendPointerSync(down)
        } finally {
            down.recycle()
        }
        SystemClock.sleep(if (hold) android.view.ViewConfiguration.getLongPressTimeout().toLong() + 240L else durationMs.coerceAtMost(android.view.ViewConfiguration.getLongPressTimeout().toLong() - 80L).coerceAtLeast(1L))
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, point[0], point[1], 0)
        try {
            instrumentation.sendPointerSync(up)
        } finally {
            up.recycle()
        }
        instrumentation.waitForIdleSync()
    }
}
