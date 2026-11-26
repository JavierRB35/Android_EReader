package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import java.util.Locale

/**
 * Presentation-only grouping. Exact normalized title and named author, one
 * EPUB and one MOBI at most. Never merge or delete rows, annotations or files.
 * Same-format variants and ambiguous metadata remain independently visible.
 */
internal object BibliographicEditions {
    private fun key(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun group(books: List<BookEntity>): List<List<BookEntity>> {
        val candidates = books.filter {
            it.format != BookFormat.PDF && it.title.isNotBlank() &&
                !it.authors.isNullOrBlank() &&
                key(it.authors.orEmpty()) !in setOf("unknown", "desconocido", "desconocida", "anónimo", "anonimo", "varios", "varios autores", "aa. vv.")
        }.groupBy { key(it.title) to key(it.authors.orEmpty()) }
        val pairs = candidates.values.filter { group ->
            group.size == 2 && group.map { it.format }.toSet() == setOf(BookFormat.EPUB, BookFormat.MOBI)
        }.flatMap { group ->
            val ordered = group.sortedWith(compareBy<BookEntity> { if (it.format == BookFormat.EPUB) 0 else 1 }.thenBy { it.id })
            ordered.map { it.id to ordered }
        }.toMap()
        val seen = mutableSetOf<String>()
        return books.mapNotNull { book ->
            val variants = pairs[book.id] ?: listOf(book)
            if (seen.add(variants.first().id)) variants else null
        }
    }
}
