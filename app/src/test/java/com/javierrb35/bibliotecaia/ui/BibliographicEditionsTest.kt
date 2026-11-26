package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import org.junit.Assert.*
import org.junit.Test

class BibliographicEditionsTest {
    private fun book(id: String, title: String, author: String?, format: BookFormat) =
        BookEntity(id, title, author, format, "$id.${format.name.lowercase()}", "", id, 1L, 1L)
    @Test fun groupsCompatibleFormatsWithoutLosingAnyOriginal() {
        val epub = book("e", "1984 (Traducción A)", "George Orwell", BookFormat.EPUB)
        val mobi = book("m", "1984 (Traducción A)", "George Orwell", BookFormat.MOBI)
        val other = book("o", "1984 (Traducción B)", "George Orwell", BookFormat.EPUB)
        val input = listOf(mobi, other, epub)
        val grouped = BibliographicEditions.group(input)
        assertEquals(listOf(epub, mobi), grouped.first())
        assertEquals(2, grouped.size)
        assertEquals(input.toSet(), grouped.flatten().toSet())
        assertEquals(4, BibliographicEditions.group(input + epub.copy(id = "e2")).size)
    }
    @Test fun ambiguousOrUnknownAuthorsNeverCollapse() {
        val e = book("e", "Libro", "Unknown", BookFormat.EPUB)
        val m = book("m", "Libro", "Unknown", BookFormat.MOBI)
        assertEquals(2, BibliographicEditions.group(listOf(e,m)).size)
        val known = listOf(e.copy(authors = "Autor"), m.copy(authors = "Autor"), e.copy(id = "e2", authors = "Autor"))
        assertEquals(3, BibliographicEditions.group(known).size)
    }
}
