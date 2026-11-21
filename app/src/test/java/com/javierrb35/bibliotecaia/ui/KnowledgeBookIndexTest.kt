package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBookIndexTest {
    private fun note(id: String, book: String?, kind: String, quote: String = "") =
        AnnotationEntity(id, book, "{\"format\":\"EPUB\"}", quote,
            if (kind == "NOTE") "Texto" else "", kind, 0, 1L, 1L)

    private val entries = listOf(
        note("one", "book-A", "NOTE", "A"),
        note("two", "book-B", "HIGHLIGHT", "B"),
        note("three", "book-A", "HIGHLIGHT", "C"),
        note("idea", null, "NOTE")
    )

    @Test fun showsRealBookCountsWithoutInventingSources() {
        val choices = KnowledgeBookIndex.choices(entries, mapOf(
            "book-A" to "Obra A", "book-B" to "Obra B"))
        assertEquals(KnowledgeBookIndex.ALL, choices.first().id)
        assertEquals(4, choices.first().count)
        assertEquals(2, choices.first { it.id == "book-A" }.count)
        assertEquals(1, choices.first { it.id == KnowledgeBookIndex.INDEPENDENT }.count)
    }

    @Test fun filtersByBookWithoutChangingStoredNotes() {
        assertEquals(2, KnowledgeBookIndex.byBook(entries, "book-A").size)
        assertEquals(1, KnowledgeBookIndex.byBook(entries, KnowledgeBookIndex.INDEPENDENT).size)
        assertEquals(entries.size, KnowledgeBookIndex.byBook(entries, KnowledgeBookIndex.ALL).size)
        assertTrue(KnowledgeBookIndex.byBook(entries, "not-in-library").isEmpty())
    }

    @Test fun fragmentAndIdeaFiltersHaveNoFalseMatches() {
        assertEquals(3, KnowledgeBookIndex.byKind(entries, "QUOTE").size)
        assertEquals(listOf("idea"), KnowledgeBookIndex.byKind(entries, "IDEAS").map { it.id })
    }
}
