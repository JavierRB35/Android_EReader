package com.javierrb35.bibliotecaia.reader.gestures

import kotlin.math.abs
import kotlin.math.max

/**
 * Single-pointer directional lock. Once vertical movement wins, a slightly
 * diagonal scroll can NEVER become a page turn later in that same gesture.
 * Only a deliberate horizontal travel returns a navigation direction.
 */
internal class HorizontalSwipeGesture(
    private val slopPx: Float,
    private val minTravelPx: Float
) {
    private var startX = 0f
    private var startY = 0f
    // 0 undecided, 1 horizontal, 2 vertical, 3 cancelled/multitouch.
    private var axis = 3

    fun down(x: Float, y: Float) {
        startX = x; startY = y; axis = 0
    }

    fun move(x: Float, y: Float, pointers: Int = 1): Boolean {
        if (pointers != 1) { axis = 3; return false }
        if (axis == 0) {
            val dx = abs(x - startX)
            val dy = abs(y - startY)
            if (max(dx, dy) > slopPx * 1.6f) {
                axis = when {
                    dx >= dy * 1.65f -> 1
                    dy >= dx -> 2
                    else -> 0
                }
            }
        }
        return axis == 1
    }

    /** -1 = previous (swipe right); +1 = next (swipe left); 0 = no turn. */
    fun up(x: Float, y: Float, pointers: Int = 1): Int {
        val wasHorizontal = move(x, y, pointers)
        val dx = x - startX
        val dy = abs(y - startY)
        val result = if (wasHorizontal && abs(dx) >= minTravelPx &&
            dy * 2f <= abs(dx)) {
            if (dx > 0f) -1 else 1
        } else 0
        cancel()
        return result
    }

    fun cancel() { axis = 3 }
}
