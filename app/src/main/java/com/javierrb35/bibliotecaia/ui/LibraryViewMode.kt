package com.javierrb35.bibliotecaia.ui

/** Three catalogue densities; old boolean preferences retain their original meaning. */
internal enum class LibraryViewMode {
    GRID, CARDS, COMPACT;
    fun next(): LibraryViewMode = entries[(ordinal + 1) % entries.size]
    companion object {
        fun restore(saved: String?, legacyGrid: Boolean): LibraryViewMode =
            entries.firstOrNull { it.name == saved } ?: if (legacyGrid) GRID else CARDS
    }
}
