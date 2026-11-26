package com.javierrb35.bibliotecaia.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibrarySelectionTest {
    @Test fun selectedIdsSurviveReorderingButNotFiltering() {
        val selection = LibrarySelection()
        selection.toggle("a")
        selection.toggle("b")
        selection.retain(listOf("c", "b", "a"))
        assertEquals(setOf("a", "b"), selection.ids)
        selection.retain(listOf("c", "b"))
        assertEquals(setOf("b"), selection.ids)
        selection.toggleAll(listOf("c", "b"))
        assertEquals(setOf("b", "c"), selection.ids)
        selection.toggleAll(listOf("c", "b"))
        assertFalse(selection.active)
    }
    @Test fun batchReportsFailuresAndStopsBetweenBooks() = runBlocking {
        val called = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val result = LibraryBatch.run(listOf(1,2,3,4), { called.size == 3 }, { done, _ -> progress.add(done) }) {
            called.add(it)
            if (it == 2) error("Fallo")
        }
        assertEquals(listOf(1,2,3), called)
        assertEquals(listOf(1,2,3), progress)
        assertEquals(2, result.completed)
        assertEquals(listOf("Fallo"), result.failures)
        assertEquals(1, result.pending)
    }
}
