package com.javierrb35.bibliotecaia.reader.annotations

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import kotlin.math.abs

/**
 * Scriptura's short-tap rule: never consume DOWN, long-press, selection
 * handles or dragging. Only a fast, stationary UP on a marked character
 * opens its associated annotation(s).
 */
object MarkedTextTap {
    fun attach(
        text: TextView,
        marksAt: (Int) -> List<AnnotationEntity>,
        onMarks: (List<AnnotationEntity>) -> Unit
    ) {
        val slop = ViewConfiguration.get(text.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (event.eventTime - event.downTime >=
                            ViewConfiguration.getLongPressTimeout() / 2 ||
                        abs(event.x - downX) > slop ||
                        abs(event.y - downY) > slop
                    ) false else {
                        val matches = marksAt(text.getOffsetForPosition(event.x, event.y))
                            .filter { it.deletedAt == null }
                            .distinctBy { it.id }
                        if (matches.isEmpty()) false
                        else { onMarks(matches); true }
                    }
                }
                else -> false
            }
        }
    }
}
