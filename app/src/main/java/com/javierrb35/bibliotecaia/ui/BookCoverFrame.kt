package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** A 2:3 cover slot with the previous grid height as a cap, not a crop. */
class BookCoverFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (172 * resources.displayMetrics.density).roundToInt()
        val width = resolveSize((maxHeight * 2f / 3f).roundToInt(), widthMeasureSpec)
        val height = resolveSize((width * 1.5f).roundToInt().coerceAtMost(maxHeight), heightMeasureSpec)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
