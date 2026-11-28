package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Readium and native Android share the same bundled serif asset, never download fonts. */
internal object EditorialTypeface {
    const val REGULAR = "readium/fonts/Literata-Regular.ttf"
    const val ITALIC = "readium/fonts/Literata-Italic.ttf"
    @Volatile private var cached: Typeface? = null

    fun serif(context: Context): Typeface = cached ?: synchronized(this) {
        cached ?: runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, REGULAR)
        }.getOrDefault(Typeface.SERIF).also { cached = it }
    }
}

/** Only editorial title views opt in; body text and saved reader choices remain distinct. */
class EditorialTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        val style = typeface?.style ?: Typeface.NORMAL
        setTypeface(EditorialTypeface.serif(context), style)
    }
}
