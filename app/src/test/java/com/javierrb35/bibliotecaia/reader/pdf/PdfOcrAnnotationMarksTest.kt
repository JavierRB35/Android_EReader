package com.javierrb35.bibliotecaia.reader.pdf

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test

class PdfOcrAnnotationMarksTest {
    private val edition = "edition-hash-1"
    private val box = PdfOcrRect(.10f, .20f, .30f, .27f)
    private val snapshot = requireNotNull(PdfOcrGeometry.fromLines(
        listOf(listOf("anotado" to box)), edition
    ))
    private fun note(
        id: String, kind: String = "NOTE",
        locator: String? = PdfOcrAnchors.anchoredLocator(2, 0, "anotado", snapshot),
        quote: String = "anotado", deletedAt: Long? = null
    ) = AnnotationEntity(id, "book-1", locator, quote,
        "Nota de lectura", kind, 0xffd4b483.toInt(), 1, 1, deletedAt)

    @Test fun savedNoteAndUnderlineArePaintedOnPdfAndRemainDistinct() {
        val painted = PdfOcrAnnotationMarks.forPage(
            listOf(note("n"), note("h", "HIGHLIGHT")), 2, edition, snapshot)
        assertEquals(2, painted.size)
        assertEquals(listOf("n", "h"), painted.map { it.annotationId })
        assertEquals(listOf("NOTE", "HIGHLIGHT"), painted.map { it.kind })
        assertEquals(listOf(box, box), painted.map { it.bounds })
    }

    @Test fun noSpuriousMarksForPageNotesDeletedNotesOrOtherEditions() {
        val saved = listOf(
            note("free", locator = """{"format":"PDF","page":2}""", quote = ""),
            note("deleted", deletedAt = 20),
            note("bookmark", kind = "BOOKMARK"),
            note("wrong-page", locator = PdfOcrAnchors.anchoredLocator(3, 0, "anotado", snapshot))
        )
        assertTrue(PdfOcrAnnotationMarks.forPage(saved, 2, edition, snapshot).isEmpty())
        assertTrue(PdfOcrAnnotationMarks.forPage(listOf(note("n")), 2, "other-hash", null).isEmpty())
    }

    @Test fun annotationRemainsVisibleWithoutLocalOcrCacheAfterBackupRestore() {
        val painted = PdfOcrAnnotationMarks.forPage(listOf(note("n")), 2, edition, null)
        assertEquals(1, painted.size)
        assertEquals(box, painted.single().bounds)
        assertEquals("NOTE", painted.single().kind)
    }

    @Test fun changedOcrExcerptNeverHighlightsAnUnrelatedWord() {
        val altered = snapshot.copy(text = "otro     ")
        assertTrue(PdfOcrAnnotationMarks.forPage(listOf(note("n")), 2, edition, altered).isEmpty())
    }
}
