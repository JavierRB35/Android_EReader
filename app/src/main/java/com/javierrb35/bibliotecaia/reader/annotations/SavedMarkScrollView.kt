package com.javierrb35.bibliotecaia.reader.annotations

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
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
    private val shortTapMs = ViewConfiguration.getLongPressTimeout() / 2
    private val horizontalTolerance = 5f * resources.displayMetrics.density
    private var textView: TextView? = null
    private var marksAt: ((Int) -> List<AnnotationEntity>)? = null
    private var onMarkTap: ((List<AnnotationEntity>) -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var startScrollY = 0
    private var possibleTap = false
    private var downMarks: List<AnnotationEntity> = emptyList()

    fun attach(
        text: TextView,
        marksAt: (Int) -> List<AnnotationEntity>,
        onMarks: (List<AnnotationEntity>) -> Unit
    ) {
        textView = text
        this.marksAt = marksAt
        onMarkTap = onMarks
    }

    private fun at(x: Float, y: Float): List<AnnotationEntity> {
        val text = textView ?: return emptyList()
        val resolve = marksAt ?: return emptyList()
        if (!text.isShown || text.width <= 0 || text.height <= 0) return emptyList()
        val localX = x + scrollX - text.left
        val localY = y + scrollY - text.top
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
        val offset = text.getOffsetForPosition(localX, localY)
        return resolve(offset).filter {
            it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
        }.distinctBy { it.id }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startScrollY = scrollY
                possibleTap = event.pointerCount == 1
                downMarks = if (possibleTap) at(event.x, event.y) else emptyList()
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 ||
                    abs(event.x - startX) > touchSlop ||
                    abs(event.y - startY) > touchSlop ||
                    scrollY != startScrollY) {
                    possibleTap = false
                    downMarks = emptyList()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                possibleTap = false
                downMarks = emptyList()
            }
        }

        // Let Android handle native scrolling, long-press selection and copy.
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return handled

        val eligible = possibleTap && event.pointerCount == 1 &&
            event.eventTime - event.downTime < shortTapMs &&
            abs(event.x - startX) <= touchSlop &&
            abs(event.y - startY) <= touchSlop &&
            scrollY == startScrollY
        possibleTap = false
        if (!eligible || downMarks.isEmpty()) {
            downMarks = emptyList()
            return handled
        }
        val upIds = at(event.x, event.y).map { it.id }.toSet()
        val matched = if (upIds.isEmpty()) downMarks
            else downMarks.filter { it.id in upIds }
        downMarks = emptyList()
        if (matched.isEmpty()) return handled

        // Dispatch is now finished: showing a modal cannot interrupt the
        // child's ACTION_UP or be dismissed by the selection's ActionMode.
        onMarkTap?.invoke(matched)
        return true
    }
}
