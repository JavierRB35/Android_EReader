package com.javierrb35.bibliotecaia.ui

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import java.text.DateFormat
import java.util.Date

internal object KnowledgeExport {
    fun scoped(notes: List<AnnotationEntity>, selected: Set<String>?): List<AnnotationEntity> =
        notes.filter { if (selected == null) it.deletedAt == null else it.id in selected }
            .distinctBy { it.id }

    fun render(notes: List<AnnotationEntity>, titles: Map<String, String>,
               authors: Map<String, String>, markdown: Boolean): String = buildString {
        appendLine(if (markdown) "# Cuaderno · Biblioteca IA" else "Cuaderno · Biblioteca IA")
        for (note in notes) {
            val kind = when (note.kind) {
                "HIGHLIGHT" -> "Fragmento"
                "BOOKMARK" -> "Marcador"
                else -> if (note.bookId == null) "Idea" else "Nota"
            }
            appendLine()
            appendLine((if (markdown) "## " else "") + kind + " · " +
                DateFormat.getDateTimeInstance().format(Date(note.createdAt)))
            if (note.bookId != null) {
                appendLine("Libro: " + (titles[note.bookId] ?: "Obra no disponible"))
                authors[note.bookId]?.takeIf { it.isNotBlank() }?.let { appendLine("Autor: $it") }
            }
            if (note.deletedAt != null) appendLine("Estado: papelera")
            if (note.quote.isNotBlank()) {
                appendLine()
                appendLine("Texto original:")
                note.quote.lineSequence().forEach { appendLine((if (markdown) "> " else "") + it) }
            }
            if (note.body.isNotBlank()) {
                appendLine()
                appendLine("Reflexión personal:")
                appendLine(note.body)
            }
            note.locator?.takeIf { it.isNotBlank() }?.let { appendLine("Localizador: $it") }
            appendLine()
            appendLine("---")
        }
    }
}
