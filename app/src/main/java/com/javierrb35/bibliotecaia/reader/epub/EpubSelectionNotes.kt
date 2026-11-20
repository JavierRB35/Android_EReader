package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.data.AnnotationEntity

/**
 * Conservative bridge between Readium DOM selections and the normalized
 * chapter offsets used by the native EPUB reader.
 *
 * Never guess which occurrence the user selected: for ambiguous or changed
 * text, the annotation remains available in "Notas" and "Conocimiento".
 */
internal object EpubSelectionNotes {
    fun overlapping(
        chapter: NativeEpubText.Chapter,
        selectedQuote: String,
        annotations: List<AnnotationEntity>
    ): List<AnnotationEntity> {
        if (selectedQuote.isBlank()) return emptyList()
        val start = chapter.text.indexOf(selectedQuote)
        if (start < 0 || chapter.text.indexOf(selectedQuote, start + 1) >= 0)
            return emptyList()
        val last = start + selectedQuote.length - 1
        return annotations.asSequence().filter {
            it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT") && it.quote.isNotBlank()
        }.filter { annotation ->
            val range = NativeEpubAnchors.validatedRange(
                annotation.locator, annotation.quote, chapter.href, chapter.text
            ) ?: NativeEpubAnchors.uniqueLegacyReadiumRange(
                annotation.locator, annotation.quote, chapter.href, chapter.text
            )
            range != null && range.first <= last && start <= range.last
        }.distinctBy { it.id }.toList()
    }
}
