package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.readium.r2.navigator.Decoration

@RunWith(AndroidJUnit4::class)
class EpubAnnotationMarksDeviceTest {
    private fun note(
        id: String, kind: String, quote: String = "Las palabras escogidas",
        locator: String? = readiumLocator("chapter1.xhtml", quote),
        deleted: Long? = null
    ) = AnnotationEntity(
        id = id, bookId = "book-1", locator = locator,
        quote = quote, body = "Reflexión guardada", kind = kind,
        color = 0xffd4b483.toInt(), createdAt = 1, updatedAt = 1,
        deletedAt = deleted
    )

    private fun readiumLocator(href: String, quote: String) =
        JSONObject().put("href", href).put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("progression", 0.4))
            .put("text", JSONObject().put("highlight", quote))
            .toString()

    @Test fun savedNoteProducesVisibleHighlightOnItsExactExcerpt() {
        val note = note("n1", "NOTE")
        val marks = EpubAnnotationMarks.forReadium(listOf(note))
        assertEquals(1, marks.size)
        assertEquals("n1", marks.single().id)
        assertTrue("A NOTE should be visibly highlighted", marks.single().style is Decoration.Style.Highlight)
        assertEquals(Decoration.Style.Highlight(tint = note.color), marks.single().style)
        assertEquals("Las palabras escogidas", marks.single().locator.text.highlight)
    }

    @Test fun savedUnderlineStillUsesUnderlineAndDoesNotChangeItsLocator() {
        val mark = note("h1", "HIGHLIGHT")
        val decorations = EpubAnnotationMarks.forReadium(listOf(mark))
        assertEquals(1, decorations.size)
        assertEquals(Decoration.Style.Underline(mark.color), decorations.single().style)
        assertEquals("Las palabras escogidas", decorations.single().locator.text.highlight)
        assertEquals("chapter1.xhtml", decorations.single().locator.href.toString())
    }

    @Test fun pageNotesBookmarksAndDeletedEntriesNeverInventTextMarks() {
        val all = listOf(
            note("free", "NOTE", "", locator = readiumLocator("chapter1.xhtml", "")),
            note("bookmark", "BOOKMARK"),
            note("deleted", "NOTE", deleted = 100L),
            note("missing", "NOTE", locator = null),
            note("broken", "NOTE", locator = "{"),
            note("wrong", "NOTE", locator = readiumLocator("chapter1.xhtml", "Otra cita"))
        )
        assertTrue(EpubAnnotationMarks.forReadium(all).isEmpty())
    }

    @Test fun nativeOffsetsRemainOwnedByTheNativeReader() {
        val native = NativeEpubAnchors.locator(
            "chapter1.xhtml", "Capítulo 1", 0, 30, 0.2, "Las palabras escogidas")
        assertTrue(EpubAnnotationMarks.forReadium(
            listOf(note("native", "NOTE", locator = native))).isEmpty())
    }

    @Test fun repeatedQuotesOnSeparateLocatorsKeepSeparateDecorationIds() {
        val results = EpubAnnotationMarks.forReadium(listOf(
            note("first", "NOTE", locator = readiumLocator("one.xhtml", "Las palabras escogidas")),
            note("second", "NOTE", locator = readiumLocator("two.xhtml", "Las palabras escogidas"))
        ))
        assertEquals(listOf("first", "second"), results.map { it.id })
        assertNotEquals(results[0].locator.href, results[1].locator.href)
    }
}
