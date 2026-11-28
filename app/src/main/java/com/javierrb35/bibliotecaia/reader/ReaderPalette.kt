package com.javierrb35.bibliotecaia.reader

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton

/** Shared visual palette; indices retain the persisted EPUB/MOBI theme contract. */
internal enum class ReaderPalette(
    val background: Int, val foreground: Int, val secondary: Int,
    val accent: Int, val chrome: Int, val light: Boolean
) {
    OLED(0xff000000.toInt(), 0xffddd4c2.toInt(), 0xff9a9387.toInt(), 0xffe9c282.toInt(), 0xff0a0a09.toInt(), false),
    NIGHT(0xff181a1b.toInt(), 0xffe0d8c8.toInt(), 0xff9c9588.toInt(), 0xffe9c282.toInt(), 0xff202325.toInt(), false),
    SEPIA(0xfff1e5cc.toInt(), 0xff332b20.toInt(), 0xff6b5d49.toInt(), 0xff7a4a12.toInt(), 0xffe8dabc.toInt(), true),
    LIGHT(0xfffaf8f2.toInt(), 0xff24221f.toInt(), 0xff6a655c.toInt(), 0xff8a5a12.toInt(), 0xfff0ede4.toInt(), true);

    fun applySystemBars(window: Window) {
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    fun applyChrome(container: ViewGroup) {
        container.setBackgroundColor(chrome)
        fun tint(view: View) {
            when (view) {
                is MaterialButton -> {
                    view.backgroundTintList = ColorStateList.valueOf(chrome)
                    view.setTextColor(accent)
                    view.strokeColor = ColorStateList.valueOf(secondary)
                    view.iconTint = ColorStateList.valueOf(accent)
                }
                is TextView -> view.setTextColor(foreground)
                is ViewGroup -> for (i in 0 until view.childCount) tint(view.getChildAt(i))
            }
        }
        tint(container)
    }

    companion object {
        fun at(index: Int): ReaderPalette = values().getOrElse(index) { OLED }
    }
}
