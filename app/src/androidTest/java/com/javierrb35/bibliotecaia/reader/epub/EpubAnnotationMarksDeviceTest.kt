package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        assertEquals(Decoration.Style.Highlight(tint = note.color, isActive = true), marks.single().style)
        assertEquals("Las palabras escogidas", marks.single().locator.text.highlight)
    }

    @Test fun savedUnderlineStillUsesUnderlineAndDoesNotChangeItsLocator() {
        val mark = note("h1", "HIGHLIGHT")
        val decorations = EpubAnnotationMarks.forReadium(listOf(mark))
        assertEquals(1, decorations.size)
        assertEquals(Decoration.Style.Underline(tint = mark.color, isActive = true), decorations.single().style)
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

    @Test fun nativeNoteCanBeOpenedFromVerifiedReadiumSelection() {
        val text = "El comienzo. Las palabras escogidas siguen aquí."
        val quote = "Las palabras escogidas"
        val at = text.indexOf(quote)
        val native = NativeEpubAnchors.locator(
            "chapter1.xhtml", "Capítulo 1", at, text.length, 0.4, quote)
        val chapter = NativeEpubText.Chapter("chapter1.xhtml", "Capítulo 1", text)
        val overlapping = EpubSelectionNotes.overlapping(
            chapter, "palabras escogidas", listOf(note("n-native", "NOTE", quote, native)))
        assertEquals(listOf("n-native"), overlapping.map { it.id })
    }

    @Test fun readiumNoteCanBeOpenedFromVerifiedPartialSelection() {
        val chapter = NativeEpubText.Chapter(
            "chapter1.xhtml", "Capítulo 1", "Ayer leí Las palabras escogidas con atención.")
        val matches = EpubSelectionNotes.overlapping(chapter, "palabras escogidas", listOf(
            note("other-chapter", "NOTE", locator = readiumLocator("other.xhtml", "Las palabras escogidas")),
            note("readium", "NOTE"),
            note("deleted", "NOTE", deleted = 100L)))
        assertEquals(listOf("readium"), matches.map { it.id })
    }

    @Test fun ambiguousSelectionDoesNotOpenNoteFromWrongOccurrence() {
        val chapter = NativeEpubText.Chapter(
            "chapter1.xhtml", "Capítulo 1", "eco y eco y Las palabras escogidas")
        val mark = note("n", "NOTE", "Las palabras escogidas")
        assertTrue(EpubSelectionNotes.overlapping(chapter, "eco", listOf(mark)).isEmpty())
        assertTrue(EpubSelectionNotes.overlapping(chapter, "palabras que no están", listOf(mark)).isEmpty())
    }

    @Test fun repeatedMarkedQuoteIsNotMovedToAnUnverifiedLocation() {
        val quote = "Las palabras escogidas"
        val chapter = NativeEpubText.Chapter(
            "chapter1.xhtml", "Capítulo 1", "$quote; después $quote")
        assertTrue(EpubSelectionNotes.overlapping(chapter, quote,
            listOf(note("readium", "NOTE"))).isEmpty())
    }

    @Test fun repeatedQuotesOnSeparateLocatorsKeepSeparateDecorationIds() {
        val results = EpubAnnotationMarks.forReadium(listOf(
            note("first", "NOTE", locator = readiumLocator("one.xhtml", "Las palabras escogidas")),
            note("second", "NOTE", locator = readiumLocator("two.xhtml", "Las palabras escogidas"))
        ))
        assertEquals(listOf("first", "second"), results.map { it.id })
        assertNotEquals(results[0].locator.href, results[1].locator.href)
    }

    @Test fun noteSavedAndEditedInRealRoomAppearsAndDisappearsFromReadium() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val bookId = "annotation-device-regression"
            db.bookDao().insert(BookEntity(
                id = bookId, title = "Prueba anotaciones", authors = null,
                format = BookFormat.EPUB, fileName = "chapter.epub", localPath = "",
                contentHash = "edition-1", sizeBytes = 128, addedAt = 1
            ))
            val repo = ReadingRepository(db)
            val locator = readiumLocator("chapter1.xhtml", "Las palabras escogidas")
            val id = repo.addAnnotation(
                bookId, locator, "Las palabras escogidas", "Mi reflexión", "NOTE")
            val saved = repo.observeAnnotations(bookId).first()
            assertEquals(1, saved.size)
            var decorations = EpubAnnotationMarks.forReadium(saved)
            assertEquals(1, decorations.size)
            assertEquals(id, decorations.single().id)
            assertTrue(decorations.single().style is Decoration.Style.Highlight)

            repo.editAnnotation(id, saved.single().updatedAt, "Mi reflexión revisada",
                saved.single().color)
            decorations = EpubAnnotationMarks.forReadium(
                repo.observeAnnotations(bookId).first())
            assertEquals(1, decorations.size)
            assertEquals(id, decorations.single().id)

            repo.setDeleted(id, true)
            assertTrue(EpubAnnotationMarks.forReadium(
                repo.observeAnnotations(bookId).first()).isEmpty())
        } finally {
            db.close()
        }
    }

}
