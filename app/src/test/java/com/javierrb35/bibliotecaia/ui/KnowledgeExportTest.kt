package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test

class KnowledgeExportTest {
    private fun note(id: String, book: String? = null, deleted: Long? = null) =
        AnnotationEntity(id, book, "loc:$id", "cita\nsegunda línea", "reflexión $id", "NOTE", 0, 1L, 2L, deleted)

    @Test fun selectedExportNeverIncludesUnselectedNotes() {
        val a = note("a", "book")
        val b = note("b")
        val trashed = note("t", deleted = 3L)
        val all = listOf(a, b, a, trashed)
        assertEquals(listOf(a, b), KnowledgeExport.scoped(all, null))
        assertEquals(listOf(b), KnowledgeExport.scoped(all, setOf("b")))
        assertEquals(listOf(trashed), KnowledgeExport.scoped(all, setOf("t")))
        assertTrue(KnowledgeExport.scoped(all, emptySet()).isEmpty())
    }

    @Test fun markdownAndTextPreserveOriginalAndReflectionSeparately() {
        val notes = listOf(note("a", "book"))
        val markdown = KnowledgeExport.render(notes, mapOf("book" to "Obra"), mapOf("book" to "Autora"), true)
        val plain = KnowledgeExport.render(notes, mapOf("book" to "Obra"), mapOf("book" to "Autora"), false)
        assertTrue(markdown.contains("> cita\n> segunda línea"))
        assertTrue(plain.contains("Texto original:\ncita\nsegunda línea"))
        assertTrue(markdown.contains("Reflexión personal:\nreflexión a"))
        assertTrue(plain.contains("Autor: Autora"))
        assertTrue(plain.contains("Localizador: loc:a"))
        assertFalse(plain.contains("> cita"))
    }
}
