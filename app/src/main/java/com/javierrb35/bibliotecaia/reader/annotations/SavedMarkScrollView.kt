package com.javierrb35.bibliotecaia.reader.annotations

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import com.javierrb35.bibliotecaia.reader.gestures.HorizontalSwipeGesture
import android.widget.ScrollView
import android.widget.TextView
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import kotlin.math.abs

/**
 * Receives the entire gesture at the scrolling parent's dispatch boundary.
 *
 * Samsung/Android's selectable TextView may consume, cancel or replace a child
 * touch sequence while initiating selection. Listening only on TextView's
 * OnTouchListener therefore makes an already-painted saved note untappable.
 * The parent always sees the dispatched gesture, even if its child consumes it.
 *
 * This is deliberately independent of transient CharacterStyle spans. The
 * supplied marksAt lambda MUST use the exact verified ranges used for painting.
 * It never searches for a similar quote at another position.
 */
class SavedMarkScrollView(context: Context) : ScrollView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // A deliberate fingertip tap on Samsung may last 250-450ms; the old
    // half-long-press cutoff silently discarded those taps. A long-press
    // remains Android's own selection gesture at its configured timeout.
    private val shortTapMs = ViewConfiguration.getLongPressTimeout()
    private val horizontalTolerance = 5f * resources.displayMetrics.density
    private var textView: TextView? = null
    private var marksAt: ((Int) -> List<AnnotationEntity>)? = null
    private var onMarkTap: ((List<AnnotationEntity>) -> Unit)? = null
    private var onUnmarkedTap: (() -> Unit)? = null
    private var onMarkedLongPress: ((List<AnnotationEntity>) -> Unit)? = null
    private var onHorizontalSwipe: ((Int) -> Unit)? = null
    private val swipe = HorizontalSwipeGesture(
        touchSlop.toFloat(), maxOf(72f * resources.displayMetrics.density,
            resources.displayMetrics.widthPixels * .17f))
    private var horizontalCaptured = false
    private var longPressTriggered = false
    private var longPressTask: Runnable? = null
    private var startX = 0f
    private var startY = 0f
    private var startScrollY = 0
    private var possibleTap = false
    private var downMarks: List<AnnotationEntity> = emptyList()

    fun attach(
        text: TextView,
        marksAt: (Int) -> List<AnnotationEntity>,
        onMarks: (List<AnnotationEntity>) -> Unit,
        onUnmarkedTap: (() -> Unit)? = null,
        onMarkedLongPress: ((List<AnnotationEntity>) -> Unit)? = null,
        onHorizontalSwipe: ((Int) -> Unit)? = null
    ) {
        textView = text
        this.marksAt = marksAt
        onMarkTap = onMarks
        this.onUnmarkedTap = onUnmarkedTap
        this.onMarkedLongPress = onMarkedLongPress
        this.onHorizontalSwipe = onHorizontalSwipe
    }

    private fun at(x: Float, y: Float): List<AnnotationEntity> {
        val text = textView ?: return emptyList()
        val resolve = marksAt ?: return emptyList()
        if (!text.isShown || text.width <= 0 || text.height <= 0) return emptyList()
        // Convert through ALL nested descendants, not just a direct child.
        // PDF OCR uses ScrollView > padded LinearLayout > TextView; subtracting
        // only text.left/top used to hit a different word after scrolling.
        val bounds = Rect(0, 0, text.width, text.height)
        offsetDescendantRectToMyCoords(text, bounds)
        val localX = x - bounds.left
        val localY = y - bounds.top
        if (localX < 0f || localY < 0f || localX >= text.width || localY >= text.height)
            return emptyList()
        // getOffsetForPosition() clamps taps in blank margins to a character.
        // Require a real line hit, so only a painted passage itself is clickable.
        val layout = text.layout ?: return emptyList()
        val textX = localX - text.totalPaddingLeft + text.scrollX
        val line = layout.getLineForVertical(
            (localY - text.totalPaddingTop + text.scrollY).toInt().coerceAtLeast(0))
        if (textX < layout.getLineLeft(line) - horizontalTolerance ||
            textX > layout.getLineRight(line) + horizontalTolerance)
            return emptyList()
        // A real finger may land slightly before a glyph/underline. Only
        // probe within the system's touchSlop; never search similar quotes.
        fun verified(px: Float, py: Float): List<AnnotationEntity> =
            resolve(text.getOffsetForPosition(px, py)).filter {
                it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
            }.distinctBy { it.id }
        val exact = verified(localX, localY)
        if (exact.isNotEmpty()) return exact
        val delta = minOf(touchSlop.toFloat(), 10f * resources.displayMetrics.density) * .8f
        val probes = arrayOf(
            localX - delta to localY, localX + delta to localY,
            localX to localY - delta, localX to localY + delta,
            localX - delta to localY - delta, localX + delta to localY - delta,
            localX - delta to localY + delta, localX + delta to localY + delta
        )
        return probes.asSequence().filter { (px, py) ->
            px in 0f..text.width.toFloat() && py in 0f..text.height.toFloat()
        }.flatMap { (px, py) -> verified(px, py).asSequence() }
            .distinctBy { it.id }.toList()
    }

    private fun cancelMarkedHold() {
        longPressTask?.let { removeCallbacks(it) }
        longPressTask = null
    }

    /**
     * Recognize a marked-note hold before the selectable TextView's own long
     * press, cancelling its touch sequence so a selection toolbar cannot
     * appear on top of the saved-note modal.
     */
    private fun scheduleMarkedHold() {
        val callback = onMarkedLongPress ?: return
        if (downMarks.isEmpty()) return
        val captured = downMarks
        val pending = Runnable {
            if (possibleTap && !horizontalCaptured && !longPressTriggered &&
                scrollY == startScrollY) {
                longPressTriggered = true
                possibleTap = false
                val now = SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL,
                    startX, startY, 0)
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                callback(captured)
            }
        }
        longPressTask = pending
        // Enqueue before the TextView sees ACTION_DOWN, so this runs first
        // at the platform's own long-press threshold.
        postDelayed(pending, shortTapMs.toLong())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMarkedHold()
                swipe.down(event.x, event.y)
                horizontalCaptured = false
                longPressTriggered = false
                startX = event.x
                startY = event.y
                startScrollY = scrollY
                possibleTap = event.pointerCount == 1
                downMarks = if (possibleTap) at(event.x, event.y) else emptyList()
                scheduleMarkedHold()
            }
            MotionEvent.ACTION_MOVE -> {
                if (horizontalCaptured) return true
                if (onHorizontalSwipe != null &&
                    swipe.move(event.x, event.y, event.pointerCount)) {
                    // Child TextView/selection receives cancellation, not an
                    // ACTION_UP that could trigger a contextual action.
                    cancelMarkedHold()
                    possibleTap = false
                    downMarks = emptyList()
                    horizontalCaptured = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                    return true
                }
                if (event.pointerCount != 1 ||
                    abs(event.x - startX) > touchSlop ||
                    abs(event.y - startY) > touchSlop ||
                    abs(scrollY - startScrollY) > touchSlop / 2) {
                    cancelMarkedHold()
                    possibleTap = false
                    downMarks = emptyList()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                swipe.cancel()
                cancelMarkedHold()
                possibleTap = false
                downMarks = emptyList()
            }
            MotionEvent.ACTION_CANCEL -> {
                swipe.cancel()
                cancelMarkedHold()
                possibleTap = false
                downMarks = emptyList()
                horizontalCaptured = false
                longPressTriggered = false
            }
            MotionEvent.ACTION_UP -> {
                cancelMarkedHold()
                if (horizontalCaptured) {
                    val turn = swipe.up(event.x, event.y, event.pointerCount)
                    horizontalCaptured = false
                    if (turn != 0) onHorizontalSwipe?.invoke(turn)
                    downMarks = emptyList()
                    return true
                }
                swipe.cancel()
                if (longPressTriggered) {
                    longPressTriggered = false
                    downMarks = emptyList()
                    return true
                }
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return handled

        val eligible = possibleTap && event.pointerCount == 1 &&
            event.eventTime - event.downTime < shortTapMs &&
            abs(event.x - startX) <= touchSlop &&
            abs(event.y - startY) <= touchSlop &&
            abs(scrollY - startScrollY) <= touchSlop / 2
        possibleTap = false
        if (!eligible) {
            downMarks = emptyList()
            return handled
        }
        if (onMarkedLongPress != null) {
            // New contract: ALL short taps toggle focus (including marks);
            // only a long press on an annotation opens the modal.
            downMarks = emptyList()
            onUnmarkedTap?.invoke()
            return handled || onUnmarkedTap != null
        }
        if (downMarks.isEmpty()) {
            onUnmarkedTap?.invoke()
            return handled || onUnmarkedTap != null
        }
        val upIds = at(event.x, event.y).map { it.id }.toSet()
        val matched = if (upIds.isEmpty()) downMarks
            else downMarks.filter { it.id in upIds }
        downMarks = emptyList()
        if (matched.isEmpty()) return handled
        onMarkTap?.invoke(matched)
        return true
    }
}
