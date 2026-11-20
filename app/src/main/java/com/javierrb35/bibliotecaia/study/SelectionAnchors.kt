package com.javierrb35.bibliotecaia.study

/**
 * Deterministic annotation anchoring. A quote is only mapped to its original
 * position; a matching sentence elsewhere must never be marked by accident.
 */
object SelectionAnchors {
    fun exactMatch(text: String, absoluteOffset: Int, quote: String): Boolean =
        quote.isNotBlank() &&
            absoluteOffset >= 0 &&
            absoluteOffset <= text.length - quote.length &&
            text.regionMatches(absoluteOffset, quote, 0, quote.length)

    /** Inclusive start, exclusive end; clipping is only for drawing visible chunks. */
    fun visibleRange(text: String, absoluteOffset: Int, quote: String,
                     visibleStart: Int, visibleEnd: Int): IntRange? {
        if (!exactMatch(text, absoluteOffset, quote) ||
            visibleStart < 0 || visibleEnd > text.length || visibleStart >= visibleEnd) return null
        val start = maxOf(absoluteOffset, visibleStart)
        val end = minOf(absoluteOffset + quote.length, visibleEnd)
        return if (start >= end) null else (start - visibleStart) until (end - visibleStart)
    }
}
