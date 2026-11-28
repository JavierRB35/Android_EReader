package com.javierrb35.bibliotecaia.catalog

import android.content.Context

/**
 * Preferencias de la catalogación automática (Issue #142). Desactivada por
 * defecto: cada llamada IA es un consumo de la clave del usuario.
 */
internal object AutoCatalogSettings {
    private const val PREFS = "bibliotecaia_auto_catalog"
    private const val KEY_NEW_BOOKS = "new_books_enabled"

    /** Catalogar automáticamente los libros recién importados o descargados. */
    fun newBooksEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEW_BOOKS, false)

    fun setNewBooksEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NEW_BOOKS, enabled).apply()
    }
}
