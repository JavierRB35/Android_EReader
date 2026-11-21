package com.javierrb35.bibliotecaia.reader.annotations

import android.text.TextPaint
import android.text.style.CharacterStyle

/**
 * Invisible identity for the SAME validated range that is visibly underlined.
 * Taps read these spans instead of recalculating a locator or searching quote text.
 * Does not change text metrics, colors, selection gestures or the book original.
 */
class AnnotationHitSpan(val annotationId: String) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) = Unit
}
