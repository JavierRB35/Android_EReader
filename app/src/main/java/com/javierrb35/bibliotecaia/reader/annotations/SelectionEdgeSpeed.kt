package com.javierrb35.bibliotecaia.reader.annotations

import kotlin.math.min

/** Signed vertical velocity, in px/s. Zero outside the edge zones. */
internal object SelectionEdgeSpeed {
    fun velocity(y: Float, height: Float, density: Float): Float {
        if (height <= 0f || density <= 0f) return 0f
        val zone = min(72f * density, height / 3f)
        val upper = ((zone - y) / zone).coerceIn(0f, 1f)
        val lower = ((y - (height - zone)) / zone).coerceIn(0f, 1f)
        return (lower * lower - upper * upper) * 1400f * density
    }
}
