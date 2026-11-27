package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.AnnotationEntity

/** Adaptation of Scriptura's per-book annotation index; no biblical canon on arbitrary books. */
internal object KnowledgeBookIndex {
    const val ALL = "__ALL_BOOKS__"
    const val INDEPENDENT = "__INDEPENDENT__"

    data class Choice(val id: String, val title: String, val count: Int)

    fun byKind(notes: List<AnnotationEntity>, kind: String) = when (kind) {
        "NOTE" -> notes.filter { it.kind == "NOTE" && it.bookId != null }
        "BOOKMARK" -> notes.filter { it.kind == "BOOKMARK" }
        "QUOTE" -> notes.filter { it.kind != "BOOKMARK" && it.quote.isNotBlank() }
        "IDEAS" -> notes.filter { it.kind == "NOTE" && it.bookId == null }
        else -> notes
    }

    fun byBook(notes: List<AnnotationEntity>, selected: String): List<AnnotationEntity> =
        when (selected) {
            ALL -> notes
            INDEPENDENT -> notes.filter { it.bookId == null }
            else -> notes.filter { it.bookId == selected }
        }

    fun choices(notes: List<AnnotationEntity>, titles: Map<String, String>): List<Choice> {
        val counts = notes.groupingBy { it.bookId ?: INDEPENDENT }.eachCount()
        val byTitle = counts.filterKeys { it != INDEPENDENT }.map { (id, count) ->
            Choice(id, titles[id] ?: "Libro no disponible", count)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return listOf(Choice(ALL, "Todas las obras", notes.size)) +
            (if (counts.containsKey(INDEPENDENT)) listOf(Choice(INDEPENDENT, "Notas independientes",
                counts.getValue(INDEPENDENT))) else emptyList()) + byTitle
    }
}
