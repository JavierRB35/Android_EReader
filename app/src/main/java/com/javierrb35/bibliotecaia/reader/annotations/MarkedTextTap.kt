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
 * In a scroll reader, reserve a verified saved mark for a short gesture,
 * but release interception on drags; open the detail during ACTION_UP as in
 * Scriptura. A small touchSlop hit area accommodates real fingertips.
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
        val maxTapDuration = ViewConfiguration.getLongPressTimeout()
        var downX = 0f
        var downY = 0f
        var active = false
        var parentReserved = false
        var downMatches = emptyList<AnnotationEntity>()

        /**
         * Pixel-perfect taps pass in an emulator, but a physical finger on
         * Samsung can land a few dp outside a thin underline or on the
         * inter-glyph boundary. Sample only inside Android touchSlop, and
         * always prefer the actual glyph before nearby offsets.
         *
         * The caller validates exact saved note anchors (and edition) so we
         * never resolve an arbitrary repeated quote or invent a note.
         */
        fun findSavedMark(x: Float, y: Float): List<AnnotationEntity> {
            fun at(px: Float, py: Float): List<AnnotationEntity> =
                marksAt(text.getOffsetForPosition(px, py))
                    .filter { it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT") }
                    .distinctBy { it.id }

            val exact = at(x, y)
            if (exact.isNotEmpty()) return exact
            val tolerance = minOf(slop.toFloat(),
                10f * text.resources.displayMetrics.density)
            val inset = tolerance * .8f
            val probes = arrayOf(
                x - inset to y, x + inset to y,
                x to y - inset, x to y + inset,
                x - inset to y - inset, x + inset to y - inset,
                x - inset to y + inset, x + inset to y + inset
            )
            return probes.asSequence().flatMap { (px, py) ->
                at(px, py).asSequence()
            }.distinctBy { it.id }.toList()
        }

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
                    downMatches = findSavedMark(downX, downY)
                    parentReserved = downMatches.isNotEmpty()
                    if (parentReserved) text.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 ||
                        abs(event.x - downX) > slop ||
                        abs(event.y - downY) > slop
                    ) {
                        active = false
                        downMatches = emptyList()
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
                    if (!stationary) {
                        downMatches = emptyList()
                        false
                    } else {
                        val upMatches = findSavedMark(event.x, event.y)
                        // The finger can move a few pixels within touchSlop;
                        // if that crosses a glyph boundary use the mark verified
                        // under ACTION_DOWN rather than silently losing the tap.
                        val matches = if (upMatches.isNotEmpty()) upMatches else downMatches
                        downMatches = emptyList()
                        if (matches.isEmpty()) false
                        else {
                            // Scriptura and BibliotecaAI <= v0.1.38 open inline.
                            // Deferred post() could race with a reader redraw or
                            // an active Android selection mode.
                            onMarks(matches)
                            true
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    active = false
                    downMatches = emptyList()
                    releaseScroll()
                    false
                }
                else -> false
            }
        }
    }
}
