package com.javierrb35.bibliotecaia.reader.annotations

import android.text.Spanned
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import kotlin.math.abs

/**
 * A short, stationary tap over the EXACT painted mark opens its saved annotation.
 * Long press and dragging keep Android's native selection/copy/share behaviour.
 *
 * In a scroll reader, parent interception during a small diagonal movement used
 * to drop ACTION_UP intermittently. Reserve the gesture until it is clearly a
 * scroll, and show dialogs after the touch dispatch completes.
 */
object MarkedTextTap {
    /** Only IDs attached to painted ranges can be opened; never guess by quote. */
    fun idsAt(text: CharSequence, offset: Int): List<String> {
        if (text !is Spanned || offset !in 0 until text.length) return emptyList()
        return text.getSpans(offset, offset + 1, AnnotationHitSpan::class.java)
            .filter { offset >= text.getSpanStart(it) && offset < text.getSpanEnd(it) }
            .map { it.annotationId }
            .distinct()
    }

    /**
     * Resolve a selected range against the very same hit spans that paint
     * the visible marks. An unmarked selection (or merely similar quote
     * elsewhere in the book) must never resolve to a saved annotation.
     */
    fun idsWithin(text: CharSequence, start: Int, end: Int): List<String> {
        if (text !is Spanned || start < 0 || end > text.length || end <= start)
            return emptyList()
        return text.getSpans(start, end, AnnotationHitSpan::class.java)
            .filter { text.getSpanStart(it) < end && text.getSpanEnd(it) > start }
            .map { it.annotationId }
            .distinct()
    }

    fun attach(
        text: TextView,
        marksAt: (Int) -> List<AnnotationEntity>,
        onMarks: (List<AnnotationEntity>) -> Unit
    ) {
        val slop = ViewConfiguration.get(text.context).scaledTouchSlop
        val maxTapDuration = ViewConfiguration.getLongPressTimeout() / 2
        var downX = 0f
        var downY = 0f
        var active = false
        var parentReserved = false

        fun releaseScroll() {
            if (parentReserved) text.parent?.requestDisallowInterceptTouchEvent(false)
            parentReserved = false
        }

        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    active = true
                    releaseScroll()
                    // The ScrollView must not steal a tap on a marked passage.
                    // Long presses still reach TextView's native selection handler.
                    val offset = text.getOffsetForPosition(downX, downY)
                    parentReserved = marksAt(offset).any { it.deletedAt == null }
                    if (parentReserved) text.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 ||
                        abs(event.x - downX) > slop ||
                        abs(event.y - downY) > slop
                    ) {
                        active = false
                        releaseScroll()
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val stationary = active && event.pointerCount == 1 &&
                        event.eventTime - event.downTime < maxTapDuration &&
                        abs(event.x - downX) <= slop && abs(event.y - downY) <= slop
                    active = false
                    releaseScroll()
                    if (!stationary) false else {
                        val matches = marksAt(text.getOffsetForPosition(event.x, event.y))
                            .filter { it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT") }
                            .distinctBy { it.id }
                        if (matches.isEmpty()) false
                        else {
                            // Don't display a window while TextView is finishing
                            // its touch/selection lifecycle.
                            if (text.isAttachedToWindow) text.post { onMarks(matches) }
                            else onMarks(matches)
                            true
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    active = false
                    releaseScroll()
                    false
                }
                else -> false
            }
        }
    }
}
