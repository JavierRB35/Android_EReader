package com.javierrb35.bibliotecaia.reader.theme

import android.content.res.Configuration

/**
 * Scriptura DisplayModes.SYSTEM adapted without changing legacy EPUB/MOBI
 * preference IDs. IDs 0..3 already mean OLED / dark / sepia / light.
 * ID 4 follows Android and never modifies the ebook's original contents.
 */
object ReaderAppearance {
    const val SYSTEM = 4
    val labels = listOf("OLED", "Oscuro", "Sepia", "Claro", "Automático")

    fun resolve(saved: Int, uiMode: Int): Int = when (saved) {
        0, 1, 2, 3 -> saved
        SYSTEM -> if (uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) 1 else 3
        else -> 0
    }
}
