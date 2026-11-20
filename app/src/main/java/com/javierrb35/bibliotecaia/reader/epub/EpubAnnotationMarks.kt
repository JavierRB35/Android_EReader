package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/**
 * Readium decorations for saved excerpts. Notes must be visible on the same
 * selected passage as underlines; an entry in the notebook alone is not enough.
 *
 * A page bookmark or a free note without a verified highlighted quote has no
 * text range and must never mark an arbitrary passage.
 */
internal object EpubAnnotationMarks {
    fun forReadium(annotations: List<AnnotationEntity>): List<Decoration> =
        annotations.asSequence().filter { note ->
            note.deletedAt == null &&
                note.kind in setOf("NOTE", "HIGHLIGHT") &&
                note.quote.isNotBlank() &&
                NativeEpubAnchors.point(note.locator) == null
        }.mapNotNull { note ->
            val locator = runCatching {
                Locator.fromJSON(JSONObject(note.locator ?: return@mapNotNull null))
            }.getOrNull() ?: return@mapNotNull null
            // Unlike bookmarks, selections carry Readium's excerpt text.
            // Require exact text for notes to avoid highlighting another quote.
            if (note.kind == "NOTE" && locator.text.highlight != note.quote)
                return@mapNotNull null
            val style = if (note.kind == "NOTE")
                Decoration.Style.Highlight(tint = note.color, isActive = true)
            else
                Decoration.Style.Underline(note.color)
            Decoration(note.id, locator, style)
        }.toList()
}
