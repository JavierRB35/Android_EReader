package com.javierrb35.bibliotecaia.reader.epub

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prevents a regression where native notes silently vanish in Readium
 * because a native UTF-16 offset is not a DOM CFI.
 */
@RunWith(AndroidJUnit4::class)
class ReadiumNativeNoteBridgeDeviceTest {
    private fun note(id: String, href: String, quote: String = "frase guardada",
                     deleted: Boolean = false) = AnnotationEntity(
        id = id, bookId = "book", locator =
            NativeEpubAnchors.locator(href, "Capítulo", 12, 500, 0.1, quote),
        quote = quote, body = "Mi anotación", kind = "NOTE",
        color = 0xffd4b483.toInt(), createdAt = 1L, updatedAt = 2L,
        deletedAt = if (deleted) 3L else null
    )

    @Test fun bridgeNeverUsesNativeOffsetAsReadiumCfiOrOtherChapter() {
        val first = note("note-one", "OEBPS/chapter-01.xhtml")
        val otherChapter = note("note-other", "OEBPS/chapter-02.xhtml")
        val deleted = note("note-deleted", "OEBPS/chapter-01.xhtml", deleted = true)
        val notes = ReadiumNativeNoteBridge.notesForHref(
            listOf(first, otherChapter, deleted), "OEBPS/chapter-01.xhtml")
        assertEquals(listOf(first.id), notes.map { it.id })
        val paint = ReadiumNativeNoteBridge.decorateScript(notes)
        assertTrue(paint.contains("document.createRange()"))
        assertTrue(paint.contains("CSS.highlights.set"))
        assertTrue(paint.contains("\"id\":\"note-one\""))
        assertFalse(paint.contains("note-other"))
        assertFalse(paint.contains("note-deleted"))
        assertFalse(paint.contains("\"cfi\""))
    }

    @Test fun tapScriptUsesSameVerifiedDomRangeAndNeverProducesUnvalidatedId() {
        val safeQuote = "Decía: «esto\\ y \"aquello\"»"
        val notes = listOf(note("saved-note", "Text/1.xhtml", safeQuote))
        val script = ReadiumNativeNoteBridge.hitTestScript(100f, 50f, notes)
        assertTrue(script.contains("range.getClientRects()"))
        assertTrue(script.contains("\"saved-note\""))
        assertTrue(script.contains("normalized.indexOf(quote, pos + 1)"))
        assertFalse(script.contains("window.open("))
        assertEquals("null", ReadiumNativeNoteBridge.hitTestScript(Float.NaN, 5f, notes))
        assertTrue(ReadiumNativeNoteBridge.notesForHref(notes, "Text/2.xhtml").isEmpty())
    }
}
