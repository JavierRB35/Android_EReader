package com.javierrb35.bibliotecaia.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewModeTest {
    @Test fun oldPreferencesAndThreeModeCycle() {
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.restore(null, true))
        assertEquals(LibraryViewMode.CARDS, LibraryViewMode.restore(null, false))
        assertEquals(LibraryViewMode.COMPACT, LibraryViewMode.restore("COMPACT", true))
        assertEquals(LibraryViewMode.CARDS, LibraryViewMode.restore("invalid", false))
        for (mode in LibraryViewMode.entries) {
            assertEquals(mode, mode.next().next().next())
            assertEquals(3, setOf(mode, mode.next(), mode.next().next()).size)
        }
    }
}
